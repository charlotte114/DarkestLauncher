package moe.ddlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 自定义分组（纯启动器侧的标签，游戏只认 mods 下的平铺目录，不改文件结构） */
data class ModGroup(val id: String, val name: String)

/**
 * 分组 / 组内排序 / 下载目标组的存储。
 *
 * 数据写在 mods 目录下的隐藏文件 `.launcher-groups.json`：
 *  - 和 mod 放在一起，卸载重装启动器也不会丢（mods 目录在 Android/media 下）；
 *  - 点号开头 + 不是目录，ModRepo.scan() 和游戏都不会把它当 mod。
 * 写不进去（没给权限）时退回应用外部目录，读的时候两边都看。
 */
class ModGroups(context: Context) {

    data class Snapshot(
        val groups: List<ModGroup> = emptyList(),
        /** mod 目录名 → 组 id；没有键就是未分组 */
        val assign: Map<String, String> = emptyMap(),
        /** 组 id（未分组用空串）→ 组内顺序（mod 目录名） */
        val order: Map<String, List<String>> = emptyMap(),
        /** 上次在工坊里选的「下载到哪个组」 */
        val downloadTarget: String? = null,
        /** 真正写进存档的加载顺序（和分组/列表显示顺序无关）；空 = 还没单独排过 */
        val loadOrder: List<String> = emptyList(),
    ) {
        fun groupOf(modId: String): String = assign[modId].orEmpty()

        fun nameOf(groupId: String?): String =
            if (groupId.isNullOrEmpty()) UNGROUPED
            else groups.firstOrNull { it.id == groupId }?.name ?: UNGROUPED

        fun exists(groupId: String?): Boolean =
            groupId.isNullOrEmpty() || groups.any { it.id == groupId }

        /**
         * 有效加载顺序：先按单独保存的 loadOrder，没列到的 mod（新装的、刚启用的）
         * 按传进来的列表顺序追加在后面。
         */
        fun loadOrderOf(mods: List<ModInfo>): List<ModInfo> {
            val byId = mods.associateBy { it.id }
            val out = ArrayList<ModInfo>(mods.size)
            val used = HashSet<String>()
            for (id in loadOrder) byId[id]?.let { if (used.add(id)) out.add(it) }
            for (m in mods) if (used.add(m.id)) out.add(m)
            return out
        }

        /**
         * 按分组切好段落：组按创建顺序，组内按保存的顺序，新加的 mod 追加在后面。
         * 未分组永远排在最后。
         */
        fun arrange(mods: List<ModInfo>): List<Section> {
            val byId = mods.associateBy { it.id }
            val used = HashSet<String>()
            val out = ArrayList<Section>()

            fun take(groupId: String): List<ModInfo> {
                val ordered = ArrayList<ModInfo>()
                for (id in order[groupId].orEmpty()) {
                    val m = byId[id] ?: continue
                    if (used.add(id)) ordered.add(m)
                }
                for (m in mods) {
                    if (used.contains(m.id)) continue
                    if (groupOf(m.id) != groupId) continue
                    used.add(m.id)
                    ordered.add(m)
                }
                return ordered
            }

            for (g in groups) out.add(Section(g, take(g.id)))
            val loose = take("")
            if (loose.isNotEmpty() || groups.isEmpty()) out.add(Section(null, loose))
            return out
        }
    }

    data class Section(val group: ModGroup?, val mods: List<ModInfo>)

    companion object {
        const val UNGROUPED = "未分组"
    }

    private val fallback = File(context.getExternalFilesDir(null), "mod-groups.json")

    private fun primary(): File = File(Game.modsDir(), ".launcher-groups.json")

    @Volatile
    private var cached: Snapshot? = null

    fun snapshot(): Snapshot = cached ?: load().also { cached = it }

    /** 磁盘上可能被外部改过（比如用户清了 mods），强制重读 */
    fun invalidate() {
        cached = null
    }

