package moe.ddlauncher

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 离线工坊目录：内置在 assets 里的一份精选合集索引（670 条，188 KB）。
 *
 * 为什么要有它：`steamcommunity.com` 在国内被 SNI 阻断，而能连通的
 * `IPublishedFileService/QueryFiles` 必须带 API key。这份目录让搜索浏览
 * 在完全离线、无 key 的情况下也能用。
 *
 * 内存：只在工坊页打开时解析，页面关闭即释放（[release]）。
 * 670 条解析后约 1 MB，远小于任何一张 3.6 GB 数据包的零头。
 */
object WorkshopCatalog {

    private const val ASSET = "workshop_catalog.json"

    data class Entry(
        val id: Long,
        val title: String,
        val author: String,
        val summary: String,
        val subs: Int,
        val updated: Long,
        val tags: List<String>,
        val size: Long,
        val preview: String,
        val collections: List<String>,
    )

    data class CollectionInfo(val id: String, val name: String, val desc: String)

    var items: List<Entry> = emptyList()
        private set
    var collections: List<CollectionInfo> = emptyList()
        private set
    var builtAt: Long = 0L
        private set
    var loaded: Boolean = false
        private set

    /** 目录里出现过的标签，按出现次数降序 */
    var topTags: List<Pair<String, Int>> = emptyList()
        private set

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            builtAt = root.optLong("ts", 0L)
            val cols = root.optJSONArray("collections")
            val cs = ArrayList<CollectionInfo>()
            if (cols != null) {
                for (i in 0 until cols.length()) {
                    val o = cols.getJSONObject(i)
                    cs += CollectionInfo(o.optString("id"), o.optString("n"), o.optString("d"))
                }
            }
            val arr = root.optJSONArray("items")
            val list = ArrayList<Entry>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val tagArr = o.optJSONArray("g")
                    val tags = ArrayList<String>(tagArr?.length() ?: 0)
                    if (tagArr != null) for (j in 0 until tagArr.length()) tags += tagArr.optString(j)
                    val cArr = o.optJSONArray("c")
                    val cids = ArrayList<String>(cArr?.length() ?: 0)
                    if (cArr != null) for (j in 0 until cArr.length()) cids += cArr.optString(j)
                    list += Entry(
                        id = o.optString("id").toLongOrNull() ?: continue,
                        title = o.optString("t"),
                        author = o.optString("a"),
                        summary = o.optString("s"),
                        subs = o.optInt("n"),
                        updated = o.optLong("u"),
                        tags = tags,
                        size = o.optLong("z"),
                        preview = o.optString("p"),
                        collections = cids,
                    )
                }
            }
            collections = cs
            items = list
            val counter = HashMap<String, Int>()
            list.forEach { e -> e.tags.forEach { counter[it] = (counter[it] ?: 0) + 1 } }
            topTags = counter.entries.sortedByDescending { it.value }.take(14).map { it.key to it.value }
            loaded = true
        } catch (t: Throwable) {
            // 目录损坏不该让整个页面挂掉：退化成空目录，用 ID/链接查询仍可用
            items = emptyList()
            collections = emptyList()
            topTags = emptyList()
            loaded = false
        }
    }

    fun release() {
        items = emptyList()
        collections = emptyList()
        topTags = emptyList()
        loaded = false
    }

    /**
     * 工坊标签是英文，中文用户搜「角色」「皮肤」会一条都搜不到。
     * 这里做一层别名：查询词命中中文键时，把它扩展成一组英文候选（组内 OR）。
     */
    private val SYNONYMS = mapOf(
        "角色" to listOf("new class", "class mod", "character mod", "hero", "class"),
        "职业" to listOf("new class", "class mod", "class"),
        "英雄" to listOf("new class", "hero", "class"),
        "人物" to listOf("new class", "character", "hero"),
        "皮肤" to listOf("skin", "reskin", "skins"),
        "美化" to listOf("skin", "retexture", "background"),
        "立绘" to listOf("skin", "art"),
        "怪物" to listOf("monster", "enemy", "boss"),
        "敌人" to listOf("monster", "enemy", "boss"),
        "boss" to listOf("boss", "monster"),
        "饰品" to listOf("trinket"),
        "道具" to listOf("trinket", "item"),
        "汉化" to listOf("localization", "chinese", "translation", "汉化", "中文"),
        "中文" to listOf("localization", "chinese", "汉化", "中文"),
        "翻译" to listOf("translation", "localization", "chinese"),
        "界面" to listOf("ui", "interface", "hud"),
        "玩法" to listOf("gameplay", "tweaks"),
        "平衡" to listOf("balance", "gameplay"),
        "难度" to listOf("difficulty", "balance"),
        "副本" to listOf("dungeon", "quest", "area"),
        "地图" to listOf("dungeon", "map", "quest"),
        "背景" to listOf("background", "curio"),
        "音效" to listOf("sound", "audio", "music"),
        "音乐" to listOf("music", "audio", "sound"),
        "建筑" to listOf("district", "building", "town"),
        "城镇" to listOf("town", "district"),
    )

    private val TAG_LABELS = mapOf(
        "new class" to "新角色",
        "class mod" to "职业 Mod",
        "character mod" to "角色 Mod",
        "skins" to "皮肤",
        "skin" to "皮肤",
        "monsters" to "怪物",
        "monster" to "怪物",
        "gameplay tweaks" to "玩法调整",
        "trinkets" to "饰品",
        "trinket" to "饰品",
        "localization" to "汉化",
        "ui" to "界面",
        "dungeon" to "副本",
        "balance" to "平衡",
        "difficulty" to "难度",
        "boss" to "Boss",
        "background" to "背景",
        "curio" to "互动物",
        "quest" to "任务",
        "class" to "职业",
        "art" to "美术",
    )

    fun tagLabel(raw: String): String = TAG_LABELS[raw.lowercase()] ?: raw

    /** 一个查询词展开成一组候选：词内 OR，词间 AND */
    private fun expand(word: String): List<String> {
        val direct = SYNONYMS[word]
        return if (direct != null) listOf(word) + direct else listOf(word)
    }

    /**
     * 搜索：空格分词，词间 AND、词内 OR（含中文别名扩展）；空白则返回全部。
     * [source] 为空时用内置目录，否则用外部合集的条目（粘贴合集链接展开的那批）。
     */
    fun search(
        query: String,
        tag: String?,
        collection: String?,
        sort: Sort,
        source: List<Entry> = emptyList(),
    ): List<Entry> {
        val pool = if (source.isNotEmpty()) source else items
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var seq = pool.asSequence()
        if (tag != null) seq = seq.filter { it.tags.any { t -> t.equals(tag, true) } }
        if (collection != null) seq = seq.filter { it.collections.contains(collection) }
        if (words.isNotEmpty()) {
            val groups = words.map { expand(it) }
            seq = seq.filter { e ->
                val hay = (e.title + " " + e.summary + " " + e.tags.joinToString(" ") + " " + e.author + " " + e.id)
                    .lowercase()
                groups.all { g -> g.any { hay.contains(it) } }
            }
        }
        // 顺序一律听服务端的（工坊相关性 / 合集作者排序），本地只做关键词过滤
        return seq.toList()
    }

    /**
     * 排序方式照搬 Apricityx/WorkshopAndroidDownloader 的 `WorkshopBrowseSortOption`。
     *
     * 那边是抓网页（browsesort 字符串），我们走 CM protobuf，只能翻成 `query_type` 整数。
     * 这几个值是真机上一个一个试出来的（用 `-e wsq <词> -e wsqt <数字>` 打的）：
     *  - 0 = 按评分，1 = 按发布时间，3 = 趋势/热门，9 = 按订阅数，12 = 按搜索词相关性，18 = 按更新时间
     */
    enum class Sort(
        val label: String,
        /** 没有搜索词时用这个 query_type（浏览热门用） */
        val popularType: Int,
        /** 有搜索词时用这个（既要过滤也要排序） */
        val textType: Int,
        /** 是否支持时间窗（只对「热门」有意义，和上游一致） */
        val supportsTimeWindow: Boolean = false,
    ) {
        Popular("热门", popularType = 3, textType = 12, supportsTimeWindow = true),
        Recent("最新发布", popularType = 1, textType = 1),
        Updated("最近更新", popularType = 18, textType = 18),
        Subscribed("订阅最多", popularType = 9, textType = 9),
        ;

        fun queryTypeFor(text: String): Int = if (text.isBlank()) popularType else textType
    }

    /** 时间窗，同样照搬上游的 `WorkshopBrowseTimeWindow` */
    enum class TimeWindow(val label: String, val days: Int) {
        Today("今日", 1),
        Week("本周", 7),
        Month("30 天", 30),
        Quarter("3 个月", 90),
        Year("1 年", 365),
        All("全部", 0),
    }

    /** 缓存目录：本地索引文件放在 App 私有目录，卸载即清 */
    fun cacheFile(context: Context): File = File(context.filesDir, "workshop_index.json")
}
