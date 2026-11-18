package moe.ddlauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 匿名浏览 Steam 创意工坊——照搬 Apricityx/WorkshopAndroidDownloader 的做法：
 *
 * 不登录、不填 API Key，直接 GET steamcommunity.com/workshop/browse/ 这个公开网页，
 * 从 HTML 里解析出条目（id / 标题 / 作者 / 预览图 / 简介）。文件大小再用公开的
 * ISteamRemoteStorage/GetPublishedFileDetails 补一次（这个接口本来就不需要登录）。
 *
 * Steam 现在新版页面有两种形态：老的是 <div class="workshopItem"> 列表，新的是
 * SSR（<script id="valve-ssr-data"> 里的 JSON）。两种都解析，优先老版。
 */
object WorkshopBrowseRepo {

    private const val BASE = "https://steamcommunity.com/"
    private const val DETAILS = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    /** 网页抓取专用客户端：短超时 + 失败重试，避免国内网络下一个黑洞 IP 卡死 */
    private val client: OkHttpClient by lazy {
        WattAccelerator.decorate(
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
        ).build()
    }

    /** 网页里 browsesort / actualsort 的值，对应启动器的四种排序 */
    private fun sortValues(sort: WorkshopCatalog.Sort): Pair<String, String> = when (sort) {
        WorkshopCatalog.Sort.Popular -> "trend" to "trend"
        WorkshopCatalog.Sort.Recent -> "mostrecent" to "mostrecent"
        WorkshopCatalog.Sort.Updated -> "lastupdated" to "lastupdated"
        WorkshopCatalog.Sort.Subscribed -> "totaluniquesubscribers" to "totaluniquesubscribers"
    }

    data class Page(val items: List<WorkshopRepo.Item>, val total: Int, val hasNext: Boolean)

    suspend fun search(
        text: String,
        page: Int,
        sort: WorkshopCatalog.Sort,
        days: Int,
    ): Page = withContext(Dispatchers.IO) {
        val (browseSort, actualSort) = sortValues(sort)
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegments("workshop/browse/")
            .addQueryParameter("appid", WorkshopRepo.APP_ID.toString())
            .addQueryParameter("searchtext", text)
            .addQueryParameter("childpublishedfileid", "0")
            .addQueryParameter("l", "schinese")
            .addQueryParameter("browsesort", browseSort)
            .addQueryParameter("section", "readytouseitems")
            .addQueryParameter("actualsort", actualSort)
            .addQueryParameter("p", page.toString())
            .addQueryParameter("numperpage", "30")
            .apply { if (sort.supportsTimeWindow && days > 0) addQueryParameter("days", days.toString()) }
            .build()

        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", ACCEPT)
            .build()

        var lastError: Throwable? = null
        var html: String? = null
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("工坊网页返回 ${resp.code}")
                    }
                    if (body.length < 200) {
                        throw IllegalStateException("工坊网页返回空内容")
                    }
                    html = body
                    return@repeat
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        val payload = html ?: throw IllegalStateException(
            "连不上 steamcommunity.com：${lastError?.message ?: "未知错误"}（可能需要代理）"
        )

        // 1) 从 HTML 解析条目
        var parsed = parseLegacy(payload, page)
        if (parsed.items.isEmpty()) parsed = parseSsr(payload, page)
        if (parsed.items.isEmpty()) {
            throw IllegalStateException("工坊网页能打开，但没解析出条目（Steam 页面结构可能变了）")
        }

        // 2) 用公开 Web API 补文件大小 / 订阅数 / 更新时间（一次批量）
        val enriched = enrichWithDetails(parsed.items)

