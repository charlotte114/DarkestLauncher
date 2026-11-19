package moe.ddlauncher

import android.os.Environment
import java.io.File

/**
 * 这个 APK 是 Switch 版 romfs + 自制移植层（ru.darkest.port）。
 * 实测出来的关键路径全部集中在这里。
 */
object Game {
    const val PKG = "com.darkestdungeon.launcher"
    const val LAUNCH_ACTIVITY = "ru.darkest.port.NxActivity"

    /** mod 目录：公开可写，第三方 App 拿到「所有文件访问」即可读写 */
    fun modsDir(): File =
        File(Environment.getExternalStorageDirectory(), "Android/media/$PKG/mods")

    /** 移植层的转换指纹：每个已转换的 mod 在这里留一个文件 */
    fun switchMarks(): File = File(modsDir(), ".port-switch")

    /** 转换前原始文件的备份 */
    fun nxOriginals(): File = File(modsDir(), ".port-nx-original")

    /** 存档目录（应用私有区，第三方 App 读不到，仅用于展示路径） */
    fun saveDir(): File =
        File(Environment.getExternalStorageDirectory(), "Android/data/$PKG/files/nx/save")

    /** 游戏实际会扫描的游戏数据子目录，用于判断「这个文件夹像不像一个 mod」 */
    val DATA_DIRS = listOf(
        "heroes", "monsters", "dungeons", "campaign", "panels", "localization",
        "trinkets", "inventory", "scripts", "shared", "curios", "loot", "props",
        "effects", "fx", "modes", "raid", "audio", "video", "upgrades", "maps",
        "colours", "fe_flow", "fonts", "loading_screen", "game_over", "overlays",
        "scrolls", "shaders", "raid_results", "activity_log", "dlc", "cursors",
        "user_information", "pin"
    )
}

data class ModInfo(
    val dir: File,
    val title: String,
    val version: String,
    val workshopId: String,
    val language: String,
    val tags: List<String>,
    val description: String,
    val iconFile: File?,
    val enabled: Boolean,
    val prepared: Boolean,
    val fileCount: Int,
    val sizeBytes: Long,
    val modified: Long,
    val notes: List<String>,
    val customName: String = "",
) {
    val id: String get() = dir.name
    val originalName: String get() = title.trim().ifBlank { dir.name }
    val displayName: String get() = customName.trim().ifBlank { originalName }
    val hasCustomName: Boolean get() = customName.trim().isNotBlank() && customName.trim() != originalName
}

data class GameInfo(
    val installed: Boolean = false,
    val version: String? = null,
)

data class Conflict(
    val path: String,
    val mods: List<String>,
    val isData: Boolean,
)

data class ImportProgress(
    val total: Int,
    val done: Int,
    val current: String,
)

data class ImportOutcome(
    val name: String,
    val ok: Boolean,
    val detail: String,
)
