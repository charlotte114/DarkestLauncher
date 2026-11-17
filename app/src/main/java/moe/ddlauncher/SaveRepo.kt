package moe.ddlauncher

import android.os.Environment
import java.util.Collections
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 存档快照：把 `<外部存储>/Android/data/com.darkestdungeon/files/nx/save` 打成 zip。
 *
 * 为什么要有：mod 的坑（尤其是战役/角色类）能直接把存档搞坏，而这游戏的存档在
 * `Android/data/<包名>` 下面，第三方文件管理器根本读不到 —— 只有 App 自己（包名就是
 * com.darkestdungeon）能碰。所以备份必须由启动器来做。
 *
 * 快照放在 `/sdcard/Documents/DarkestSaves/`：卸载 App 不会连带删掉，用户也能直接
 * 用数据线拷走。思路来自 ModinMobileSTS/Sts2MobileLauncher 的 LocalSaveSnapshotManager
 * （MIT），做了精简：不存 metadata JSON，时间/原因直接写进文件名。
 */
object SaveRepo {

    private const val KEEP = 20
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    data class Snapshot(
        val file: File,
        val createdAt: Long,
        val sizeBytes: Long,
        val reason: String,
    ) {
        val id: String get() = file.name
        val timeText: String
            get() = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(createdAt))
        val reasonText: String
            get() = when (reason) {
                "manual" -> "手动备份"
                "before-restore" -> "还原前自动备份"
                "before-import" -> "导入前自动备份"
                "imported" -> "导入的存档"
                "before-launch" -> "启动前自动备份"
                "before-update" -> "更新 mod 前自动备份"
                else -> reason
            }
    }

    fun saveDir(): File = Game.saveDir()

    fun snapDir(): File =
        File(Environment.getExternalStorageDirectory(), "Documents/DarkestSaves")

    fun hasSaves(): Boolean {
        val d = saveDir()
        return d.isDirectory && (d.walkTopDown().any { it.isFile })
    }

    fun list(): List<Snapshot> {
        val dir = snapDir()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) && !it.name.startsWith(".") }
            .mapNotNull { f ->
                val reason = f.name.substringAfterLast('_').removeSuffix(".zip").ifBlank { "manual" }
                runCatching {
                    Snapshot(f, f.lastModified(), f.length(), reason)
                }.getOrNull()
            }
            .sortedByDescending { it.createdAt }
    }

    /** 打一份快照，返回生成的文件 */
    fun create(reason: String): Snapshot {
        val src = saveDir()
        require(src.isDirectory) { "找不到存档目录：${src.absolutePath}" }
        val files = src.walkTopDown().filter { it.isFile }.toList()
        require(files.isNotEmpty()) { "存档目录是空的，没东西可备份" }

        val dir = snapDir()
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("建不了备份目录：${dir.absolutePath}")

        val name = "DD-save_${stamp.format(Date())}_$reason.zip"
        val target = File(dir, name)
        val tmp = File(dir, ".$name.tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            files.forEach { f ->
                val rel = f.relativeTo(src).path.replace('\\', '/')
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        prune()
        return Snapshot(target, target.lastModified(), target.length(), reason)
    }

    /** 还原自己备份过的快照 */
    fun restore(snapshot: Snapshot) {
        require(snapshot.file.isFile) { "备份文件不见了：${snapshot.file.name}" }
        installZip(snapshot.file, "before-restore")
    }

    /**
     * 导入外部存档：用户在文件管理器里挑一个 zip（别人给的、电脑上拷的、网盘下的）。
     * 装进去之前会自动把当前存档备份一份，装完再把这个 zip 存进快照列表，方便反悔。
     */
    fun importFrom(source: File): Snapshot {
        require(source.isFile) { "选中的文件读不到" }
        require(source.length() > 0) { "这个文件是空的" }
        installZip(source, "before-import")

        val dir = snapDir()
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("建不了备份目录：${dir.absolutePath}")
        val name = "DD-save_${stamp.format(Date())}_imported.zip"
        val target = File(dir, name)
        source.copyTo(target, overwrite = true)
        target.setLastModified(System.currentTimeMillis())
        prune()
        return Snapshot(target, target.lastModified(), target.length(), "imported")
    }

    /**
     * 把某个 zip 装成当前存档。先解到 staging 校验，再把现有存档整目录挪走，最后把
     * staging 换进来——中间任何一步失败都不会把存档搞没。
     *
     * staging / rollback 必须和 `save` 在**同一个文件系统**里（都放 `files/nx/` 下）：
     * `Android/data` 和 `Documents` 是两次不同的 FUSE 挂载，跨挂载点 renameTo 直接返回 false。
     */
    private fun installZip(zipFile: File, backupReason: String) {
        val save = saveDir()
        val host = save.parentFile ?: throw IllegalStateException("找不到存档的上级目录")
        host.mkdirs()
        val suffix = System.nanoTime()
        val staging = File(host, ".restore-staging-$suffix")
        val rollback = File(host, ".restore-rollback-$suffix")
        staging.mkdirs()
        try {
            var count = 0
            ZipFile(zipFile).use { zip ->
                val entries = Collections.list(zip.entries()).filter { !it.isDirectory }
                // 别处来的包常常多一层 save/ 或 nx/save/，剥掉才认得
                val prefix = rootPrefixOf(entries.map { it.name })
                entries.forEach { e ->
                    if (e.name.startsWith("__MACOSX/") || e.name.endsWith(".DS_Store")) return@forEach
                    val rel = e.name.removePrefix(prefix)
                    if (rel.isBlank()) return@forEach
                    val out = File(staging, rel)
                    if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw IllegalStateException("压缩包里有非法路径：${e.name}")
                    }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    count++
                }
            }
            require(count > 0) { "压缩包里没有文件" }

            // 覆盖前先把当前存档留一份，用户反悔还能退回去
            if (hasSaves()) runCatching { create(backupReason) }

            val hadSave = save.exists()
            if (hadSave && !save.renameTo(rollback)) {
                throw IllegalStateException("挪不开当前存档（可能游戏还开着，或存储占用满了）")
            }
            if (!staging.renameTo(save)) {
                if (hadSave) rollback.renameTo(save)
                throw IllegalStateException("替换存档失败，已回滚")
            }
            rollback.deleteRecursively()
            prune()
        } finally {
            staging.deleteRecursively()
            rollback.deleteRecursively()
        }
    }

    /**
     * 找出包里多出来的公共根目录（`save/`、`nx/save/`、`Android/data/<包名>/files/nx/save/`…）。
     * 只剥白名单里的段，避免把存档自己的 `profile_0/` 也剥掉。
     */
    private fun rootPrefixOf(names: List<String>): String {
        val segs = names.map { it.split('/') }.filter { it.size >= 2 }
        if (segs.isEmpty()) return ""
        val maxDepth = segs.minOf { it.size } - 1
        val allowed = setOf(
            "save", "nx", "files", "android", "data",
            "com.darkestdungeon", "darkestdungeon",
        )
        var depth = 0
        var prefix = ""
        while (depth < maxDepth) {
            val first = segs[0][depth]
            val same = segs.all { it.size > depth + 1 && it[depth] == first }
            if (same && first.lowercase(Locale.ROOT) in allowed) {
                prefix += "$first/"
                depth++
            } else {
                break
            }
        }
        return prefix
    }

    fun delete(snapshot: Snapshot) {
        snapshot.file.delete()
    }

    /** 只留最近 KEEP 份，别把存储塞满 */
    private fun prune(keep: Int = KEEP) {
        list().drop(keep).forEach { runCatching { it.file.delete() } }
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