        Page(
            items = enriched,
            total = parsed.total,
            hasNext = parsed.hasNext,
        )
    }

    private data class RawItem(
        val id: Long,
        val title: String,
        val preview: String,
        val author: String,
        val description: String,
    )

    private data class Parsed(val items: List<RawItem>, val total: Int, val hasNext: Boolean)

    // ---------- 老版页面：<div class="workshopItem"> ----------
    private val itemBlockRegex = Regex(
        """<div\b[^>]*class="workshopItem"[^>]*>(.*?<div class="workshopItemAuthorName ellipsis">.*?</div>.*?)</div>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val itemHeaderRegex = Regex(
        """<a\b[^>]*href="[^"]*?id=(\d+)[^"]*"[^>]*class="ugc"[^>]*data-appid="(\d+)"[^>]*data-publishedfileid="\d+"[^>]*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val itemPreviewRegex = Regex(
        """class="workshopItemPreviewImage[^"]*"\s+src="([^"]+)"""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val itemTitleRegex = Regex(
        """class="workshopItemTitle ellipsis">(.*?)</div>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val itemAuthorRegex = Regex(
        """class="workshopItemAuthorName ellipsis">.*?<a\b[^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private fun parseLegacy(payload: String, page: Int): Parsed {
        val items = itemBlockRegex.findAll(payload).mapNotNull { block ->
            val b = block.groupValues[1]
            val header = itemHeaderRegex.find(b) ?: return@mapNotNull null
            val id = header.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            RawItem(
                id = id,
                title = strip(itemTitleRegex.find(b)?.groupValues?.getOrNull(1).orEmpty()),
                preview = itemPreviewRegex.find(b)?.groupValues?.getOrNull(1).orEmpty(),
                author = strip(itemAuthorRegex.find(b)?.groupValues?.getOrNull(1).orEmpty()),
                description = "",
            )
        }.toList()
        // 和上游一致：出现「下一页」按钮就认为还有
        val hasNext = items.isNotEmpty() && payload.contains("&p=${page + 1}") &&
            payload.contains("class='pagebtn'")
        return Parsed(items, if (hasNext) Int.MAX_VALUE else items.size, hasNext)
    }

    // ---------- 新版 SSR：<script id="valve-ssr-data"> ----------
    private val ssrDataScriptRegex = Regex(
        """<script\b[^>]*id="valve-ssr-data"[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private fun parseSsr(payload: String, page: Int): Parsed {
        // 两种 embedding：新的是 <script id="valve-ssr-data">{...}</script>；
        // 老的是 window.SSR.renderContext = JSON.parse("...") （字符串里带转义）
        var script = ssrDataScriptRegex.find(payload)?.groupValues?.getOrNull(1)
        if (script.isNullOrBlank()) {
            script = legacySsrRenderContext(payload)
        }
        if (script.isNullOrBlank()) return Parsed(emptyList(), 0, false)
        // 不引 kotlinx-serialization：用 org.json 从 SSR 的 queryData 里捞 results
        try {
            val root = org.json.JSONObject(script)
            val ctx = root.optJSONObject("renderContext") ?: root
            val queryData = ctx.optString("queryData", "")
            if (queryData.isBlank()) return Parsed(emptyList(), 0, false)
            val q = org.json.JSONObject(queryData)
            val queries = q.optJSONArray("queries") ?: return Parsed(emptyList(), 0, false)
            // 作者名表：PlayerLinkDetails -> steamId -> persona_name
            val names = HashMap<String, String>()
            for (i in 0 until queries.length()) {
                val entry = queries.optJSONObject(i) ?: continue
                val key = entry.optJSONArray("queryKey") ?: continue
                if (key.optString(0, "") != "PlayerLinkDetails") continue
                val sid = key.optString(1, "")
                val name = entry.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("public_data")
                    ?.optString("persona_name", "").orEmpty()
                if (sid.isNotBlank() && name.isNotBlank()) names[sid] = name
            }
            var results: org.json.JSONArray? = null
            var totalPages = page
            for (i in 0 until queries.length()) {
                val data = queries.optJSONObject(i)
                    ?.optJSONObject("state")?.optJSONObject("data") ?: continue
                val r = data.optJSONArray("results")
                if (r != null && data.has("current_page")) {
                    results = r
                    totalPages = data.optInt("total_pages", page)
                    break
                }
            }
            if (results == null) return Parsed(emptyList(), 0, false)
            val items = (0 until results.length()).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("publishedfileid", "").toLongOrNull() ?: return@mapNotNull null
                val sid = o.optString("creator", "")
                RawItem(
                    id = id,
                    title = strip(o.optString("title", "")),
                    preview = o.optString("preview_url", ""),
                    author = names[sid].orEmpty(),
                    description = strip(o.optString("short_description", "")),
                )
            }.toList()
            return Parsed(items, totalPages * 30, page < totalPages)
        } catch (t: Throwable) {
            return Parsed(emptyList(), 0, false)
        }
    }

    /**
     * 从 window.SSR.renderContext = JSON.parse("...."); 里抠出那个带转义的 JSON 字符串，
     * 处理好嵌套的转义引号（不能简单找第一个 " 就截断）。
     */
    private fun legacySsrRenderContext(payload: String): String? {
        val marker = Regex("""window\.SSR\.renderContext\s*=\s*JSON\.parse\(""")
        val m = marker.find(payload) ?: return null
        val start = m.range.last + 1
        var i = start
        while (i < payload.length) {
            if (payload[i] == '"') {
                var slashes = 0
                var p = i - 1
                while (p >= start && payload[p] == '\\') { slashes++; p-- }
                if (slashes % 2 == 0 && payload.getOrNull(i + 1) == ')' && payload.getOrNull(i + 2) == ';') {
                    // 截出来的还是 JS 字符串字面量（带 \uXXXX 和 \"），转成真正的 JSON 文本
                    val literal = payload.substring(start, i)
                    return try {
                        org.json.JSONObject("{\"v\":\"$literal\"}").getString("v")
                    } catch (t: Throwable) {
                        literal.replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                }
            }
            i++
        }
        return null
    }

    // ---------- 用公开 Web API 补元数据 ----------
    private fun enrichWithDetails(raw: List<RawItem>): List<WorkshopRepo.Item> {
        val byId = HashMap<Long, RawItem>()
        raw.forEach { byId[it.id] = it }
        if (raw.isEmpty()) return emptyList()

        val body = okhttp3.FormBody.Builder()
            .add("itemcount", raw.size.toString())
            .add("appid", WorkshopRepo.APP_ID.toString())
            .apply { raw.forEachIndexed { i, it -> add("publishedfileids[$i]", it.id.toString()) } }
            .build()
        val request = Request.Builder().url(DETAILS).post(body).build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return raw.toItems(byId)
                val arr = org.json.JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("response")?.optJSONArray("publishedfiledetails") ?: return raw.toItems(byId)
                val out = ArrayList<WorkshopRepo.Item>(raw.size)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("result", 1) != 1) continue
                    val id = o.optString("publishedfileid", "").toLongOrNull() ?: continue
                    val base = byId[id] ?: continue
                    out.add(
                        WorkshopRepo.Item(
                            id = id,
                            title = o.optString("title", "").ifBlank { base.title },
                            sizeBytes = o.optString("file_size", "0").toLongOrNull() ?: 0L,
                            updated = o.optString("time_updated", "0").toLongOrNull() ?: 0L,
                            subscriptions = o.optString("subscriptions", "0").toLongOrNull() ?: 0L,
                            previewUrl = o.optString("preview_url", "").ifBlank { base.preview },
                            description = strip(
                                o.optString("short_description", "").ifBlank { base.description }
                            ),
                            creator = base.author,
                            tags = emptyList(),
                        )
                    )
                }
                return out.ifEmpty { raw.toItems(byId) }
            }
        } catch (t: Throwable) {
            return raw.toItems(byId)
        }
    }

    private fun List<RawItem>.toItems(byId: Map<Long, RawItem>): List<WorkshopRepo.Item> = map { r ->
        WorkshopRepo.Item(
            id = r.id,
            title = r.title,
            sizeBytes = 0L,
            updated = 0L,
            subscriptions = 0L,
            previewUrl = r.preview,
            description = r.description,
            creator = r.author,
            tags = emptyList(),
        )
    }

    private fun strip(s: String): String {
        if (s.isBlank()) return ""
        return s.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\[/?[A-Za-z][A-Za-z0-9_]*(?:=[^]]+)?\\]"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("&#x?[0-9A-Fa-f]+;"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}
