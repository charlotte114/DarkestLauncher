package moe.ddlauncher

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 跳过游戏开屏动画。
 *
 * 真正拖时间的是 `romfs/video/house_of_ruin.mp4`（约 2 分钟）和
 * `romfs/video/old_road.mp4`（约 71 秒），`fe_flow.anim.darkest` 里的
 * `.intro_movie` 只是过渡时间，管不到视频本身。
 *
 * 做法：开启时把原视频改名成 `.ddlauncher.orig`，用 APK 里自带的 0.5 秒黑屏
 * 视频顶替；关闭时删掉占位视频并把原文件改回来。只动视频文件，不碰引擎，
 * 所以不会像强制 skipfeflowintro 那样跳过主菜单。
 */
object IntroSkip {

    private const val PREF = "settings"
    private const val PREF_KEY = "skip_intro"
    private const val BACKUP_SUFFIX = ".ddlauncher.orig"
    private const val PLACEHOLDER = "intro_skip.mp4"

    private val targets = listOf(
        "romfs/video/house_of_ruin.mp4",
        "romfs/video/old_road.mp4",
        "overlay_ru/video/house_of_ruin.mp4",
        "overlay_ru/video/old_road.mp4",
    )

    data class Status(
        val available: Boolean,
        val enabled: Boolean,
        val path: String,
        val matched: Int,
        val zeroed: Int,
        /** 每个副本的实际情况，校验失败时直接看这个 */
        val details: String = "",
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun targetFiles(ctx: Context): List<File> =
        targets.map { File(DataPack.dir(ctx), it) }

    private fun backupOf(file: File): File =
        File(file.parentFile, file.name + BACKUP_SUFFIX)

    private fun placeholderBytes(ctx: Context): ByteArray? =
        runCatching { ctx.assets.open(PLACEHOLDER).use { it.readBytes() } }.getOrNull()

    private fun digestOf(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** 占位视频的字节数和摘要；两者要一起比，别拿文件长度去比摘要长度 */
    private class Mark(val size: Int, val digest: ByteArray)

    private fun markOf(ctx: Context): Mark? =
        placeholderBytes(ctx)?.let { Mark(it.size, digestOf(it)) }

    private fun isPlaceholder(file: File, mark: Mark?): Boolean {
        if (mark == null || !file.isFile) return false
        if (file.length() != mark.size.toLong()) return false
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (bytes.size != mark.size) return false
        return digestOf(bytes).contentEquals(mark.digest)
    }

    fun status(ctx: Context): Status {
        val mark = markOf(ctx)
        var available = false
        var matched = 0
        var replaced = 0
        val present = ArrayList<String>()
        val detail = ArrayList<String>()
        for (file in targetFiles(ctx)) {
            val hasOriginal = file.isFile
            val hasBackup = backupOf(file).isFile
            if (!hasOriginal && !hasBackup) continue
            available = true
            matched++
            val placeholder = hasOriginal && isPlaceholder(file, mark)
            if (placeholder) replaced++
            present += file.absolutePath
            detail += "${file.parentFile?.parentFile?.name}/${file.name}" +
                " size=${if (hasOriginal) file.length() else -1}" +
                " ph=$placeholder" +
                " bak=${if (hasBackup) backupOf(file).length() else -1}"
        }
        return Status(
            available = available,
            enabled = available && matched > 0 && replaced == matched,
            path = present.joinToString("\n"),
            matched = matched,
            zeroed = replaced,
            details = detail.joinToString(" | "),
        )
    }

    /** 返回 null 表示成功。 */
    fun setEnabled(ctx: Context, enabled: Boolean): String? {
        val files = targetFiles(ctx)
        // 写盘和校验都用同一份字节，避免「读到的 asset 和刚写进去的不一样」
        val bytes = placeholderBytes(ctx) ?: return "安装包里缺少 $PLACEHOLDER，无法跳过片头"
        val digest = digestOf(bytes)
        val mark = Mark(bytes.size, digest)
        if (files.none { it.isFile || backupOf(it).isFile }) {
            return "还没导入数据包，找不到开屏视频"
        }

        return runCatching {
            for (file in files) {
                val backup = backupOf(file)
                if (enabled) {
                    if (file.isFile && !isPlaceholder(file, mark)) {
                        // 已有更大的备份就别动它，绝不能用小文件把原片覆盖掉
                        val keepBackup = backup.isFile && backup.length() >= file.length()
                        if (!keepBackup && !move(file, backup)) {
                            return "备份 ${file.name} 失败（空间不足？）"
                        }
                    } else if (!file.isFile && !backup.isFile) {
                        continue
                    }
                    file.parentFile?.mkdirs()
                    var ok = writePlaceholder(ctx, file, bytes, mark)
                    if (!ok) {
                        // 某些机型的 FUSE 偶发写不进去，删掉重写一次
                        runCatching { file.delete() }
                        ok = writePlaceholder(ctx, file, bytes, mark)
                    }
                    if (!ok) {
                        val size = if (file.isFile) file.length() else -1
                        DdLog.i(ctx, "introskip", "write failed ${file.absolutePath} size=$size")
                        return "写入 ${file.parentFile?.parentFile?.name}/${file.name} 后校验不一致" +
                            "（实际 ${size} 字节，应为 ${bytes.size} 字节）"
                    }
                } else {
                    if (!backup.isFile) {
                        if (isPlaceholder(file, mark)) {
                            return "找不到 ${file.name} 的原始备份，重新导入一次数据包就能恢复"
                        }
                        continue
                    }
                    if (file.exists() && !file.delete()) {
                        return "删除占位视频失败：${file.absolutePath}"
                    }
                    if (!move(backup, file)) {
                        return "恢复 ${file.name} 失败"
                    }
                }
            }

            val now = status(ctx)
            if (enabled && !now.enabled) {
                DdLog.i(ctx, "introskip", "verify ${now.zeroed}/${now.matched}: ${now.details}")
                return "写入后检查失败：${now.zeroed}/${now.matched} 已替换。${now.details}"
            }
            if (!enabled && now.enabled) {
                DdLog.i(ctx, "introskip", "verify restore: ${now.details}")
                return "恢复后检查失败，片头视频仍然是占位文件"
            }

            // commit 而不是 apply：刚关掉就点「启动」时，applySavedPreference 不能读到旧值
            prefs(ctx).edit().putBoolean(PREF_KEY, enabled).commit()
            cleanupLegacy(ctx)
            DdLog.i(
                ctx,
                "introskip",
                "${if (enabled) "enabled" else "disabled"} video ${now.zeroed}/${now.matched}",
            )
            null
        }.getOrElse {
            DdLog.e(ctx, "introskip", it)
            "修改开屏设置失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /**
     * 写完立刻读回来比对；只有真的落盘成功才算数。
     *
     * 魅族这类机器的 /sdcard 是 FUSE，`close()` 偶尔会抛 EIO/ENOSPC，但数据其实
     * 已经写进去了 —— 所以以「读回来的内容」为准，close 的异常只记日志。
     */
    private fun writePlaceholder(ctx: Context, file: File, bytes: ByteArray, mark: Mark): Boolean {
        val err = runCatching {
            file.outputStream().use { it.write(bytes); it.flush() }
        }.exceptionOrNull()

        if (isPlaceholder(file, mark)) {
            if (err != null) {
                DdLog.i(
                    ctx, "introskip",
                    "close ${file.name} 报错但内容已写入：" +
                        "${err.javaClass.simpleName}: ${err.message}",
                )
            }
            return true
        }

        val got = runCatching { digestOf(file.readBytes()).toHex() }.getOrNull()
        DdLog.i(
            ctx, "introskip",
            "verify ${file.name} 失败 size=${if (file.isFile) file.length() else -1}" +
                " want=${mark.digest.toHex()} got=$got" +
                (err?.let { " err=${it.javaClass.simpleName}: ${it.message}" } ?: ""),
        )
        return false
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 数据包重装后视频会恢复原版，开游戏前按已保存的选择再替换一次。
     */
    fun applySavedPreference(ctx: Context): String? {
        if (!prefs(ctx).getBoolean(PREF_KEY, false)) return null
        val now = status(ctx)
        if (!now.available || now.enabled) return null
        return setEnabled(ctx, true)
    }

    private fun move(from: File, to: File): Boolean {
        if (!from.isFile) return false
        to.parentFile?.mkdirs()
        if (to.exists()) to.delete()
        if (from.renameTo(to)) return true
        return runCatching {
            from.copyTo(to, overwrite = true)
            from.delete()
        }.getOrDefault(false)
    }

    /** 旧版本留下的时长备份已经不用了。 */
    private fun cleanupLegacy(ctx: Context) {
        runCatching {
            File(ctx.filesDir, "introskip-backup").deleteRecursively()
        }
    }
}
