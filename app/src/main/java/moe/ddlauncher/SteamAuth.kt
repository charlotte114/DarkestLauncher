package moe.ddlauncher

import android.content.Context
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamCredentialAuthSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamGuardChallenge
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType
import top.apricityx.workshop.steam.protocol.SteamWebAccessTokens

/**
 * Steam 账号：密码登录 → 拿 refresh token → 只保存 token，密码不落盘。
 *
 * 登录走 Steam 官方的 CM 协议（和 Steam 客户端同一套）：
 *  1. Authentication.GetPasswordRSAPublicKey  拿 RSA 公钥
 *  2. 密码用公钥加密后提交（BeginAuthSessionViaCredentials）
 *  3. 需要 Guard 码时提交 UpdateAuthSessionWithSteamGuardCode
 *  4. 轮询 PollAuthSessionStatus，拿到 refresh token
 *
 * refresh token 存在应用私有目录的 SharedPreferences 里（别的 App 读不到），
 * 可以随时在 Steam 设置 → 安全 → 已授权设备 里撤销。
 */
object SteamAuth {

    data class Account(
        val accountName: String,
        val steamId: Long,
        val refreshToken: String,
        val displayName: String = "",
    ) {
        fun toSession(machineName: String) = SteamAccountSession(
            accountName = accountName,
            steamId = steamId,
            refreshToken = refreshToken,
            shouldRememberPassword = true,
            machineName = machineName,
        )

        /** SteamID64 → 32 位账号 ID，用于拼接个人主页链接 */
        val accountId: Long get() = steamId - 76561197960265728L
    }

    private const val PREF = "steam_account"
    private const val KEY_NAME = "account_name"
    private const val KEY_ID = "steam_id"
    private const val KEY_TOKEN = "refresh_token"
    private const val KEY_DISPLAY = "display_name"
    private const val KEY_AT = "access_token"
    private const val KEY_AT_AT = "access_token_at"

    fun load(context: Context): Account? {
        val p = context.getSharedPreferences(PREF, 0)
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        val id = p.getLong(KEY_ID, 0L)
        if (token.isBlank() || id == 0L) return null
        return Account(
            accountName = p.getString(KEY_NAME, "").orEmpty(),
            steamId = id,
            refreshToken = token,
            displayName = p.getString(KEY_DISPLAY, "").orEmpty(),
        )
    }

    fun save(context: Context, account: Account) {
        context.getSharedPreferences(PREF, 0).edit()
            .putString(KEY_NAME, account.accountName)
            .putLong(KEY_ID, account.steamId)
            .putString(KEY_TOKEN, account.refreshToken)
            .putString(KEY_DISPLAY, account.displayName)
            .remove(KEY_AT)
            .apply()
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREF, 0).edit().clear().apply()
    }

    /** access_token 走 CM 换取，比较慢，缓存 20 分钟 */
    fun cachedAccessToken(context: Context): String? {
        val p = context.getSharedPreferences(PREF, 0)
        val token = p.getString(KEY_AT, "").orEmpty()
        val at = p.getLong(KEY_AT_AT, 0L)
        if (token.isBlank()) return null
        if (System.currentTimeMillis() - at > 20 * 60_000L) return null
        return token
    }

    fun putAccessToken(context: Context, token: String) {
        context.getSharedPreferences(PREF, 0).edit()
            .putString(KEY_AT, token)
            .putLong(KEY_AT_AT, System.currentTimeMillis())
            .apply()
    }

    fun client(http: OkHttpClient): SteamAuthenticationClient =
        SteamAuthenticationClient(SteamDirectoryClient(http)) { OkHttpSteamCmSession(http) }

    /** 登录会话：一次密码登录要跨「提交密码 → 等 Guard 码 → 轮询」三步，中间要保持 CM 连接 */
    class Pending(val session: SteamCredentialAuthSession) {
        val guardChallenges: List<SteamGuardChallenge> = session.challenges
        val needsCode: Boolean = guardChallenges.any {
            it.type == SteamGuardChallengeType.DeviceCode || it.type == SteamGuardChallengeType.EmailCode
        }
        val preferredType: SteamGuardChallengeType
            get() = guardChallenges.firstOrNull {
                it.type == SteamGuardChallengeType.DeviceCode || it.type == SteamGuardChallengeType.EmailCode
            }?.type ?: SteamGuardChallengeType.EmailCode

        fun close() = runCatching { session.close() }
    }
}
