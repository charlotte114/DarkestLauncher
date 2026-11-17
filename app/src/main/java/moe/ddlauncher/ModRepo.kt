package moe.ddlauncher

import java.io.File
import java.util.Locale

/**
 * mod 目录的读写都在这里。
 *
 * 关键事实（从游戏 dex / 原生库确认）：
 *  - 游戏只在文件夹里存在 project.xml 时才把它当 mod  → 所以「禁用」= 把 project.xml 改名，
 *    这是全量可逆的，不动游戏私有存档，也不需要 root。
 *  - 每次启动游戏都会跑 NxMods.prepareAll()，把 PC 格式 mod 转成手机版并写 .port-switch 指纹，
 *    所以启动器不需要自己转换，只负责「把文件夹放对」。
 */
class ModRepo {

    private val metadataNames = setOf(
        "project.xml", "project.xml.off", "project.xml.broken",
        "modfiles.txt", "modfiles.txt.pc", "filelist.csv",
        "preview_icon.png", "preview_icon.dds", "preview_icon.jpg",
        "modinfo.json", "readme", "introduce.txt",
    )

    fun ensureDir() {
        val root = Game.modsDir()
        if (!root.isDirectory) root.mkdirs()
        val noMedia = File(root, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
    }

    fun scan(): List<ModInfo> {
        val root = Game.modsDir()
        if (!root.isDirectory) return emptyList()
        val marks = Game.switchMarks()
        val list = ArrayList<ModInfo>()
        for (child in root.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            if (child.name.startsWith(".")) continue
            val info = read(child, File(marks, child.name).isFile) ?: continue
            list.add(info)
        }
        return list.sortedWith(
            compareBy<ModInfo> { !it.enabled }.thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    private fun read(dir: File, prepared: Boolean): ModInfo? {
        val on = File(dir, "project.xml")
        val off = File(dir, "project.xml.off")
        val xml = when {
            on.isFile -> on
            off.isFile -> off
            else -> null
        }
        val looksLikeMod = xml != null || Game.DATA_DIRS.any { File(dir, it).isDirectory }
        if (!looksLikeMod) return null

        val notes = ArrayList<String>()
        var meta = ProjectXml.Meta()
        if (xml != null) {
            try {
                meta = ProjectXml.meta(ProjectXml.readText(xml))
            } catch (t: Throwable) {
                notes.add("project.xml 读不动：${t.message ?: t.javaClass.simpleName}")
            }
            if (meta.title.isBlank()) notes.add("project.xml 里没有 Title")
        } else {
            notes.add("缺 project.xml")
        }

        if (File(dir, "project.xml.broken").isFile) notes.add("曾被移植层判定为损坏（留了 project.xml.broken）")
        if (File(dir, "modfiles.txt.pc").isFile) notes.add("modfiles.txt 已按 PC 清单备份")

        var icon = meta.previewIcon?.let { File(dir, it) }?.takeIf { it.isFile }
        if (icon == null) {
            icon = listOf("preview_icon.png", "preview_icon.dds", "preview_icon.jpg")
                .map { File(dir, it) }
                .firstOrNull { it.isFile }
        }

        var files = 0
        var bytes = 0L
        walk(dir) { f ->
            files++
            bytes += f.length()
        }

        return ModInfo(
            dir = dir,
            title = meta.title,
            version = meta.version,
            workshopId = meta.workshopId,
            language = meta.language,
            tags = meta.tags,
            description = meta.description,
            iconFile = icon,
            enabled = on.isFile,
            prepared = prepared,
            fileCount = files,
            sizeBytes = bytes,
            modified = dir.lastModified(),
            notes = notes,
        )
    }

    /** 启用 = 恢复 project.xml；禁用 = 改名 project.xml.off（游戏就不再认得这个文件夹） */
    fun setEnabled(info: ModInfo, enabled: Boolean): Boolean {
        val on = File(info.dir, "project.xml")
        val off = File(info.dir, "project.xml.off")
        return if (enabled) {
            when {
                on.isFile -> true
                off.isFile -> off.renameTo(on)
                else -> false
            }
        } else {
            when {
                off.isFile -> true
                on.isFile -> on.renameTo(off)
                else -> false
            }
        }
    }

    fun delete(info: ModInfo): Boolean {
        val ok = deleteTree(info.dir)
        File(Game.switchMarks(), info.dir.name).delete()
        deleteTree(File(Game.nxOriginals(), info.dir.name))
        return ok
    }

    /**
     * 跨 mod 覆盖冲突：DD 没有加载顺序，两个 mod 改同一个文件时谁生效是随机的。
     * .darkest / .json 是数据（会影响玩法），其余算资源。
     */
    fun conflicts(mods: List<ModInfo>): List<Conflict> {
        val owners = HashMap<String, MutableList<String>>()
        val data = HashMap<String, Boolean>()

        for (m in mods) {
            if (!m.enabled) continue
            walk(m.dir) { f ->
                val rel = relative(m.dir, f) ?: return@walk
                val lower = rel.lowercase(Locale.ROOT)
                val leaf = lower.substringAfterLast('/')
                if (leaf in metadataNames) return@walk
                if (lower.startsWith(".port-") || lower.contains("/.port-")) return@walk
                if (leaf.startsWith("._") || leaf == ".ds_store" || leaf == "thumbs.db") return@walk
                if (lower.endsWith(".md") || lower.endsWith(".txt")) return@walk
                owners.getOrPut(lower) { ArrayList() }.let { if (!it.contains(m.displayName)) it.add(m.displayName) }
                data[lower] = lower.endsWith(".darkest") || lower.endsWith(".json")
            }
        }

        return owners.entries
            .filter { it.value.size > 1 }
            .map { Conflict(it.key, it.value.sorted(), data[it.key] == true) }
            .sortedWith(compareByDescending<Conflict> { it.mods.size }.thenBy { it.path })
    }

    // ---- 文件树工具 ----

    private fun walk(root: File, onFile: (File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 250_000) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                guard++
                if (c.isDirectory) {
                    if (c.name.startsWith(".port-")) continue
                    stack.addLast(c)
                } else {
                    if (c.name == ".DS_Store" || c.name == "Thumbs.db") continue
                    onFile(c)
                }
            }
        }
    }

    private fun relative(root: File, file: File): String? {
        val r = root.absolutePath.trimEnd('/')
        val f = file.absolutePath
        if (!f.startsWith("$r/")) return null
        return f.substring(r.length + 1).replace('\\', '/')
    }

    fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it) }
        }
        return file.delete()
    }

    /** 给新建的 mod 目录取一个不重名的名字 */
    fun uniqueName(base: String): String {
        var name = base
        var i = 2
        while (File(Game.modsDir(), name).exists()) {
            name = "$base ($i)"
            i++
        }
        return name
    }

    fun humanSize(bytes: Long): String = when {        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
