package moe.ddlauncher

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 游戏内设置（`persist.options.json`）—— 逆向出来的真实结构（0.4.12 实测）：
 *
 * ```json
 * {
 *   "version": 1,
 *   "data": { "values": {
 *     "resolution": [1920, 1080],
 *     "music_volume": [100],
 *     "blur": [0],
 *     "subtitles": "low",
 *     "language": "default"
 *   } }
 * }
 * ```
 *
 * 依据：
 * - `files/nx/exefs/main`（18 MB 引擎本体）里就有 `persist.options.json` 这个字符串；
 * - 移植层 `libddnx.so` 是 `"%s/persist.options.json"`，还带 `nx_options_saved` 标记；
 * - 引擎启动时读它、退出时回写，移植层还会从里面读分辨率
 *   （`nx: resolution: the options not rewritten`、`the options' resolution is not two numbers`）。
 *
 * 所以启动器不 hook 游戏，只改这个 JSON，下次启动就生效。**只改用户动过的键**，
 * 其余原样写回（`version` 之类也不能丢）；每次写之前自动备份。
 */
object GameOptions {

    private const val DATA = "data"
    private const val VALUES = "values"

    enum class Kind { TOGGLE, VOLUME, INT, TEXT, RESOLUTION, COMPLEX }

    /** 键的中文名、分组、控件类型。没列到的键按原类型只读展示，不乱猜。 */
    private class Meta(
        val label: String,
        val group: String,
        val kind: Kind,
        val hint: String = "",
        /** TEXT 的可选值；有它就变成下拉选择，不让用户手打 */
        val choices: List<String> = emptyList(),
        /** 可选值的中文名，键是原始值 */
        val choiceLabels: Map<String, String> = emptyMap(),
        /** INT 的档位范围（步进器用） */
        val min: Int = 0,
        val max: Int = 0,
    )

    /** 声明顺序就是界面顺序 */
    private val META: Map<String, Meta> = linkedMapOf(
        "resolution" to Meta("分辨率", "画面", Kind.RESOLUTION, "降一档更省电；改错进不去就在启动器里还原备份"),
        "blur" to Meta("模糊效果", "画面", Kind.TOGGLE, "菜单背后的模糊，关掉省性能"),
        "gamma" to Meta("亮度 Gamma", "画面", Kind.INT, "游戏里的亮度档，默认 3", min = 0, max = 6),
        "framerate" to Meta("帧率档位", "画面", Kind.INT, "0 = 游戏默认档", min = 0, max = 2),

        "mute" to Meta("静音", "声音", Kind.TOGGLE, "1 = 静音"),
        "master_volume" to Meta("主音量", "声音", Kind.VOLUME),
        "music_volume" to Meta("音乐音量", "声音", Kind.VOLUME),
        "sfx_volume" to Meta("音效音量", "声音", Kind.VOLUME),
        "narration_volume" to Meta("旁白音量", "声音", Kind.VOLUME),
        "video_volume" to Meta("影片音量", "声音", Kind.VOLUME),

        "language" to Meta(
            "语言", "语言", Kind.TEXT,
            choices = listOf(
                "default", "schinese", "english", "brazilian", "czech", "french",
                "german", "italian", "koreana", "polish", "russian", "spanish",
            ),
            choiceLabels = mapOf(
                "default" to "跟随系统", "schinese" to "简体中文", "english" to "English",
                "brazilian" to "Português (Brasil)", "czech" to "Čeština", "french" to "Français",
                "german" to "Deutsch", "italian" to "Italiano", "koreana" to "한국어",
                "polish" to "Polski", "russian" to "Русский", "spanish" to "Español",
            ),
        ),
        "subtitles" to Meta(
            "字幕", "语言", Kind.TEXT, choices = listOf("low", "high"),
            choiceLabels = mapOf("low" to "低（只显示关键）", "high" to "高（全部）"),
        ),

        "tutorial" to Meta("新手提示", "玩法", Kind.TOGGLE, "1 = 显示"),
        "hold_required_in_dungeon" to Meta("地牢内需按住", "玩法", Kind.TOGGLE, "1 = 需要按住"),
        "left_stick_interact" to Meta("左摇杆交互", "玩法", Kind.TOGGLE, "1 = 开"),
        "combat_pivot_camera" to Meta("战斗时镜头转向", "玩法", Kind.TOGGLE, "1 = 开"),
        "extra_bark_time" to Meta("额外台词时间", "玩法", Kind.TOGGLE, "1 = 开"),
        "bark_dismissal" to Meta("台词可手动关闭", "玩法", Kind.TOGGLE, "1 = 可以"),
        "roster_sort_party_to_top" to Meta("队伍成员置顶", "玩法", Kind.TOGGLE, "1 = 开"),
        "right_handed" to Meta("右手模式", "玩法", Kind.TOGGLE, "1 = 开"),
        "map_follow_party" to Meta("地图跟随队伍", "玩法", Kind.TOGGLE, "1 = 开"),
    )

