package moe.ddlauncher

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * 游戏内字体大小。
 *
 * 移植层支持在 fonts.darkest 的 font / font_language_override 行追加
 * `.scale_to_native_res <倍率>`。只改这个配置就能让字形、行高和排版一起缩放，
 * 不改字体图集，也不会破坏简体中文 override。
 */
object GameFont {

    const val MIN = 85
    const val MAX = 125
    const val STEP = 5
    const val NORMAL = 100

    private const val PREFIX = "ddlauncher_"
    private const val BACKUP_SUFFIX = ".ddlauncher.orig"
    private const val CFG = "fonts.darkest"

    private val FONT_LINE = Regex("""^(?:font|font_language_override):.*\.file\s+"[^"]+"""")
    private val SCALE_PROP = Regex(
        """[ \t]*\.scale_to_native_res[ \t]+[+-]?(?:\d+(?:\.\d*)?|\.\d+)"""
    )
    private val SCALE_VALUE = Regex(
        """\.scale_to_native_res[ \t]+([+-]?(?:\d+(?:\.\d*)?|\.\d+))"""
    )
    private val OLD_PREFIX = Regex("""fonts/${PREFIX}(\d+)_""")

    data class Status(
        val available: Boolean = false,
        val percent: Int = NORMAL,
        val hasBackup: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null,
    )

    private fun fontsDir(ctx: Context): File = File(DataPack.dir(ctx), "romfs/fonts")
    private fun config(ctx: Context): File = File(fontsDir(ctx), CFG)
    private fun backup(ctx: Context): File = File(fontsDir(ctx), CFG + BACKUP_SUFFIX)

    fun status(ctx: Context): Status {
        val cfg = config(ctx)
        if (!cfg.isFile) return Status()
        val text = runCatching { cfg.readText(StandardCharsets.UTF_8) }.getOrNull().orEmpty()
        val scaled = SCALE_VALUE.find(text)
            ?.groupValues?.get(1)?.toFloatOrNull()
            ?.let { (it * 100).roundToInt() }
        val old = OLD_PREFIX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val pct = scaled ?: old ?: NORMAL
        return Status(
            available = true,
            percent = pct.coerceIn(MIN, MAX),
            hasBackup = backup(ctx).isFile,
        )
    }

    /** 返回 null 表示成功。 */
    fun apply(ctx: Context, requested: Int): String? {
        val pct = normalize(requested)
        return runCatching {
            val dir = fontsDir(ctx)
            val cfg = File(dir, CFG)
            if (!cfg.isFile) return "还没导入数据包，找不到 fonts.darkest"

            val bak = File(dir, CFG + BACKUP_SUFFIX)
            if (pct == NORMAL) return restore(ctx)

            if (!bak.isFile) {
                val now = cfg.readText(StandardCharsets.UTF_8)
                if (now.contains("fonts/$PREFIX")) {
                    return "字体已经被改过，但找不到原版备份；重新导入一次数据包后再试"
                }
                writeAtomic(bak, now.toByteArray(StandardCharsets.UTF_8))
            }

            val source = bak.readText(StandardCharsets.UTF_8)
            if (source.contains("fonts/$PREFIX")) {
                return "原版字体配置备份损坏，重新导入一次数据包"
            }

            val factor = factorText(pct)
            var count = 0
            val newline = if (source.contains("\r\n")) "\r\n" else "\n"
            val rewritten = source.lineSequence().joinToString(newline) { line ->
                if (!FONT_LINE.containsMatchIn(line)) {
                    line
                } else {
                    count++
                    "${SCALE_PROP.replace(line, "").trimEnd()}  .scale_to_native_res $factor"
                }
            }
            if (count == 0) return "fonts.darkest 里没读到可缩放的字体"

            writeAtomic(cfg, rewritten.toByteArray(StandardCharsets.UTF_8))
            val check = cfg.readText(StandardCharsets.UTF_8)
            if (!check.contains(".scale_to_native_res $factor")) {
                throw IllegalStateException("写入后校验失败，已保留原版备份")
            }
            cleanupGenerated(dir)
            DdLog.i(ctx, "font", "scale=$pct fonts=$count")
            null
        }.getOrElse {
            DdLog.e(ctx, "font", it)
            "设置字体失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun restore(ctx: Context): String? = runCatching {
        val dir = fontsDir(ctx)
        val cfg = File(dir, CFG)
        val bak = File(dir, CFG + BACKUP_SUFFIX)
        if (!bak.isFile) {
            if (!cfg.isFile) return "还没导入数据包，找不到 fonts.darkest"
            val text = cfg.readText(StandardCharsets.UTF_8)
            if (text.contains("fonts/$PREFIX")) {
                return "找不到原版备份；重新导入一次数据包就能恢复"
            }
            writeAtomic(cfg, SCALE_PROP.replace(text, "").toByteArray(StandardCharsets.UTF_8))
        } else {
            writeAtomic(cfg, bak.readBytes())
        }
        cleanupGenerated(dir)
        DdLog.i(ctx, "font", "restore original")
        null
    }.getOrElse {
        DdLog.e(ctx, "font", it)
        "恢复原版字体失败：${it.message ?: it.javaClass.simpleName}"
    }

    private fun cleanupGenerated(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(PREFIX)) runCatching { f.delete() }
        }
    }

    fun normalize(v: Int): Int {
        val clamped = v.coerceIn(MIN, MAX)
        return ((clamped + STEP / 2) / STEP * STEP).coerceIn(MIN, MAX)
    }

    private fun factorText(pct: Int): String {
        val fraction = pct % 100
        return "${pct / 100}.${fraction / 10}${fraction % 10}"
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.part")
        FileOutputStream(tmp).use { out ->
            BufferedOutputStream(out).use { it.write(bytes) }
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
