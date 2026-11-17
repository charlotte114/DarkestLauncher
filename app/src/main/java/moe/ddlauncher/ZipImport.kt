package moe.ddlauncher

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * zip 导入。
 *
 * 一个 zip 里可能有多个 mod（比如从别人那儿打包的一整个 mods 目录）：
 * 按 project.xml 的位置把每个 mod 拆开，各放各的文件夹。
 * 已经做了这些自动化：
 *  - 剥掉多余的目录层级（"Darkest Dungeon/data/mods/xxx/"、"mods/xxx/"、甚至 __MACOSX）
 *  - 跳过 __MACOSX / .DS_Store / desktop.ini / .git
 *  - project.xml 是 UTF-16 的自动转 UTF-8
 *  - 没有 project.xml 的自动补一个
 *  - 同工坊 ID 的旧版本自动替换
 */
class ZipImport(private val repo: ModRepo) {

    private val badName = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

    /** 常见的「包装层」，不算 mod 本身 */
    private val wrappers = setOf("assets", "mods", "data", "darkest dungeon", "content", "workshop")

    private class Plan(
        val root: String,
        val entries: List<ZipEntry>,
        val names: List<String>,
        val meta: ProjectXml.Meta,
        val fallbackName: String,
    )

    fun import(context: Context, uri: Uri, onProgress: (done: Int, total: Int) -> Unit): ImportOutcome {
        val label = displayName(context, uri) ?: "mod.zip"
        val short = label.take(48)
        val temp = File(context.cacheDir, "import-${System.currentTimeMillis()}.zip")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedOutputStream(temp.outputStream()).use { out -> input.copyTo(out, 1 shl 16) }
            } ?: return ImportOutcome(short, false, "读不到这个文件（授权失效？）")

            val head = ByteArray(8)
            temp.inputStream().use { it.read(head) }
            val magic = when {
                head.size >= 6 && head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
                    head[2] == 0x72.toByte() && head[3] == 0x21.toByte() -> "rar"
                head.size >= 6 && head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() &&
                    head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() -> "7z"
                else -> "zip"
            }
            if (magic != "zip") {
                return ImportOutcome(short, false, "这是 $magic 包，只认 zip，请先解压再重新压成 zip")
            }

