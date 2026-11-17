package moe.ddlauncher

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 数据包 = 游戏本体的 Switch romfs 资源（exefs/main + romfs + aoc + overlay）。
 *
 * 移植层的 NxActivity.data() 里有个分支：APK 里的数据文件 ≤1000 个时，改从这个目录读
 * （已实测：`nx: the APK carries 0 files of the data` → `nx: started true`）。
 * 所以本 APK 不带 assets/nx，全靠这里导入的数据。
 */
object DataPack {

    private const val PREFIX = "assets/nx/"
    private const val MARKER = "exefs/main"
    private val TOP_DIRS = listOf("exefs", "romfs", "aoc", "overlay", "overlay_ru", "mods")

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "nx")

    fun isInstalled(context: Context): Boolean = File(dir(context), MARKER).isFile

    fun sizeOf(context: Context): Long {
        val root = dir(context)
        if (!root.isDirectory) return 0
        var total = 0L
        for (name in TOP_DIRS) total += treeSize(File(root, name))
        return total
    }

    private fun treeSize(root: File): Long {
        if (!root.exists()) return 0
        var sum = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (k.isDirectory) stack.addLast(k) else sum += k.length()
            }
        }
        return sum
    }

    data class Progress(val bytes: Long, val entries: Int, val current: String)

    data class Result(val ok: Boolean, val message: String)

    /**
     * 从用户选的 APK / zip 里流式抽出 assets/nx 下的内容。
     * 不落地临时副本（3.6 GB 再复制一份太浪费），直接顺序读 zip 流。
     */
    fun install(
        context: Context,
        uri: Uri,
        onProgress: (Progress) -> Unit,
    ): Result {
        val base = context.getExternalFilesDir(null) ?: return Result(false, "外部存储不可用")
        val staging = File(base, "nx.import")
        deleteTree(staging)
        if (!staging.mkdirs()) return Result(false, "无法创建临时目录")

        val free = base.usableSpace
        if (free < 4L * 1024 * 1024 * 1024) {
            return Result(false, "剩余空间不足（需要约 4 GB，现在只有 ${gb(free)}）")
        }

        var bytes = 0L
        var entries = 0
        var lastReport = 0L

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result(false, "读不到这个文件（授权失效？）")
            BufferedInputStream(input, 1 shl 16).use { raw ->
                val head = ByteArray(4)
                raw.mark(4)
                val n = raw.read(head)
                raw.reset()
                if (n < 4 || head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
                    return Result(false, "这不是 zip/apk 包（只认原版 APK 或压缩成 zip 的数据包）")
                }

                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && name.startsWith(PREFIX)) {
                            val rel = name.substring(PREFIX.length)
                            if (rel.isNotBlank() && !rel.endsWith("/") && !rel.contains("..")) {
                                val target = File(staging, rel)
                                val parent = target.parentFile
                                if (parent != null && (parent.isDirectory || parent.mkdirs())) {
                                    BufferedOutputStream(target.outputStream()).use { out ->
                                        val buf = ByteArray(1 shl 16)
                                        while (true) {
                                            val r = zip.read(buf)
                                            if (r <= 0) break
                                            out.write(buf, 0, r)
                                            bytes += r
                                        }
                                    }
                                    entries++
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport > 400) {
                                        lastReport = now
                                        onProgress(Progress(bytes, entries, rel))
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (t: Throwable) {
            deleteTree(staging)
            return Result(false, "解压失败：${t.message ?: t.javaClass.simpleName}")
        }

        if (!File(staging, MARKER).isFile) {
            deleteTree(staging)
            return Result(false, "这个包里没有游戏的 exefs/main，不像是 Darkest Dungeon 的安装包")
        }

        // 换入：只替换数据目录，不动 save/ 和 nx-report.txt
        val root = dir(context)
        if (!root.isDirectory && !root.mkdirs()) return Result(false, "无法创建数据目录")
        for (name in TOP_DIRS) {
            val from = File(staging, name)
            if (!from.exists()) continue
            val to = File(root, name)
            deleteTree(to)
            if (!from.renameTo(to)) {
                return Result(false, "移动 $name 失败（空间不足？）")
            }
        }
        deleteTree(staging)
        onProgress(Progress(bytes, entries, "完成"))
        return Result(true, "数据包已导入：${entries} 个文件，${gb(bytes)}")
    }

    fun remove(context: Context): Boolean {
        val root = dir(context)
        var ok = true
        for (name in TOP_DIRS) {
            if (name == "mods") continue
            ok = deleteTree(File(root, name)) && ok
        }
        return ok
    }

    private fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
        return file.delete()
    }

    fun gb(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.0f MB", bytes / 1048576.0)
        else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    }
}
