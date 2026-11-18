package moe.ddlauncher

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 照搬 Apricityx/WorkshopAndroidDownloader 里那套「Watt Toolkit 加速直连」。
 *
 * 原理（跟上游 `ExperimentalWorkshopDirectAccess*` 一致）：
 *  1. 问 `api.steampp.net/accelerator/projectgroups` 要当前启用的加速项目，
 *     每个项目给出「匹配域名 → 转发节点域名 / 是否需要忽略证书 / 假 SNI」。
 *  2. 命中名单的请求（steamcommunity / api.steampowered / 图片 CDN）改写成走转发节点：
 *     URL 换成假 Host（或伪域名）、查 DNS 时由 [WattDns] 指向转发节点、再加 `Host: 真实域名` 头。
 *  3. 转发节点返回 403/5xx 或连不上 → 自动换下一个节点；整条链路失败 → 原样回退直连。
 *
 * 覆盖范围要注意：**不包括工坊 chunk 用的 `cdn.steamcontent.com`**（Watt 没有这个项目），
 * 所以它加速的是搜索、详情、缩略图这类请求；mod 本体还是直连 CDN 下。
 */
object WattAccelerator {

    private const val API = "https://api.steampp.net/accelerator/projectgroups"
    private const val TAG = "watt"
    private const val TTL_MS = 30L * 60L * 1000L
    private const val COOLDOWN_MS = 30_000L
    private const val MAX_REDIRECTS = 10
    private val RETRYABLE_CODES = setOf(403, 404, 421, 502, 503, 504, 521, 522, 523, 524, 525)

    private data class Profile(
        val name: String,
        val hosts: Set<String>,
        val bootstrapTargets: List<String>,
        val bootstrapFakeName: String,
        val bootstrapIgnoreSsl: Boolean,
    )

    private val profiles = listOf(
        Profile(
            name = "steam-community",
            hosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
            bootstrapTargets = listOf("steamcommunity.rmbgame.net"),
            bootstrapFakeName = "steamstore-a.akamaihd.net",
            bootstrapIgnoreSsl = false,
        ),
        Profile(
            name = "steam-store",
            hosts = setOf(
                "api.steampowered.com",
                "store.steampowered.com",
                "help.steampowered.com",
                "login.steampowered.com",
                "checkout.steampowered.com",
            ),
            bootstrapTargets = listOf("steamstore.rmbgame.net"),
            bootstrapFakeName = "steamstore-a.akamaihd.net",
            bootstrapIgnoreSsl = false,
        ),
        Profile(
            name = "steam-image",
            hosts = setOf(
                "steamcdn-a.akamaihd.net",
                "steamuserimages-a.akamaihd.net",
                "cdn.akamai.steamstatic.com",
                "community.akamai.steamstatic.com",
                "avatars.akamai.steamstatic.com",
                "store.akamai.steamstatic.com",
                "avatars.fastly.steamstatic.com",
                "images.steamusercontent.com",
            ),
            bootstrapTargets = listOf("steamimage.rmbgame.net"),
            bootstrapFakeName = "",
            bootstrapIgnoreSsl = true,
        ),
    )

    private data class Route(
        val logicalHosts: Set<String>,
        val forwardTargets: List<String>,
        val ignoreSsl: Boolean,
        val fakeServerName: String,
    ) {
        private val normalizedFake: String? = fakeServerName.trim().lowercase()
            .takeIf { it.isNotEmpty() && it != "{origin}" && it != "@domain" }

        val usesOriginFakeName: Boolean = fakeServerName.trim() in setOf("{origin}", "@domain")

        val forwardHosts: List<String> = forwardTargets.mapNotNull { raw ->
            if (raw.contains("://")) raw.toHttpUrlOrNull()?.host?.lowercase()
            else raw.substringBefore('/').substringBefore(':').lowercase()
        }

        val networkHosts: Set<String> = buildSet {
            addAll(forwardHosts)
            normalizedFake?.let { add(it) }
            if (usesOriginFakeName) addAll(logicalHosts)
        }

        fun matches(host: String): Boolean = host.lowercase() in logicalHosts

        fun networkHostFor(logicalHost: String): String? = when {
            normalizedFake != null -> normalizedFake
            usesOriginFakeName -> logicalHost.lowercase()
            else -> null
        }

        fun buildForwardedUrl(url: HttpUrl): HttpUrl {
            val first = forwardTargets.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return url
            val target = networkHostFor(url.host) ?: first
            return if (first.contains("://")) {
                val base = first.toHttpUrlOrNull() ?: return url
                base.newBuilder()
                    .host(target)
                    .encodedPath(url.encodedPath)
                    .encodedQuery(url.encodedQuery)
                    .build()
            } else {
                url.newBuilder().host(target).build()
            }
        }

        fun shouldBypassHostname(host: String): Boolean =
            ignoreSsl && host.lowercase() in networkHosts

        fun candidates(): List<Route> =
            if (forwardTargets.size < 2) listOf(this)
            else forwardTargets.indices.map { copy(forwardTargets = forwardTargets.drop(it)) }
    }