            ZipFile(temp).use { zip ->
                val all = zip.entries().toList().filter { !it.isDirectory && !isJunk(it.name) }
                if (all.isEmpty()) return ImportOutcome(short, false, "压缩包是空的")

                val names = all.map { normalize(it.name) }
                val plans = buildPlans(zip, all, names, safeName(label) ?: "mod")
                if (plans.isEmpty()) return ImportOutcome(short, false, "压缩包里没找到能用的文件")

                val rootDir = Game.modsDir()
                rootDir.mkdirs()

                val total = all.size
                var processed = 0
                var done = 0
                var bytes = 0L
                var skipped = 0
                var replaced = 0
                val imported = ArrayList<String>()

                for (plan in plans) {
                    // 同工坊 ID → 认为是这个 mod 的新版本，直接替换旧目录
                    var replacement: String? = null
                    if (plan.meta.workshopId.isNotEmpty()) {
                        val old = repo.scan().firstOrNull { it.workshopId == plan.meta.workshopId }
                        if (old != null) {
                            replacement = old.dir.name
                            repo.delete(old)
                            replaced++
                        }
                    }

                    val name = plan.fallbackName
                    var dest = if (replacement != null) File(rootDir, replacement)
                    else File(rootDir, name)
                    var n = 2
                    while (dest.exists()) {
                        dest = File(rootDir, "${dest.name.substringBeforeLast(" (")} ($n)")
                        n++
                    }
                    if (!dest.mkdirs()) {
                        skipped += plan.entries.size
                        processed += plan.entries.size
                        onProgress(processed, total)
                        continue
                    }

                    var thisDone = 0
                    for ((i, entry) in plan.entries.withIndex()) {
                        val rel = extractRel(plan.names[i], plan.root)
                        if (rel == null) {
                            skipped++
                            processed++
                            continue
                        }
                        val target = File(dest, rel)
                        val parent = target.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            skipped++
                            processed++
                            continue
                        }
                        runCatching {
                            zip.getInputStream(entry).use { input ->
                                BufferedOutputStream(target.outputStream()).use { out ->
                                    bytes = input.copyTo(out, 1 shl 16) + bytes
                                }
                            }
                        }.onSuccess { thisDone++; done++ }.onFailure { skipped++ }

                        processed++
                        if (processed % 20 == 0 || processed == total) onProgress(processed, total)
                    }

                    if (thisDone == 0) {
                        repo.deleteTree(dest)
                        continue
                    }

                    // 兜底：补 project.xml / 把 UTF-16 转成 UTF-8
                    val projectFile = File(dest, "project.xml")
                    if (!projectFile.isFile) {
                        val title = plan.meta.title.ifBlank { dest.name }
                        projectFile.writeText(ProjectXml.template(title), Charsets.UTF_8)
                    } else {
                        runCatching {
                            val bytesP = projectFile.readBytes()
                            val isUtf16 = bytesP.size >= 2 && (
                                ((bytesP[0].toInt() and 0xFF) == 0xFF && (bytesP[1].toInt() and 0xFF) == 0xFE) ||
                                    ((bytesP[0].toInt() and 0xFF) == 0xFE && bytesP[1].toInt() == 0xFF)
                                )
                            if (isUtf16) projectFile.writeText(ProjectXml.readText(projectFile), Charsets.UTF_8)
                        }
                    }
                    imported.add(dest.name)
                }

                if (imported.isEmpty()) {
                    return ImportOutcome(short, false, "压缩包里没找到能用的文件")
                }

                val size = repo.humanSize(bytes)
                val extra = buildString {
                    if (replaced > 0) append("，替换旧版本 $replaced 个")
                    if (skipped > 0) append("，跳过 $skipped 个")
                }
                val detail = if (imported.size == 1) {
                    "已放到 ${imported.first()}（$done 个文件，$size$extra）"
                } else {
                    "识别出 ${imported.size} 个 mod：${imported.joinToString("、")}（$done 个文件，$size$extra）"
                }
                return ImportOutcome(short, true, detail)
            }
        } catch (t: Throwable) {
            return ImportOutcome(short, false, t.message ?: t.javaClass.simpleName)
        } finally {
            temp.delete()
        }
    }

    /**
     * 把压缩包拆成若干 mod：
     *  1. 有 project.xml 的，以它所在的目录为界（最可靠）
     *  2. 没有 project.xml 的，先剥掉公共包装层，再按顶层文件夹拆
     */
    private fun buildPlans(
        zip: ZipFile,
        all: List<ZipEntry>,
        names: List<String>,
        zipLabel: String,
    ): List<Plan> {
        val projectByRoot = LinkedHashMap<String, ZipEntry>()
        names.forEachIndexed { i, n ->
            if (n.substringAfterLast('/').equals("project.xml", true)) {
                val dir = n.substringBeforeLast('/', "")
                val root = if (dir.isEmpty()) "" else "$dir/"
                if (!projectByRoot.containsKey(root)) projectByRoot[root] = all[i]
            }
        }

        val roots = if (projectByRoot.isNotEmpty()) {
            projectByRoot.keys.toList()
        } else {
            rootsWithoutProject(names)
        }

        return roots.mapNotNull { root ->
            val idx = names.indices.filter { names[it].startsWith(root) }
            if (idx.isEmpty()) return@mapNotNull null
            val meta = projectByRoot[root]?.let { entry ->
                runCatching { ProjectXml.meta(ProjectXml.readText(zip.getInputStream(entry).readBytes())) }
                    .getOrDefault(ProjectXml.Meta())
            } ?: ProjectXml.Meta()

            val folderName = root.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            val fallback = safeName(meta.title) ?: safeName(folderName ?: zipLabel) ?: "mod"
            Plan(
                root = root,
                entries = idx.map { all[it] },
                names = idx.map { names[it] },
                meta = meta,
                fallbackName = fallback,
            )
        }
    }

    /** 没有 project.xml 时：剥公共包装层，然后按顶层目录拆成多个 mod */
    private fun rootsWithoutProject(names: List<String>): List<String> {
        var rest = names
        var prefix = ""
        while (true) {
            val firsts = rest.map { it.substringBefore('/') }.distinct()
            if (firsts.size == 1 && firsts.first().lowercase(Locale.ROOT) in wrappers &&
                rest.any { it.contains('/') }
            ) {
                prefix += firsts.first() + "/"
                rest = rest.map { it.substringAfter('/') }.filter { it.isNotBlank() }
            } else break
        }

        val firsts = rest.map { it.substringBefore('/') }.distinct()
        val known = Game.DATA_DIRS.map { it.lowercase(Locale.ROOT) }.toSet()
        if (firsts.size == 1) {
            val f = firsts.first()
            return listOf(prefix + if (f.lowercase(Locale.ROOT) in known || !rest.any { it.contains('/') }) "" else "$f/")
        }
        if (firsts.all { it.lowercase(Locale.ROOT) in known }) return listOf(prefix)

        val mods = firsts.filterNot { it.lowercase(Locale.ROOT) in known }
        val result = mods.map { "$prefix$it/" }.toMutableList()
        if (mods.size != firsts.size) result.add(prefix)
        return result
    }

    private fun extractRel(name: String, root: String): String? {
        val rel = if (root.isEmpty()) name else name.removePrefix(root)
        if (rel.isBlank() || rel.endsWith("/")) return null
        if (rel.startsWith("/")) return null
        val parts = rel.split('/')
        if (parts.any { it == ".." || it == "." }) return null
        if (parts.first().lowercase(Locale.ROOT) == "__macosx") return null
        return parts.joinToString("/")
    }

    private fun normalize(name: String) = name.replace('\\', '/')

    private fun isJunk(raw: String): Boolean {
        val n = normalize(raw)
        val lower = n.lowercase(Locale.ROOT)
        if (lower.startsWith("__macosx/") || lower.contains("/__macosx/")) return true
        if (lower.startsWith(".git/") || lower.contains("/.git/")) return true
        val leaf = lower.substringAfterLast('/')
        return leaf == ".ds_store" || leaf == "thumbs.db" || leaf == "desktop.ini" ||
            leaf.startsWith("._") || leaf == ".nomedia"
    }

    private fun safeName(name: String): String? {
        var base = name.substringBeforeLast('.').ifBlank { name }
        base = badName.replace(base, "_").trim().trim('.')
        if (base.isBlank()) return null
        return base.take(60)
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
        }.getOrNull()
    }
}
