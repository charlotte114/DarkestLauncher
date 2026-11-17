package moe.ddlauncher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘调试日志：`/sdcard/Android/data/com.darkestdungeon/files/ddlauncher.log`
 *
 * 工坊下载失败的真正原因（每个 CDN 主机各自的报错）只在 DownloadEvent.LogAppended 里，
 * UI 只显示最后一行且失败后会清掉输出目录，所以必须同步写一份到外部目录，
 * 方便 adb pull 出来看。发布版不能只靠 logcat。
 */
object DdLog {

    private const val FILE_NAME = "ddlauncher.log"
    private const val MAX_BYTES = 512 * 1024L

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun i(ctx: Context, tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg\n"
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: return
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, FILE_NAME)
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(dir, "$FILE_NAME.old")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line, Charsets.UTF_8)
        }
    }

    /** 把异常链每一层都写出来，别只留最外层那句笼统消息 */
    @Synchronized
    fun e(ctx: Context, tag: String, t: Throwable) {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            sb.append(if (depth == 0) "" else "\n    caused by: ")
                .append(cur.javaClass.simpleName).append(": ").append(cur.message ?: "")
            cur = cur.cause
            depth++
        }
        runCatching { sb.append('\n').append(t.stackTraceToString().take(4000)) }
        i(ctx, tag, sb.toString())
    }

    fun path(ctx: Context): String =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FILE_NAME).absolutePath

    fun clear(ctx: Context) {
        runCatching { File(ctx.getExternalFilesDir(null) ?: return, FILE_NAME).delete() }
    }
}
