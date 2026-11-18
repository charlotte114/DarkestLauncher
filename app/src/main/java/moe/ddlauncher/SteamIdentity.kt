package moe.ddlauncher

import android.content.Context
import android.os.Build
import android.provider.Settings
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.DEFAULT_MACHINE_NAME
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.buildSteamMachineId
import java.util.UUID

/**
 * 照搬上游 WorkshopAndroidDownloader 的 `SteamClientIdentity`。
 *
 * CM 登录时要带一个 machineId。之前我们用的是库里写死的 demo id，
 * 每次工坊下载都像一台全新设备连上去，Steam 那头更容易重新分配 cell、复用不了会话；
 * 用「本机安装 id + 硬件指纹」算出来的稳定 id，连接和后续请求都更顺。
 */
class SteamIdentity(context: Context) {

    private val app = context.applicationContext

    val machineName: String = DEFAULT_MACHINE_NAME

    val machineId: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val installationId = installationId()
        val baseId = androidId() ?: installationId
        val hardware = listOf(
            field(Build.MANUFACTURER, "unknown-manufacturer"),
            field(Build.BRAND, "unknown-brand"),
            field(Build.MODEL, "unknown-model"),
            field(Build.DEVICE, "unknown-device"),
            field(Build.BOARD, "unknown-board"),
        ).joinToString("|")
        val storage = listOf(
            field(Build.FINGERPRINT, "unknown-fingerprint"),
            field(Build.PRODUCT, "unknown-product"),
            field(Build.HARDWARE, "unknown-hardware"),
            Build.SUPPORTED_ABIS.joinToString(",").ifBlank { "unknown-abi" },
        ).joinToString("|")
        buildSteamMachineId(
            machineGuidSource = "$baseId|${app.packageName}".toByteArray(Charsets.UTF_8),
            macAddressSource = "$baseId|$hardware".toByteArray(Charsets.UTF_8),
            diskIdSource = "$baseId|$storage".toByteArray(Charsets.UTF_8),
        )
    }

    fun newSession(client: OkHttpClient): OkHttpSteamCmSession = OkHttpSteamCmSession(
        client = client,
        machineName = machineName,
        machineId = machineId,
        diagnosticLogger = { DdLog.i(app, "cm", it) },
    )

    private fun androidId(): String? = runCatching {
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.takeUnless { it.isBlank() || it == "9774d56d682e549c" }
    }.getOrNull()

    private fun installationId(): String {
        val prefs = app.getSharedPreferences("steam_client_identity", Context.MODE_PRIVATE)
        prefs.getString("installation_id", null)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("installation_id", created).apply()
        return created
    }

    private fun field(value: String?, fallback: String): String =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) } ?: fallback

    companion object {
        /** 每个进程只建一次（machineId 计算有点重，而且没必要重复） */
        @Volatile
        private var cached: SteamIdentity? = null

        fun of(context: Context): SteamIdentity =
            cached ?: synchronized(this) {
                cached ?: SteamIdentity(context).also { cached = it }
            }
    }
}