    data class Option(
        val key: String,
        val kind: Kind,
        val text: String,
        val label: String,
        val group: String,
        val hint: String = "",
        val choices: List<String> = emptyList(),
        val choiceLabels: Map<String, String> = emptyMap(),
        val min: Int = 0,
        val max: Int = 0,
    ) {
        val editable: Boolean get() = kind != Kind.COMPLEX

        /** 值 → 界面显示名（语言用 “简体中文” 这种） */
        fun labelOf(value: String): String = choiceLabels[value] ?: value
    }

    data class Loaded(
        val path: String,
        val raw: String,
        val modifiedAt: Long,
        val options: List<Option>,
        val error: String? = null,
    )

    fun file(): File = File(Game.saveDir(), "persist.options.json")

    fun backupDir(): File =
        File(Environment.getExternalStorageDirectory(), "Documents/DarkestSaves/options")

    fun load(ctx: Context): Loaded {
        val f = file()
        if (!f.isFile) {
            return Loaded(f.absolutePath, "", 0L, emptyList(), "还没生成过设置文件，进一次游戏就会有了")
        }
        val raw = runCatching { f.readText(Charsets.UTF_8) }
            .getOrElse { return Loaded(f.absolutePath, "", f.lastModified(), emptyList(), "读不到：${it.message}") }
        return runCatching {
            Loaded(f.absolutePath, raw, f.lastModified(), parse(raw))
        }.getOrElse {
            Loaded(f.absolutePath, raw, f.lastModified(), emptyList(), "解析失败：${it.message}")
        }
    }

    /** 真实结构在 `data.values`；万一被拍平，就退回顶层找。 */
    private fun valuesOf(root: JSONObject): JSONObject? {
        val data = root.optJSONObject(DATA)
        val v = data?.optJSONObject(VALUES)
        return v ?: root.optJSONObject(VALUES) ?: root
    }

    private fun parse(raw: String): List<Option> {
        val values = valuesOf(JSONObject(raw)) ?: return emptyList()
        val keys = values.keys().asSequence().toList()
        return keys.map { k ->
            val v = values.opt(k)
            val meta = META[k]
            val kind = meta?.kind ?: inferKind(v)
            Option(
                key = k,
                kind = kind,
                text = textOf(v, kind),
                label = meta?.label ?: k,
                group = meta?.group ?: "其他",
                hint = meta?.hint.orEmpty(),
                choices = meta?.choices.orEmpty(),
                choiceLabels = meta?.choiceLabels.orEmpty(),
                min = meta?.min ?: 0,
                max = meta?.max ?: 0,
            )
        }.sortedWith(compareBy({ groupRank(it.group) }, { orderOf(it.key) }))
    }

    private fun inferKind(v: Any?): Kind = when {
        v is JSONArray && v.length() == 2 && v.opt(0) is Number && v.opt(1) is Number -> Kind.RESOLUTION
        v is JSONArray && v.length() == 1 && v.opt(0) is Number -> Kind.INT
        v is Number -> Kind.INT
        v is String -> Kind.TEXT
        else -> Kind.COMPLEX
    }

    private fun textOf(v: Any?, kind: Kind): String = when (kind) {
        Kind.RESOLUTION -> {
            val a = v as? JSONArray
            if (a != null && a.length() >= 2) "${a.optInt(0)}x${a.optInt(1)}" else ""
        }
        Kind.TOGGLE, Kind.INT, Kind.VOLUME -> intOf(v).toString()
        Kind.TEXT -> (v as? String) ?: v?.toString().orEmpty()
        Kind.COMPLEX -> summary(v)
    }