    private data class CacheEntry(val route: Route, val atMs: Long, val cooldownUntil: Long = 0L)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val bypassHosts = ConcurrentHashMap.newKeySet<String>()
    private val dns = WattDns()
    private var context: Context? = null

    private fun log(message: String) {
        context?.let { DdLog.i(it, TAG, message) }
    }

    private fun enabled(): Boolean {
        val ctx = context ?: return false
        return ctx.getSharedPreferences("ddlauncher", Context.MODE_PRIVATE)
            .getBoolean("watt_accel", true)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("ddlauncher", Context.MODE_PRIVATE).edit()
            .putBoolean("watt_accel", on).apply()
        if (!on) {
            cache.clear()
            bypassHosts.clear()
        }
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("ddlauncher", Context.MODE_PRIVATE).getBoolean("watt_accel", true)

    /** 给任意客户端挂上加速拦截器；没初始化过就原样返回 */
    fun decorate(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val ctx = context ?: return builder
        if (!isEnabled(ctx)) return builder
        return builder.addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host.lowercase()
            if (!enabled() || profiles.none { host in it.hosts }) return@Interceptor chain.proceed(request)
            val route = resolve(host) ?: return@Interceptor chain.proceed(request)
            if (route.forwardTargets.isEmpty()) return@Interceptor chain.proceed(request)
            try {
                val response = executeWithRedirects(request, host, route)
                cache.computeIfPresent(profileOf(host)?.name ?: "") { _, e -> e.copy(cooldownUntil = 0L) }
                response
            } catch (e: IOException) {
                log("加速 ${request.url} 失败，回退直连：${e.message}")
                profileOf(host)?.let { p ->
                    cache[p.name] = (cache[p.name] ?: CacheEntry(route, 0L)).copy(
                        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS,
                    )
                }
                chain.proceed(request)
            }
        })
    }

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    // ---------------- 实际发请求 ----------------

    private fun executeWithRedirects(initial: Request, originalHost: String, route: Route): Response {
        var current = initial
        var hops = 0
        while (true) {
            val response = executeWithCandidates(current, route)
            val target = redirectTarget(response, current)
            if (target == null) {
                return response.newBuilder().request(current).build()
            }
            if (hops >= MAX_REDIRECTS) {
                response.close()
                throw IOException("加速跳转次数过多：$MAX_REDIRECTS")
            }
            // 301/302/303 之后按规范换成 GET，别把 POST 体带到新地址去
            val code = response.code
            response.close()
            val method = current.method
            val keepBody = code == 307 || code == 308
            current = if (!keepBody && method != "GET" && method != "HEAD") {
                current.newBuilder()
                    .url(target)
                    .get()
                    .removeHeader("Content-Type")
                    .removeHeader("Content-Length")
                    .build()
            } else {
                current.newBuilder().url(target).build()
            }
            hops++
        }
    }

    private fun executeWithCandidates(request: Request, route: Route): Response {
        var lastError: IOException? = null
        for (candidate in route.candidates()) {
            try {
                dns.register(candidate)
                candidate.networkHosts
                    .filter { candidate.shouldBypassHostname(it) }
                    .forEach { bypassHosts.add(it) }
                val network = candidate.buildForwardedUrl(request.url)
                val forwarded = request.newBuilder()
                    .url(network)
                    .header("Host", request.url.host)
                    .build()
                val response = directClient.newCall(forwarded).execute()
                if (candidate.matches(request.url.host) && response.code in RETRYABLE_CODES) {
                    val code = response.code
                    response.close()
                    lastError = IOException("转发节点返回 $code（${network.host}）")
                    log("节点 ${network.host} 返回 $code，换下一个")
                    continue
                }
                return response
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("没有可用的加速节点")
    }

    private fun redirectTarget(response: Response, request: Request): HttpUrl? {
        if (response.code !in setOf(300, 301, 302, 303, 307, 308)) return null
        val location = response.header("Location")?.trim().orEmpty()
        if (location.isEmpty()) return null
        return request.url.resolve(location)
    }

    // ---------------- 路由 ----------------

    private fun profileOf(host: String): Profile? =
        profiles.firstOrNull { host.lowercase() in it.hosts }

    private fun resolve(host: String): Route? {
        val profile = profileOf(host) ?: return null
        val now = System.currentTimeMillis()
        val entry = cache[profile.name]
        if (entry != null) {
            if (now < entry.cooldownUntil) return null
            if (now - entry.atMs < TTL_MS && entry.route.matches(host)) return entry.route
        }
        val fetched = fetchRoute(profile)
        if (fetched != null) {
            cache[profile.name] = CacheEntry(fetched, now)
            log(
                "加速路线 ${profile.name} 节点=${fetched.forwardTargets.joinToString(";")} " +
                    "假SNI=${fetched.fakeServerName.ifBlank { "-" }} 忽略证书=${fetched.ignoreSsl}",
            )
            return fetched
        }
        val stale = entry?.route?.takeIf { it.matches(host) }
        if (stale != null) {
            cache[profile.name] = entry.copy(atMs = now)
            return stale
        }
        val bootstrap = bootstrapRoute(profile)
        if (bootstrap != null) {
            log("加速配置取不到，用内置节点 ${profile.name}")
            cache[profile.name] = CacheEntry(bootstrap, now)
            return bootstrap
        }
        return null
    }

    private fun bootstrapRoute(profile: Profile): Route? =
        profile.bootstrapTargets.takeIf { it.isNotEmpty() }?.let { targets ->
            Route(
                logicalHosts = profile.hosts,
                forwardTargets = targets,
                ignoreSsl = profile.bootstrapIgnoreSsl,
                fakeServerName = profile.bootstrapFakeName,
            )
        }

    private data class Project(
        val name: String,
        val patterns: List<String>,
        val targets: List<String>,
        val ignoreSsl: Boolean,
        val fakeServerName: String,
    )

    private fun fetchRoute(profile: Profile): Route? {
        val projects = fetchProjects() ?: return null
        val matched = projects.firstOrNull { project ->
            project.patterns.any { pattern -> profile.hosts.any { hostMatches(pattern, it) } }
        } ?: run {
            log("Watt 没有覆盖 ${profile.hosts.joinToString()} 的项目")
            return null
        }
        return Route(
            logicalHosts = profile.hosts,
            forwardTargets = matched.targets,
            ignoreSsl = matched.ignoreSsl,
            fakeServerName = matched.fakeServerName,
        )
    }

    private fun fetchProjects(): List<Project>? = runCatching {
        val jsonType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(API)
            .post("{}".toRequestBody(jsonType))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", "DarkestLauncher/1.2")
            .build()
        routeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("配置接口 HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val out = ArrayList<Project>()
            for (key in root.keys()) {
                root.optJSONArray(key)?.let { collectProjects(it, out) }
            }
            out
        }
    }.onFailure { log("取加速配置失败：${it.javaClass.simpleName}:${it.message}") }.getOrNull()

    private fun collectProjects(array: JSONArray, out: MutableList<Project>) {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val checked = o.optBoolean("Checked", false)
            val proxyType = o.optInt("ProxyType", 0)
            val targets = o.optString("ForwardDomainNames")
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (checked && proxyType == 0 && targets.isNotEmpty()) {
                out.add(
                    Project(
                        name = o.optString("Name"),
                        patterns = o.optString("MatchDomainNames")
                            .split(';')
                            .map { it.trim().lowercase() }
                            .filter { it.isNotEmpty() },
                        targets = targets,
                        ignoreSsl = o.optBoolean("IgnoreSSLCertVerification", false),
                        fakeServerName = o.optString("FakeServerName").trim(),
                    )
                )
            }
            o.optJSONArray("Items")?.let { collectProjects(it, out) }
        }
    }

    /** 名单里可能是域名、通配符，也可能带 scheme/path（子项目那种），统一成主机模式再比 */
    private fun hostMatches(pattern: String, host: String): Boolean {
        var p = pattern.substringBefore('#').substringBefore('?')
        if (p.contains("://")) p = p.substringAfter("://").substringBefore('/')
        else if (p.contains('/')) p = p.substringBefore('/')
        p = p.trim().lowercase()
        if (p.isEmpty()) return false
        if (p == host.lowercase()) return true
        if (p.startsWith("*.")) return host.lowercase().endsWith(p.substring(1))
        if (p.startsWith("*")) return host.lowercase().endsWith(p.substring(1))
        return false
    }

    // ---------------- 客户端 / DNS / 证书 ----------------

    private class WattDns : Dns {
        private val map = ConcurrentHashMap<String, String>()

        fun register(route: Route) {
            val target = route.forwardHosts.firstOrNull() ?: return
            val fake = route.fakeServerName.trim().lowercase()
                .takeIf { it.isNotEmpty() && it != "{origin}" && it != "@domain" }
            when {
                fake != null -> map[fake] = target
                route.usesOriginFakeName -> route.logicalHosts.forEach { map[it.lowercase()] = target }
            }
        }

        override fun lookup(hostname: String): List<InetAddress> {
            val target = map[hostname.lowercase()] ?: hostname
            return Dns.SYSTEM.lookup(target)
        }
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun trustAllContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }

    /** 只用来取配置：api.steampp.net 的中间证书是过期的，上游也是全信任 */
    private val routeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .sslSocketFactory(trustAllContext().socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** 发「改写后」请求的客户端：DNS 走自己的映射，跳转自己跟，证书按路线要求放宽 */
    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(dns)
            .sslSocketFactory(trustAllContext().socketFactory, trustAll)
            .hostnameVerifier { hostname, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session) ||
                    hostname.lowercase() in bypassHosts
            }
            .build()
    }

    /** 探测节点是否活着（给日志/自检用） */
    fun probe(target: String): Boolean = runCatching {
        val url = target.toHttpUrlOrNull() ?: "https://$target".toHttpUrlOrNull()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(url!!.host, url.port), 1200)
        }
        true
    }.getOrDefault(false)
}
