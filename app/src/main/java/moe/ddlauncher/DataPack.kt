package moe.ddlauncher

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
    private const val VERSION_FILE = "pack-version.txt"

    /** 导入时顺手把包里的 AndroidManifest.xml 存一份（几 KB），以后能重新解析版本 */
    private const val MANIFEST_FILE = "pack-manifest.xml"
    private val TOP_DIRS = listOf("exefs", "romfs", "aoc", "overlay", "overlay_ru", "mods")

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "nx")

    fun isInstalled(context: Context): Boolean = File(dir(context), MARKER).isFile

    /**
     * 当前装着的这份数据包对应哪个游戏版本。
     * 优先读导入时记下的版本号；没有记录但存了清单，就重新解析一遍（解析器以后改进了也能自愈）；
     * 都拿不到就返回 null —— 界面显示「版本未知」，绝不拿启动器的 versionName 冒充。
     */
    fun version(context: Context): String? {
        val f = File(dir(context), VERSION_FILE)
        if (f.isFile) {
            val stored = runCatching { PackVersion.normalize(f.readText().trim()) }.getOrNull()
            if (stored != null) return stored
        }
        val manifest = File(dir(context), MANIFEST_FILE)
        if (!manifest.isFile) return null
        val bytes = runCatching { manifest.readBytes() }.getOrNull() ?: return null
        val parsed = PackVersion.fromManifest(bytes) ?: return null
        writeVersion(context, parsed)
        DdLog.i(context, "pack", "版本从存下的清单重新解析出来：$parsed")
        return parsed
    }

    private fun writeVersion(context: Context, value: String?) {
        val f = File(dir(context), VERSION_FILE)
        runCatching {
            if (value.isNullOrBlank()) f.delete() else f.writeText(value)
        }
    }

    private fun writeManifest(context: Context, bytes: ByteArray?) {
        val f = File(dir(context), MANIFEST_FILE)
        runCatching {
            if (bytes == null || bytes.isEmpty()) f.delete() else f.writeBytes(bytes)
        }
    }

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
     * 在 Download（含一层子目录）里找一个可导入的数据包。
     * 不解压、只查 zip 中央目录里有没有 assets/nx/exefs/main，所以几 GB 的包也是秒级。
     * 命中后返回 File，交给 install() 走正常导入流程。
     */
    fun findInDownloads(): File? = downloadCandidates().firstOrNull()

    /** Download（顶层 + 一层子目录）里所有看着像数据包的文件，按修改时间倒序 */
    fun downloadCandidates(): List<File> {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: return emptyList()
        if (!dl.isDirectory) return emptyList()
        val files = ArrayList<File>()
        dl.listFiles()?.forEach { f ->
            if (f.isFile) files.add(f)
            else if (f.isDirectory) f.listFiles()?.filter { it.isFile }?.let { files.addAll(it) }
        }
        files.sortByDescending { it.lastModified() }
        return files.filter { f ->
            val name = f.name.lowercase(Locale.ROOT)
            (name.endsWith(".apk") || name.endsWith(".zip")) &&
                f.length() >= 64L * 1024 * 1024 && // 数据包至少几十 MB，跳过小文件
                looksLikeDataPack(f)
        }
    }

    /** 只读 zip 中央目录，看是否含游戏数据的标记文件 */
    private fun looksLikeDataPack(file: File): Boolean {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("${PREFIX}${MARKER}") != null
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 已经装好、但没有版本记录（早期版本导入的）：从 Download 里那份数据包补一次。
     * 只有当所有候选包认出来的版本一致时才补，避免张冠李戴。
     */
    fun recoverVersion(context: Context): String? {
        val candidates = downloadCandidates()
        if (candidates.isEmpty()) {
            DdLog.i(context, "pack", "版本缺失，Download 里没有候选包，无法补")
            return null
        }
        val found = candidates.map { it to versionFromFile(context, it) }
        val versions = found.mapNotNull { it.second.first }.distinct()
        val ver = if (versions.size == 1) versions[0] else null
        if (ver == null) {
            DdLog.i(
                context, "pack",
                "版本补齐失败：候选 ${candidates.size} 个，版本 ${versions.ifEmpty { listOf("都没认出来") }.joinToString()}"
            )
            return null
        }
        writeManifest(context, found.firstOrNull { it.second.first == ver }?.second?.second)
        writeVersion(context, ver)
        DdLog.i(context, "pack", "版本从 Download 里补齐：$ver")
        return ver
    }

    /** 只读 zip 里的清单 + 文件名，得出一个版本号；顺手把清单字节带回去存起来 */
    private fun versionFromFile(context: Context, file: File): Pair<String?, ByteArray?> {
        var manifest: ByteArray? = null
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml")
                    ?: zip.entries().asSequence().firstOrNull {
                        it.name.substringAfterLast('/').equals("AndroidManifest.xml", true)
                    }
                if (entry != null) {
                    manifest = zip.getInputStream(entry).use { it.readBytes() }
                }
            }
        }.onFailure { DdLog.e(context, "pack", it) }
        val fromLevel = PackVersion.fromManifest(manifest)
        val fromName = PackVersion.fromFileName(file.name)
        DdLog.i(context, "pack", "读包 ${file.name}: 清单 ${manifest?.size ?: 0}B → ${fromLevel ?: "-"}，文件名 → ${fromName ?: "-"}")
        return (fromLevel ?: fromName) to manifest
    }

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
        // 包里带的 AndroidManifest.xml（小文件，顺手读进内存拿 versionName）
        var manifest: ByteArray? = null

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
                        val leaf = name.substringAfterLast('/')
                        if (!entry.isDirectory && manifest == null &&
                            leaf.equals("AndroidManifest.xml", ignoreCase = true)
                        ) {
                            manifest = readCapped(zip, 8 shl 20)
                        } else if (!entry.isDirectory && name.startsWith(PREFIX)) {
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
        val sourceName = displayName(context, uri)
        val ver = PackVersion.fromManifest(manifest) ?: PackVersion.fromFileName(sourceName)
        // 清单存一份：以后解析器有改进，不用重新导入几 GB 也能把版本纠正过来
        writeManifest(context, manifest)
        writeVersion(context, ver)
        DdLog.i(
            context, "pack",
            "导入完成 entries=$entries 清单=${manifest?.size ?: 0}B 版本=${ver ?: "未识别"} 文件名=$sourceName"
        )
        onProgress(Progress(bytes, entries, "完成"))
        val verText = if (ver != null) "，游戏 $ver" else "，版本未识别"
        return Result(true, "数据包已导入：${entries} 个文件，${gb(bytes)}$verText")
    }

    /** 读 sd 卡上的 uri 没法用 ZipFile，只能顺序读；这里只读小文件（清单） */
    private fun readCapped(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 13)
        while (true) {
            val r = input.read(buf)
            if (r <= 0) break
            if (out.size() + r > limit) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    /** 选择器给的 uri 拿不到真实文件名，去问 ContentResolver，拿不到就退回路径尾段 */
    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { File(it).name }
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    fun remove(context: Context): Boolean {
        val root = dir(context)
        var ok = true
        for (name in TOP_DIRS) {
            if (name == "mods") continue
            ok = deleteTree(File(root, name)) && ok
        }
        File(root, VERSION_FILE).delete()
        File(root, MANIFEST_FILE).delete()
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
