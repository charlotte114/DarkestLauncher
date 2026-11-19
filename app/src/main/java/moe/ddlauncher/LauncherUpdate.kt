package moe.ddlauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 启动器自身更新：版本信息来自 GitHub Releases，不依赖自建服务器。
 */
object LauncherUpdate {

    private const val API =
        "https://api.github.com/repos/charlotte114/DarkestLauncher/releases/latest"
    private const val USER_AGENT = "DarkestLauncher-Updater"

    data class Release(
        val version: String,
        val title: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
    )

    data class Status(
        val checking: Boolean = false,
        val downloading: Boolean = false,
        val progress: Int? = null,
        val release: Release? = null,
        val readyPath: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
    ) {
        val busy: Boolean get() = checking || downloading
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun latest(): Release {
        val request = Request.Builder()
            .url(API)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("GitHub 返回 HTTP ${response.code}")
            }
            val root = JSONObject(response.body?.string().orEmpty())
            val tag = root.optString("tag_name").ifBlank {
                root.optString("name")
            }.trim()
            if (tag.isBlank()) error("Release 没有版本号")

            var apk: JSONObject? = null
            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    if (!item.optString("name").endsWith(".apk", ignoreCase = true)) continue
                    if (apk == null || item.optLong("size") > apk.optLong("size")) apk = item
                }
            }
            val asset = apk ?: error("最新 Release 没有 APK 附件")
            val url = asset.optString("browser_download_url").trim()
            if (!url.startsWith("https://")) error("APK 下载地址无效")

            return Release(
                version = tag.removePrefix("v").removePrefix("V"),
                title = root.optString("name").ifBlank { tag },
                notes = root.optString("body"),
                pageUrl = root.optString("html_url"),
                apkUrl = url,
                apkName = asset.optString("name").ifBlank { "DarkestLauncher-$tag.apk" },
                apkSize = asset.optLong("size"),
            )
        }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = versionParts(candidate)
        val b = versionParts(current)
        if (a.isEmpty() || b.isEmpty()) return candidate.trim() != current.trim()
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    fun cachedFile(ctx: Context, release: Release): File =
        File(updateDir(ctx), safeName(release.apkName))

    fun download(ctx: Context, release: Release, onProgress: (Int) -> Unit): File {
        val target = cachedFile(ctx, release)
        val tmp = File(target.parentFile, "${target.name}.part")
        tmp.delete()

        try {
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("APK 下载失败 HTTP ${response.code}")
                val body = response.body ?: error("APK 下载响应为空")
                val total = body.contentLength()
                tmp.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100.0 / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }

        if (tmp.length() <= 0L) {
            tmp.delete()
            error("下载到的 APK 是空文件")
        }
        if (release.apkSize > 0 && tmp.length() != release.apkSize) {
            tmp.delete()
            error("APK 大小不一致，可能下载不完整")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        onProgress(100)
        return target
    }

    /** 返回 null 表示已经打开系统安装器；否则返回需要显示的提示。 */
    fun install(ctx: Context, file: File): String? {
        if (!file.isFile) return "更新包不存在，请重新下载"

        val info = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (info?.packageName != ctx.packageName) {
            return "下载的 APK 包名不匹配，已拒绝安装"
        }

        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${ctx.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(settings) }
                .onFailure { return "无法打开安装权限设置：${it.message}" }
            return "请先允许安装未知应用，返回后再点“安装更新”"
        }

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { return "无法打开系统安装器：${it.message}" }
        return null
    }

    private fun versionParts(raw: String): List<Int> =
        Regex("""\d+""").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun updateDir(ctx: Context): File =
        File(ctx.cacheDir, "updates").apply { mkdirs() }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("""[/\\:*?"<>|]"""), "_").trim()
        return cleaned.ifBlank { "DarkestLauncher-update.apk" }
    }
}
