package moe.ddlauncher

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.apricityx.workshop.workshop.DownloadEvent
import top.apricityx.workshop.workshop.DownloadState
import top.apricityx.workshop.workshop.WorkshopDownloadEngine
import top.apricityx.workshop.workshop.WorkshopDownloadRequest
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 工坊对接：元数据走公开 Web API，内容走 steam-protocol 的 UGC/depot 链路。
 * 不需要 Steam 登录（公开条目匿名可下），失败时引擎会自动回退到公共 CDN。
 */
object WorkshopRepo {

    const val APP_ID: UInt = 262060u // Darkest Dungeon

    /** 每页拉多少条：CM 的 QueryFiles 给到 30 比较稳，再大响应会慢 */
    const val PAGE_SIZE = 30

    /** 给登录模块复用同一个 OkHttp（连接池共享） */
    val http: OkHttpClient get() = client

    /**
     * 注意 `.protocols(HTTP_1_1)`：Steam 的 CM WebSocket 端点靠 HTTP/1.1 的 Upgrade 握手，
     * OkHttp 默认会协商 h2，握手会一直挂着（表现为「登录中…」卡死）。
     * 上游的 newDefaultOkHttpClient() 就是靠 applySteamHttpCompatibility() 设这一项。
     */
    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 纯 Web API 用的客户端。这网络下 `api.steampowered.com` 解析出的 8 个 Akamai IP 里常有
     * 几个是 TLS 黑洞：默认 30s 连接超时会让一次查询卡上好几分钟。改成短超时 + 上层重试，
     * 单次失败几秒内就能换下一条。CM WebSocket 和 CDN 下载仍用 [client]（要长连接）。
     */
    private val apiClient = client.newBuilder()
        // 连接超时压到 4 秒很关键：一个域名解析出 8 个 IP，OkHttp 是挨个试的，
        // 每个黑洞 IP 卡 8 秒的话一次查询就废了；4 秒能让它在 callTimeout 内换掉好几个。
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class Item(
        val id: Long,
        val title: String,
        val sizeBytes: Long,
        val updated: Long,
        val subscriptions: Long,
        val previewUrl: String,
        val description: String,
        val creator: String,
        val tags: List<String> = emptyList(),
    )

    /** 目录条目转成详情能用的最小结构（省一次网络请求，直接用本地元数据下载） */
    fun itemOf(entry: WorkshopCatalog.Entry): Item = Item(
        id = entry.id,
        title = entry.title,
        sizeBytes = entry.size,
        updated = entry.updated,
        subscriptions = entry.subs.toLong(),
        previewUrl = entry.preview,
        description = entry.summary,
        creator = entry.author,
        tags = entry.tags,
    )

    /** 从任意文本里抠出工坊 ID：支持纯数字、?id=xxx、整条链接 */
    fun parseId(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        Regex("""id=(\d{5,20})""").find(trimmed)?.let { return it.groupValues[1].toLongOrNull() }
        return Regex("""\d{5,20}""").find(trimmed)?.value?.toLongOrNull()
    }