    private fun load(): Snapshot {
        val file = listOf(primary(), fallback).firstOrNull { it.isFile } ?: return Snapshot()
        val text = runCatching { file.readText() }.getOrNull() ?: return Snapshot()
        return runCatching {
            val root = JSONObject(text)
            val groupsJson = root.optJSONArray("groups") ?: JSONArray()
            val groups = ArrayList<ModGroup>()
            for (i in 0 until groupsJson.length()) {
                val o = groupsJson.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) groups.add(ModGroup(id, name))
            }
            val assignJson = root.optJSONObject("assign") ?: JSONObject()
            val assign = HashMap<String, String>()
            for (key in assignJson.keys()) {
                val v = assignJson.optString(key)
                if (v.isNotBlank() && groups.any { it.id == v }) assign[key] = v
            }
            val orderJson = root.optJSONObject("order") ?: JSONObject()
            val order = HashMap<String, List<String>>()
            for (key in orderJson.keys()) {
                val arr = orderJson.optJSONArray(key) ?: continue
                val ids = ArrayList<String>()
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { ids.add(it) }
                order[key] = ids
            }
            val loadJson = root.optJSONArray("loadOrder") ?: JSONArray()
            val loadOrder = ArrayList<String>()
            for (i in 0 until loadJson.length()) {
                loadJson.optString(i).takeIf { it.isNotBlank() }?.let { loadOrder.add(it) }
            }
            val target = root.optString("download").takeIf { it.isNotBlank() }
            Snapshot(groups, assign, order, target?.takeIf { id -> groups.any { it.id == id } }, loadOrder)
        }.getOrElse { Snapshot() }
    }

    private fun save(snap: Snapshot): Boolean {
        cached = snap
        val json = JSONObject().apply {
            put("v", 1)
            put("groups", JSONArray().apply {
                snap.groups.forEach { g -> put(JSONObject().put("id", g.id).put("name", g.name)) }
            })
            put("assign", JSONObject().apply { snap.assign.forEach { (k, v) -> put(k, v) } })
            put("order", JSONObject().apply { snap.order.forEach { (k, v) -> put(k, JSONArray(v)) } })
            put("download", snap.downloadTarget ?: "")
            put("loadOrder", JSONArray(snap.loadOrder))
        }.toString()
        val targets = listOf(primary(), fallback)
        for (f in targets) {
            val ok = runCatching {
                f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                f.writeText(json)
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    // ---------------- 操作 ----------------

    fun createGroup(name: String): ModGroup? {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return null
        val snap = snapshot()
        if (snap.groups.any { it.name.equals(clean, ignoreCase = true) }) return null
        val group = ModGroup("g" + UUID.randomUUID().toString().take(8), clean)
        save(snap.copy(groups = snap.groups + group))
        return group
    }

    fun renameGroup(id: String, name: String): Boolean {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return false
        val snap = snapshot()
        if (!snap.groups.any { it.id == id }) return false
        if (snap.groups.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) return false
        save(snap.copy(groups = snap.groups.map { if (it.id == id) it.copy(name = clean) else it }))
        return true
    }

    /** 删组不删 mod：组里的 mod 回落到未分组 */
    fun deleteGroup(id: String) {
        val snap = snapshot()
        save(
            snap.copy(
                groups = snap.groups.filterNot { it.id == id },
                assign = snap.assign.filterValues { it != id },
                order = snap.order - id,
                downloadTarget = snap.downloadTarget?.takeIf { it != id },
            )
        )
    }

    fun moveGroups(from: Int, to: Int) {
        val snap = snapshot()
        if (from !in snap.groups.indices || to !in snap.groups.indices || from == to) return
        val list = snap.groups.toMutableList()
        list.add(to, list.removeAt(from))
        save(snap.copy(groups = list))
    }

    fun assign(modIds: Collection<String>, groupId: String?) {
        if (modIds.isEmpty()) return
        val target = groupId.orEmpty()
        val snap = snapshot()
        if (!snap.exists(target)) return
        val assign = HashMap(snap.assign)
        val order = HashMap(snap.order)
        for (id in modIds) {
            if (target.isEmpty()) assign.remove(id) else assign[id] = target
            // 从旧组的顺序表里摘掉，避免同一 id 出现在两个组
            order.keys.toList().forEach { key ->
                val list = order[key] ?: return@forEach
                if (key != target && list.contains(id)) order[key] = list - id
            }
            if (!order[target].orEmpty().contains(id)) {
                order[target] = order[target].orEmpty() + id
            }
        }
        save(snap.copy(assign = assign, order = order))
    }

    /** 拖动排序后写入某组的完整顺序 */
    fun setOrder(groupId: String?, ids: List<String>) {
        val snap = snapshot()
        save(snap.copy(order = snap.order + (groupId.orEmpty() to ids)))
    }

    fun setDownloadTarget(groupId: String?) {
        val snap = snapshot()
        save(snap.copy(downloadTarget = groupId?.takeIf { snap.exists(it) && it.isNotEmpty() }))
    }

    /** 单独排的加载顺序（启动游戏时写进存档的就是它） */
    fun setLoadOrder(ids: List<String>) {
        val snap = snapshot()
        save(snap.copy(loadOrder = ids.filter { it.isNotBlank() }.distinct()))
    }
}