    private fun intOf(v: Any?): Int = when (v) {
        is JSONArray -> v.optInt(0)
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun summary(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONArray -> "[${v.length()} 项]"
        is JSONObject -> "{${v.length()} 个键}"
        else -> v.toString()
    }

    /** 声明顺序 = 显示顺序；没声明的键排最后 */
    private fun orderOf(key: String) = META.keys.toList().indexOf(key).let { if (it < 0) 99 else it }

    private fun groupRank(g: String) = when (g) {
        "画面" -> 0
        "声音" -> 1
        "语言" -> 2
        "玩法" -> 3
        else -> 9
    }

    /** 只改用户动过的键，其余原样写回；写之前先备份。返回 null 表示成功。 */
    fun save(ctx: Context, edits: Map<String, String>): String? {
        val f = file()
        if (!f.isFile) return "设置文件不见了，先启动一次游戏"
        val root = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrElse {
            return "现有文件不是合法 JSON，先用「原始 JSON」修好再改：${it.message}"
        }
        val values = valuesOf(root) ?: return "文件结构不认识了（没有 values 段）"
        val changed = ArrayList<String>()

        for ((k, text) in edits) {
            if (!values.has(k)) continue
            val meta = META[k]
            val kind = meta?.kind ?: inferKind(values.opt(k))
            val newValue: Any? = when (kind) {
                Kind.TOGGLE -> if (text.trim() == "1") 1 else 0
                Kind.VOLUME -> text.trim().toIntOrNull()?.coerceIn(0, 100)
                    ?: return "「${meta?.label ?: k}」要填 0-100：$text"
                Kind.INT -> text.trim().toIntOrNull()
                    ?: return "「${meta?.label ?: k}」要填整数：$text"
                Kind.TEXT -> text
                Kind.RESOLUTION -> {
                    val nums = text.split('x', 'X', ',', '*', ' ')
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it > 0 }
                    if (nums.size < 2) return "分辨率要写成 宽x高，例如 1920x1080：$text"
                    JSONArray().put(nums[0].coerceIn(320, 7680)).put(nums[1].coerceIn(240, 4320))
                }
                Kind.COMPLEX -> continue
            }
            val wrapped = values.opt(k) is JSONArray
            val stored: Any = when {
                !wrapped -> newValue ?: continue
                newValue is JSONArray -> newValue
                else -> JSONArray().put(newValue)
            }
            if (values.opt(k)?.toString() != stored.toString()) {
                values.put(k, stored)
                changed.add(meta?.label ?: k)
            }
        }

        if (changed.isEmpty()) return null
        backup("before-edit")
        val err = writeRaw(f, root.toString(2))
        if (err == null) DdLog.i(ctx, "opts", "saved ${changed.size}: ${changed.joinToString(",")}")
        return err
    }

    fun saveRaw(ctx: Context, text: String): String? {
        val check = runCatching {
            val o = JSONObject(text)
            require(o.length() > 0) { "空的 JSON 对象，拒写（避免把设置清空）" }
            require(valuesOf(o) != null) { "缺少 data.values 段，游戏会读不出来" }
        }.exceptionOrNull()
        if (check != null) return "不是合法的设置 JSON：${check.message}"
        backup("before-raw-edit")
        val pretty = runCatching { JSONObject(text).toString(2) }.getOrDefault(text)
        val e = writeRaw(file(), pretty)
        if (e == null) DdLog.i(ctx, "opts", "saved raw ${text.length} chars")
        return e
    }

    /** 原子写：先写 .tmp 再 rename，中途断电不会留半个文件 */
    private fun writeRaw(f: File, text: String): String? = runCatching {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
        null as String?
    }.getOrElse { "写入失败：${it.message}" }

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun backup(reason: String): File? = runCatching {
        val f = file()
        if (!f.isFile) return null
        val dir = backupDir()
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "options_${stamp.format(Date())}_$reason.json")
        f.copyTo(target, overwrite = true)
        prune()
        target
    }.getOrNull()

    fun listBackups(): List<File> {
        val dir = backupDir()
        return (dir.listFiles() ?: return emptyList())
            .filter { it.isFile && it.name.startsWith("options_") && it.name.endsWith(".json") }
            .sortedByDescending { it.lastModified() }
    }

    fun restore(ctx: Context, backup: File): String? {
        if (!backup.isFile) return "备份文件不见了"
        if (runCatching { JSONObject(backup.readText(Charsets.UTF_8)) }.isFailure) return "这份备份损坏了"
        backup("before-restore")
        val err = writeRaw(file(), backup.readText(Charsets.UTF_8))
        if (err == null) DdLog.i(ctx, "opts", "restored from ${backup.name}")
        return err
    }

    private fun prune() {
        runCatching { listBackups().drop(12).forEach { it.delete() } }
    }

    /** 调试：把当前设置拷到公开目录，方便 adb pull */
    fun dumpToPublic(ctx: Context): String {
        val f = file()
        if (!f.isFile) return "没有 persist.options.json"
        val dir = backupDir()
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "current-options.json")
        runCatching { f.copyTo(target, overwrite = true) }
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
        DdLog.i(ctx, "opts", "dump -> ${target.absolutePath}\n$text")
        return target.absolutePath
    }
}
