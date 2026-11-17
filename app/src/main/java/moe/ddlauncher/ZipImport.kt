package moe.ddlauncher

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * zip 导入。
 *
 * 移植层自己的导入器只收 zip，而且要求「选中的那一层就是要放进 mods 的文件夹」，
 * 一个包只能一个 mod，一个个挑。这里把麻烦的部分全部自动化：
 *  - 自动剥掉多余的目录层级（"Darkest Dungeon/data/mods/xxx/"、"xxx/"、甚至 __MACOSX 混在里面）
 *  - 自动跳过 __MACOSX / .DS_Store / desktop.ini / .git
 *  - project.xml 是 UTF-16 的自动转 UTF-8（否则引擎读不出来）
 *  - 完全没有 project.xml 的自动补一个（否则游戏根本看不见它）
 *  - 同工坊 ID 的旧版本自动替换，不留一堆重复目录
 */
class ZipImport(private val repo: ModRepo) {

    private val badName = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

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
                val entries = zip.entries().toList().filter { !it.isDirectory && !isJunk(it.name) }
                if (entries.isEmpty()) return ImportOutcome(short, false, "压缩包是空的")

                val names = entries.map { normalize(it.name) }
                val projectEntry = entries
                    .filterIndexed { i, _ -> names[i].substringAfterLast('/').equals("project.xml", true) }
                    .minByOrNull { normalize(it.name).count { c -> c == '/' } }

                val root = if (projectEntry != null) {
                    val dir = normalize(projectEntry.name).substringBeforeLast('/', "")
                    if (dir.isEmpty()) "" else "$dir/"
                } else {
                    guessRoot(names)
                }

                val meta = try {
                    projectEntry?.let { ProjectXml.meta(ProjectXml.readText(zip.getInputStream(it).readBytes())) }
                        ?: ProjectXml.Meta()
                } catch (t: Throwable) {
                    ProjectXml.Meta()
                }

                val root2 = Game.modsDir()
                root2.mkdirs()

                // 同工坊 ID → 认为是这个 mod 的新版本，直接替换旧目录
                var replaced = ""
                if (meta.workshopId.isNotEmpty()) {
                    val old = repo.scan().firstOrNull { it.workshopId == meta.workshopId }
                    if (old != null) {
                        replaced = old.dir.name
                        repo.delete(old)
                    }
                }

                var dest = File(root2, safeName(label) ?: "mod")
                if (replaced.isNotEmpty() && File(root2, replaced).exists().not()) {
                    dest = File(root2, replaced)
                }
                var n = 2
                while (dest.exists()) {
                    dest = File(root2, "${dest.name.substringBeforeLast(" (")} ($n)")
                    n++
                }
                if (!dest.mkdirs()) return ImportOutcome(short, false, "建不了目录：${dest.path}")

                val total = entries.size
                var done = 0
                var bytes = 0L
                var skipped = 0

                for ((index, entry) in entries.withIndex()) {
                    val rel = extractRel(names[index], root)
                    if (rel == null) {
                        skipped++
                        continue
                    }
                    val target = File(dest, rel)
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        skipped++
                        continue
                    }
                    runCatching {
                        zip.getInputStream(entry).use { input ->
                            BufferedOutputStream(target.outputStream()).use { out -> bytes = input.copyTo(out, 1 shl 16) + bytes }
                        }
                    }.onSuccess { done++ }.onFailure { skipped++ }

                    if (index % 20 == 0 || index == total - 1) onProgress(index + 1, total)
                }

                if (done == 0) {
                    repo.deleteTree(dest)
                    return ImportOutcome(short, false, "压缩包里没找到能用的文件")
                }

                // 兜底：补 project.xml / 把 UTF-16 转成 UTF-8
                val projectFile = File(dest, "project.xml")
                if (!projectFile.isFile) {
                    val title = meta.title.ifBlank { dest.name }
                    projectFile.writeText(ProjectXml.template(title), Charsets.UTF_8)
                } else {
                    runCatching {
                        val bytesP = projectFile.readBytes()
                        val isUtf16 = bytesP.size >= 2 && (
                            ((bytesP[0].toInt() and 0xFF) == 0xFF && (bytesP[1].toInt() and 0xFF) == 0xFE) ||
                                ((bytesP[0].toInt() and 0xFF) == 0xFE && (bytesP[1].toInt() and 0xFF) == 0xFF)
                            )
                        if (isUtf16) projectFile.writeText(ProjectXml.readText(projectFile), Charsets.UTF_8)
                    }
                }

                val size = repo.humanSize(bytes)
                val extra = buildString {
                    if (replaced.isNotEmpty()) append("，替换旧版本 $replaced")
                    if (skipped > 0) append("，跳过 $skipped 个")
                }
                return ImportOutcome(short, true, "已放到 ${dest.name}（$done 个文件，$size$extra）")
            }
        } catch (t: Throwable) {
            return ImportOutcome(short, false, t.message ?: t.javaClass.simpleName)
        } finally {
            temp.delete()
        }
    }

    /** 没有 project.xml 时猜 mod 根目录：只有一个顶层文件夹就用它，否则当成平铺 */
    private fun guessRoot(names: List<String>): String {
        val firsts = names.map { it.substringBefore('/') }.distinct()
        if (firsts.size == 1 && names.any { it.contains('/') }) {
            val only = firsts.first().lowercase(Locale.ROOT)
            if (only == "assets" || only == "mods" || only == "data" || only == "darkest dungeon") {
                // 再往下一层看，例如 mods/MyMod/...
                val depth2 = names.mapNotNull { n ->
                    val p = n.split('/')
                    if (p.size > 2) p[1] else null
                }.distinct()
                if (depth2.size == 1) return "${firsts.first()}/${depth2.first()}/"
            }
            return "${firsts.first()}/"
        }
        val known = Game.DATA_DIRS.map { it.lowercase(Locale.ROOT) }.toSet()
        if (firsts.all { it.lowercase(Locale.ROOT) in known }) return ""
        return ""
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