    suspend fun details(id: Long): Item = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("itemcount", "1")
            .add("publishedfileids[0]", id.toString())
            .build()
        val req = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
            .post(body)
            .build()
        apiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Steam 接口返回 ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val arr = JSONObject(text).getJSONObject("response").getJSONArray("publishedfiledetails")
            if (arr.length() == 0) throw IllegalStateException("查不到这个工坊 ID")
            val o = arr.getJSONObject(0)
            if (o.optInt("result", 0) != 1) throw IllegalStateException("Steam 说这个条目不可用（result=${o.optInt("result")}）")
            if (o.optInt("banned", 0) == 1) throw IllegalStateException("这个条目已被封禁")
            Item(
                id = id,
                title = o.optString("title", "未命名"),
                sizeBytes = o.optString("file_size", "0").toLongOrNull() ?: 0L,
                updated = o.optLong("time_updated", 0L),
                subscriptions = o.optLong("subscriptions", 0L),
                previewUrl = o.optString("preview_url", ""),
                description = o.optString("description", ""),
                creator = o.optString("creator", ""),
            )
        }
    }

    /**
     * 展开一个工坊合集。公开接口，匿名可用（国内可达）。
     * 返回条目 ID 的原始顺序（合集作者排的顺序，DD 虽然不讲究加载顺序，但保留它更贴近工坊）。
     */
    suspend fun collection(id: Long): List<Long> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("collectioncount", "1")
            .add("publishedfileids[0]", id.toString())
            .build()
        val req = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/")
            .post(body)
            .build()
        apiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Steam 接口返回 ${resp.code}")
            val root = JSONObject(resp.body?.string().orEmpty()).getJSONObject("response")
            val arr = root.optJSONArray("collectiondetails")
                ?: throw IllegalStateException("这个 ID 不是合集")
            if (arr.length() == 0) throw IllegalStateException("查不到这个合集")
            val c = arr.getJSONObject(0)
            if (c.optInt("result", 0) != 1) {
                throw IllegalStateException("这个 ID 不是公开合集（result=${c.optInt("result")}）")
            }
            val children = c.optJSONArray("children") ?: return@use emptyList<Long>()
            (0 until children.length()).mapNotNull { i ->
                val ch = children.getJSONObject(i)
                if (ch.optInt("filetype", 0) != 0) null else ch.optString("publishedfileid").toLongOrNull()
            }
        }
    }

    /** 批量元数据：一次最多 100 个 ID */
    suspend fun detailsBatch(ids: List<Long>): List<Item> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val builder = FormBody.Builder().add("itemcount", ids.size.toString())
        ids.forEachIndexed { i, id -> builder.add("publishedfileids[$i]", id.toString()) }
        val req = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
            .post(builder.build())
            .build()
        apiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Steam 接口返回 ${resp.code}")
            val arr = JSONObject(resp.body?.string().orEmpty())
                .getJSONObject("response")
                .optJSONArray("publishedfiledetails") ?: return@use emptyList<Item>()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (o.optInt("result", 0) != 1) return@mapNotNull null
                if (str(o, "consumer_app_id") != APP_ID.toString()) return@mapNotNull null
                Item(
                    id = o.optString("publishedfileid").toLongOrNull() ?: return@mapNotNull null,
                    title = o.optString("title", "未命名"),
                    sizeBytes = o.optString("file_size", "0").toLongOrNull() ?: 0L,
                    updated = o.optLong("time_updated", 0L),
                    subscriptions = o.optLong("subscriptions", 0L),
                    previewUrl = o.optString("preview_url", ""),
                    description = o.optString("short_description", "").ifBlank { o.optString("file_description", "") },
                    creator = o.optString("creator", ""),
                    tags = run {
                        val ta = o.optJSONArray("tags") ?: return@run emptyList<String>()
                        (0 until ta.length()).mapNotNull { j ->
                            ta.getJSONObject(j).optString("tag").takeIf { it.isNotBlank() }
                        }
                    },
                )
            }
        }
    }

    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.opt(key).toString()

    /**
     * 联网搜索。必须带 Steam Web API Key（`IPublishedFileService/QueryFiles`
     * 不接受匿名调用），所以这是个可选增强：没有 key 时用内置目录做本地搜索。
     */
    suspend fun queryFiles(
        key: String,
        text: String,
        page: Int,
        sort: WorkshopCatalog.Sort,
        pageSize: Int = 20,
    ): List<Item> = withContext(Dispatchers.IO) {
        val queryType = sort.queryTypeFor(text)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.steampowered.com")
            .addPathSegments("IPublishedFileService/QueryFiles/v1/")
            .addQueryParameter("key", key)
            .addQueryParameter("appid", APP_ID.toString())
            .addQueryParameter("query_type", queryType.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("numperpage", pageSize.toString())
            .addQueryParameter("search_text", text)
            .addQueryParameter("return_tags", "true")
            .addQueryParameter("return_previews", "true")
            .addQueryParameter("return_short_description", "true")
            .addQueryParameter("return_metadata", "true")
            .addQueryParameter("days", "0")
            .build()
        apiClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val text2 = resp.body?.string().orEmpty()
            if (resp.code == 403) throw IllegalStateException("API Key 无效或未授权（403）")
            if (!resp.isSuccessful) throw IllegalStateException("Steam 接口返回 ${resp.code}")
            val arr = JSONObject(text2).getJSONObject("response").optJSONArray("publishedfiledetails")
                ?: return@use emptyList<Item>()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (o.optInt("result", 0) != 1) return@mapNotNull null
                Item(
                    id = o.optString("publishedfileid").toLongOrNull() ?: return@mapNotNull null,
                    title = o.optString("title", "未命名"),
                    sizeBytes = o.optString("file_size", "0").toLongOrNull() ?: 0L,
                    updated = o.optLong("time_updated", 0L),
                    subscriptions = o.optLong("subscriptions", 0L),
                    previewUrl = o.optString("preview_url", ""),
                    description = o.optString("short_description", "")
                        .ifBlank { o.optString("file_description", "") },
                    creator = o.optString("creator", ""),
                    tags = run {
                        val ta = o.optJSONArray("tags") ?: return@run emptyList<String>()
                        (0 until ta.length()).mapNotNull { j ->
                            ta.getJSONObject(j).optString("tag").takeIf { it.isNotBlank() }
                        }
                    },
                )
            }
        }
    }

    // ---------------- 带 Steam 账号 token 的接口 ----------------
    //
    // 登录后拿到 access_token，可以用它替代 API key 调 IPublishedFileService。
    // 这三件事都不需要 key：搜工坊、读我的订阅、订阅/取消订阅。

    private fun apiUrl(vararg segments: String, params: Map<String, String>): okhttp3.HttpUrl {
        val b = okhttp3.HttpUrl.Builder().scheme("https").host("api.steampowered.com")
        segments.forEach { b.addPathSegment(it) }
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun parseItemArray(arr: org.json.JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optInt("result", 1) != 1) return@mapNotNull null
            Item(
                id = o.optString("publishedfileid").toLongOrNull() ?: return@mapNotNull null,
                title = o.optString("title", "未命名"),
                sizeBytes = o.optString("file_size", "0").toLongOrNull() ?: 0L,
                updated = o.optLong("time_updated", 0L),
                subscriptions = o.optLong("subscriptions", 0L),
                previewUrl = o.optString("preview_url", ""),
                description = o.optString("short_description", "")
                    .ifBlank { o.optString("file_description", "") },
                creator = o.optString("creator", ""),
                tags = run {
                    val ta = o.optJSONArray("tags") ?: return@run emptyList<String>()
                    (0 until ta.length()).mapNotNull { j ->
                        ta.getJSONObject(j).optString("tag").takeIf(String::isNotBlank)
                    }
                },
            )
        }
    }

    /** 用登录态搜索整个工坊（等价于 QueryFiles，但不需要 API key） */
    suspend fun searchWithToken(
        accessToken: String,
        text: String,
        page: Int,
        sort: WorkshopCatalog.Sort,
        pageSize: Int = 20,
    ): List<Item> = withContext(Dispatchers.IO) {
        val queryType = sort.queryTypeFor(text)
        val url = apiUrl(
            "IPublishedFileService", "QueryFiles", "v1",
            params = mapOf(
                "access_token" to accessToken,
                "appid" to APP_ID.toString(),
                "query_type" to queryType.toString(),
                "page" to page.toString(),
                "numperpage" to pageSize.toString(),
                "search_text" to text,
                "return_tags" to "true",
                "return_previews" to "true",
                "return_short_description" to "true",
                "return_metadata" to "true",
            ),
        )
        apiClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw IllegalStateException("登录已失效，请重新登录 Steam")
            if (!resp.isSuccessful) throw IllegalStateException("Steam 接口返回 ${resp.code}")
            parseItemArray(
                JSONObject(body).optJSONObject("response")?.optJSONArray("publishedfiledetails")
            )
        }
    }

    /**
     * 用登录态走 CM 协议搜工坊（`PublishedFile.QueryFiles#1`）。
     * 比 Web API 慢（每次要连一次 CM），但只依赖 refresh token，不赌 access_token 的权限。
     * Web API 那条被拒时用它兜底。
     */
    suspend fun searchViaCm(
        account: top.apricityx.workshop.steam.protocol.SteamAccountSession,
        text: String,
        page: Int,
        sort: WorkshopCatalog.Sort,
        queryTypeOverride: Int? = null,
        days: Int = 0,
    ): SubscriptionPage = withContext(Dispatchers.IO) {
        val queryType = sort.queryTypeFor(text)
        val cmClient = top.apricityx.workshop.steam.protocol.SteamPublishedFileClient(
            top.apricityx.workshop.steam.protocol.SteamDirectoryClient(client),
        ) { top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession(client) }
        val result = cmClient.queryFiles(
            account = account,
            query = top.apricityx.workshop.steam.protocol.SteamPublishedFileQuery(
                appId = APP_ID,
                searchText = text,
                page = page,
                pageSize = PAGE_SIZE,
                queryType = queryTypeOverride ?: queryType,
                days = days,
                // 6 = schinese，让工坊把标题/简介按简中给你（作者没写中文就还是原文）
                language = top.apricityx.workshop.steam.protocol.STEAM_LANGUAGE_SIMPLIFIED_CHINESE,
            ),
        )
        val items = result.items.map { it ->
            Item(
                id = it.publishedFileId.toLong(),
                title = it.title,
                sizeBytes = it.fileSizeBytes,
                updated = it.timeUpdatedEpochSeconds,
                subscriptions = it.subscriptions.toLong(),
                previewUrl = it.previewUrl,
                description = it.description,
                creator = it.creatorSteamId.toString(),
                tags = emptyList(),
            )
        }
        SubscriptionPage(items, if (result.total > 0) result.total else items.size)
    }

    data class SubscriptionPage(val items: List<Item>, val total: Int)

    /**
     * 走 CM 协议读「我的订阅」（`PublishedFile.GetUserFiles#1`）。
     *
     * 为什么不用 Web API：`IPublishedFileService/GetUserFiles` 只认 API key，
     * 登录拿到的 access_token 会被 401 拒掉（实测）。CM 这条路只需要 refresh token。
     */
    suspend fun mySubscriptionsViaCm(
        account: top.apricityx.workshop.steam.protocol.SteamAccountSession,
        page: Int,
        pageSize: Int = 100,
    ): SubscriptionPage = withContext(Dispatchers.IO) {
        val cmServers = top.apricityx.workshop.steam.protocol.SteamDirectoryClient(client).loadServers()
        top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession(client).use { session ->
            session.connectWithRefreshToken(cmServers, account)
            val resp = session.callServiceMethod(
                methodName = "PublishedFile.GetUserFiles#1",
                request = top.apricityx.workshop.steam.proto.CPublishedFile_GetUserFiles_Request.newBuilder()
                    .setSteamid(account.steamId)
                    .setAppid(APP_ID.toInt())
                    .setPage(page)
                    .setNumperpage(pageSize)
                    .setType("mysubscriptions")
                    .setSortmethod("lastupdated")
                    .setReturnTags(true)
                    .setReturnPreviews(true)
                    .setReturnShortDescription(true)
                    .build(),
                parser = top.apricityx.workshop.steam.proto.CPublishedFile_GetUserFiles_Response.parser(),
            )
            val items = resp.publishedfiledetailsList.map { d ->
                Item(
                    id = d.publishedfileid,
                    title = d.title.ifBlank { "未命名" },
                    sizeBytes = d.fileSize,
                    updated = d.timeUpdated.toLong(),
                    subscriptions = d.subscriptions.toLong(),
                    previewUrl = d.previewUrl,
                    description = d.shortDescription.ifBlank { d.fileDescription },
                    creator = d.creator.toString(),
                    tags = emptyList(),
                )
            }
            SubscriptionPage(items, resp.total)
        }
    }

    /** 走 CM 协议订阅 / 取消订阅 */
    suspend fun setSubscribedViaCm(
        account: top.apricityx.workshop.steam.protocol.SteamAccountSession,
        id: Long,
        subscribe: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val cmServers = top.apricityx.workshop.steam.protocol.SteamDirectoryClient(client).loadServers()
        top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession(client).use { session ->
            session.connectWithRefreshToken(cmServers, account)
            if (subscribe) {
                session.callServiceMethod(
                    methodName = "PublishedFile.Subscribe#1",
                    request = top.apricityx.workshop.steam.proto.CPublishedFile_Subscribe_Request.newBuilder()
                        .setPublishedfileid(id)
                        .setAppid(APP_ID.toInt())
                        .setNotifyClient(true)
                        .build(),
                    parser = top.apricityx.workshop.steam.proto.CPublishedFile_Subscribe_Response.parser(),
                )
            } else {
                session.callServiceMethod(
                    methodName = "PublishedFile.Unsubscribe#1",
                    request = top.apricityx.workshop.steam.proto.CPublishedFile_Unsubscribe_Request.newBuilder()
                        .setPublishedfileid(id)
                        .setAppid(APP_ID.toInt())
                        .setNotifyClient(true)
                        .build(),
                    parser = top.apricityx.workshop.steam.proto.CPublishedFile_Unsubscribe_Response.parser(),
                )
            }
        }
        true
    }
    /** 我订阅的 mod。type=mysubscriptions，必须带身份。 */
    suspend fun mySubscriptions(
        accessToken: String,
        steamId: Long,
        page: Int,
        pageSize: Int = 100,
    ): SubscriptionPage = withContext(Dispatchers.IO) {
        val url = apiUrl(
            "IPublishedFileService", "GetUserFiles", "v1",
            params = mapOf(
                "access_token" to accessToken,
                "steamid" to steamId.toString(),
                "appid" to APP_ID.toString(),
                "type" to "mysubscriptions",
                "page" to page.toString(),
                "numperpage" to pageSize.toString(),
                "sortmethod" to "lastupdated",
                "return_details" to "true",
                "return_short_description" to "true",
                "return_tags" to "true",
                "return_previews" to "true",
            ),
        )
        apiClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw IllegalStateException("登录已失效，请重新登录 Steam")
            if (!resp.isSuccessful) throw IllegalStateException("Steam 接口返回 ${resp.code}")
            val root = JSONObject(body).optJSONObject("response")
                ?: return@withContext SubscriptionPage(emptyList(), 0)
            SubscriptionPage(
                items = parseItemArray(root.optJSONArray("publishedfiledetails")),
                total = root.optInt("total", 0),
            )
        }
    }

    /** 真的去 Steam 订阅（订阅后 PC 端也能看到） */
    suspend fun setSubscribed(accessToken: String, id: Long, subscribed: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val method = if (subscribed) "Subscribe" else "Unsubscribe"
            val body = FormBody.Builder()
                .add("access_token", accessToken)
                .add("publishedfileid", id.toString())
                .build()
            val url = apiUrl("IPublishedFileService", method, "v1", params = emptyMap())
            apiClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                if (resp.code == 401) throw IllegalStateException("登录已失效，请重新登录 Steam")
                if (!resp.isSuccessful) throw IllegalStateException("Steam 接口返回 ${resp.code}")
                // 成功时 response 里是空的 result 对象，失败会带 eresult
                val text = resp.body?.string().orEmpty()
                val err = runCatching {
                    JSONObject(text).optJSONObject("response")?.optJSONObject("result")
                        ?.optInt("eresult", 1)
                }.getOrNull()
                err == null || err == 1
            }
        }

    /**
     * 下载一个工坊条目。
     *
     * 跟上游两个项目对齐的三点（原来是全默认值，等于白扔）：
     *  - [account] 非空就用登录态连 CM（`connectWithRefreshToken`）。匿名连不上也没关系，
     *    但「仅好友/需拥有游戏」的条目只有带身份才拿得到授权；这是 Sts2MobileLauncher
     *    的 `createAccountSession` 那套。
     *  - [maxConcurrentChunks] 并发抓块的条数，mod 大时调高明显快（同上项目里可从设置改）。
     *  - `publishedFileLanguage = "schinese"`：元数据里的标题/简介优先给中文。
     */
    fun download(
        item: Item,
        outputDir: File,
        account: top.apricityx.workshop.steam.protocol.SteamAccountSession? = null,
        maxConcurrentChunks: Int = 4,
    ): Flow<DownloadEvent> {
        val engine = WorkshopDownloadEngine.createDefault(
            client = client,
            sessionConnector = { session, servers ->
                val canAuth = account != null && account.steamId > 0L && !account.refreshToken.isNullOrBlank()
                if (canAuth) {
                    runCatching { session.connectWithRefreshToken(servers, account!!) }
                        .getOrElse { session.connectAnonymous(servers) }
                } else {
                    session.connectAnonymous(servers)
                }
            },
            maxConcurrentChunks = maxConcurrentChunks.coerceIn(1, 12),
            allowPublicCdnFallbackOnSessionFailure = true,
            publishedFileLanguage = "schinese",
        )
        return engine.download(
            WorkshopDownloadRequest(
                appId = APP_ID,
                publishedFileId = item.id.toULong(),
                outputDir = outputDir,
            )
        )
    }

    /** CM 直连探测：只握手拿 cell，用来判断「是我们代码的问题还是网络的问题」 */
    suspend fun probeCm(): String = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val servers = top.apricityx.workshop.steam.protocol.SteamDirectoryClient(client).loadServers()
        top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession(client).use { session ->
            val ctx = session.connectAnonymous(servers)
            "CM 匿名连接 OK cell=${ctx.cellId} steamId=${ctx.steamId} ${System.currentTimeMillis() - started}ms " +
                "（候选 ${servers.size} 个）"
        }
    }

    data class InstallResult(val ok: Boolean, val message: String, val dirName: String? = null)

    /**
     * 下载完成后把结果装进 mods 目录：单 zip 走导入器，散文件整目录搬进去。
     *
     * [preferName] 是这个工坊条目上次装出来的文件夹名（存在 prefs 里）。
     * 传了就原地覆盖，不再生成「标题 (2)」——重复点下载不该多出一份。
     */
    suspend fun install(
        context: Context,
        repo: ModRepo,
        item: Item,
        outputDir: File,
        preferName: String? = null,
    ): InstallResult = withContext(Dispatchers.IO) {
        val files = outputDir.walkTopDown()
            .filter { it.isFile }
            .filterNot { f ->
                val rel = f.relativeTo(outputDir).path.replace('\\', '/')
                rel.startsWith(".chunks/") || rel == "download.log" || rel == "metadata.json" ||
                    f.extension == "tmp" || f.extension == "part"
            }
            .toList()

        if (files.isEmpty()) return@withContext InstallResult(false, "下载完成但没有可用文件")
        val single = files.singleOrNull()
        if (single != null && single.name.lowercase(Locale.ROOT).endsWith(".zip")) {
            val outcome = ZipImport(repo).import(context, Uri.fromFile(single)) { _, _ -> }
            return@withContext InstallResult(outcome.ok, outcome.detail)
        }
        // 散文件：当成一个 mod 文件夹整体放入
        val root = outputDir
        val reuse = preferName?.takeIf { it.isNotBlank() }
            ?.let { File(Game.modsDir(), it) }
            ?.takeIf { it.isDirectory }
        val name = reuse?.name ?: repo.uniqueName(safeName(item.title))
        val dest = reuse ?: File(Game.modsDir(), name)
        // 覆盖安装：先清空原目录，别把旧版残留文件留在里面
        if (reuse != null) dest.deleteRecursively()
        dest.mkdirs()
        files.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            val target = File(dest, rel)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
        val projectXml = File(dest, "project.xml")
        if (!projectXml.isFile) {
            projectXml.writeText(ProjectXml.template(item.title), Charsets.UTF_8)
        }
        // 内容变了，指纹作废，不然游戏以为已经转换过，还按老 mod 跑
        File(Game.switchMarks(), name).delete()
        InstallResult(
            true,
            (if (reuse != null) "已更新 $name" else "已安装到 $name") + "（${files.size} 个文件）",
            name,
        )
    }

    private fun safeName(title: String): String {
        val cleaned = title.replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), "_").trim().trim('.')
        return cleaned.ifBlank { "workshop_mod" }.take(60)
    }

    fun stateLabel(state: DownloadState): String = when (state) {
        DownloadState.Idle -> "待开始"
        DownloadState.Resolving -> "查询元数据"
        DownloadState.Connecting -> "连接 Steam"
        DownloadState.Downloading -> "下载中"
        DownloadState.Paused -> "已暂停"
        DownloadState.Success -> "完成"
        DownloadState.Failed -> "失败"
    }
}
