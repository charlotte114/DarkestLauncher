package moe.ddlauncher

import org.json.JSONObject
import java.io.File

/**
 * 把启动器里的 mod 启用状态和顺序写进每个存档的 `persist.game.json`。
 *
 * 游戏（包括这个 Switch 移植版）的 mod 优先级是**每个存档单独一份**，存在：
 *
 * ```
 * base_root.applied_ugcs_1_0 = { "0": {name, source}, "1": {...}, ... }
 * ```
 *
 * 键 `"0"` 优先级最高（最后加载、覆盖别人），往后依次降低；启动器里排在最上面的
 * mod 也写到 `"0"`。启动器里关闭的 mod 会从这张表里移除，新启用的 mod 会补进来，
 * 所以不用每次进游戏再手动启用。
 * `base_root.persistent_ugcs.applied_ugcs_1_0` 只是「曾经启用过」的历史记录，
 * 这里只往尾部补新条目，不重排。
 *
 * name/source 必须和引擎扫 `mods/` 时登记的完全一致：移植层对每个 mod 目录固定登记
 * `mod_local_source`，name 取 project.xml 的 `<Title>`（main NSO 0x8bd988-0x8bd9b0：
 * Title 从 +0xee0 拷到 +0x620，source 直接写 `mod_local_source`）。存档里如果还留着
 * PC/旧版的 PublishedFileId + `Steam` 写法，引擎按 name+source 匹配不上，那个 mod
 * 等于没启用，排序自然也看不出来。
 *
 * 写法：先整份解析、重建、比对；有变化才写，并留一份 `persist.game.json.bak`。
 * 结构对不上（没有 base_root / applied_ugcs_1_0）的存档一律不动。
 */
object ModOrderSync {

    private const val APPLIED = "applied_ugcs_1_0"
    private const val PERSISTENT = "persistent_ugcs"
    private const val LOCAL_SOURCE = "mod_local_source"

    data class Result(
        val saves: Int = 0,
        val changed: Int = 0,
        val mods: Int = 0,
        val skipped: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
    ) {
        val summary: String
            get() = when {
                saves == 0 -> "没找到可同步的存档"
                changed == 0 && skipped.size >= saves -> "$saves 个存档都跳过了（结构不认识）"
                changed == 0 -> "$saves 个存档的启用状态和顺序已经一致（$mods 个 mod）"
                else -> "已同步 $changed/$saves 个存档，$mods 个 mod 的启用状态和顺序"
            }
    }

    /** 按启动器里从上到下的顺序同步；[ordered] 必须是最终显示顺序 */
    fun sync(ordered: List<ModInfo>): Result {
        val enabled = ordered.filter { it.enabled }

        val files = saveFiles()
        if (files.isEmpty()) return Result(skipped = listOf("没有 persist.game.json"))

        var changed = 0
        val skipped = ArrayList<String>()
        val notes = ArrayList<String>()
        for (file in files) {
            when (val r = syncOne(file, enabled, notes)) {
                true -> changed++
                false -> Unit
                null -> skipped.add(file.absolutePath)
            }
        }
        return Result(files.size, changed, enabled.size, skipped, notes)
    }

    /** true=改了，false=本来就一致，null=结构不认识没动 */
    private fun syncOne(file: File, ordered: List<ModInfo>, notes: MutableList<String>): Boolean? {
        val bytes = runCatching { file.readBytes() }.getOrElse {
            notes.add("$file 读不了：${it.message}")
            return null
        }
        notes.add("$file ${bytes.size} 字节，开头 ${bytes.take(8).joinToString(" ") { b -> "%02x".format(b) }}")
        if (bytes.size >= 0x40 && le32(bytes, 0) == BinarySave.MAGIC) {
            notes.add("$file 是引擎二进制存档")
            // 旧版按 4 字节对齐写坏过存档：值读不出来时先用 .bak 还原，再重新同步
            var data = bytes
            val backup = File(file.parentFile, file.name + ".bak")
            val readOrder = BinarySave.appliedOrder(data)
            if (readOrder != null) notes.add("写入前磁盘顺序 ${readOrder.size} 个：${readOrder.take(6)}")
            val backupBytes = if (backup.isFile) runCatching { backup.readBytes() }.getOrNull() else null
            val backupOrder = backupBytes?.let { BinarySave.appliedOrder(it) }
            if (backupBytes != null && backupOrder != null) {
                notes.add("备份 ${backupBytes.size} 字节，${backupOrder.size} 个 mod")
            }
            val broken = readOrder != null && readOrder.isNotEmpty() && readOrder.all { it == "?" }
            // 注意：不能因为「当前启用项比备份少」就回滚，用户主动关闭 mod 也会变少。
            // 只有启用列表明显读坏（全是 ?）时才认为需要拿备份救整份存档。
            if (backupBytes != null && backupOrder != null && broken &&
                backupBytes.size >= 0x40 && le32(backupBytes, 0) == BinarySave.MAGIC
            ) {
                data = backupBytes
                notes.add("$file 的值读不出来，已用 .bak 还原后再同步")
            }
            val wants = ordered.map {
                BinarySave.Want(it.title.takeIf { title -> title.isNotBlank() }, candidateNames(it))
            }
            return when (val o = BinarySave.setApplied(data, wants, notes)) {
                is BinarySave.Outcome.Changed -> {
                    val ok = writeBytes(file, o.bytes, notes)
                    if (ok) {
                        val back = runCatching { BinarySave.appliedOrder(file.readBytes()) }.getOrNull()
                        notes.add("写回后读到的启用列表 ${back?.size} 个：${back?.take(6)}")
                    }
                    ok
                }
                is BinarySave.Outcome.Unchanged -> if (data !== bytes) writeBytes(file, data, notes) else false
                is BinarySave.Outcome.Unsupported -> if (data !== bytes) writeBytes(file, data, notes) else null
            }
        }
        var text = runCatching { String(bytes, Charsets.UTF_8) }.getOrElse {
            notes.add("$file 不是 UTF-8：${it.message}")
            return null
        }
        if (text.startsWith('\uFEFF')) {
            text = text.removePrefix("\uFEFF")
            notes.add("$file 有 UTF-8 BOM，已去掉")
        }
        val root = runCatching { JSONObject(text) }.getOrElse {
            notes.add("$file JSON 解析失败：${it.message}")
            dumpForDebug(file, notes)
            return null
        }
        notes.add("顶层 keys=${root.keys().asSequence().toList()}")
        val base = locateAppliedParent(root)
        if (base == null) {
            notes.add("$file 里找不到 $APPLIED")
            dumpShape(root, notes)
            return null
        }
        val applied = base.getJSONObject(APPLIED)
        val diskOrder = namesOf(applied)
        notes.add("存档里的启用列表 ${diskOrder.size} 个：${diskOrder.take(6)}")

        val next = JSONObject()
        val used = HashSet<String>()
        fun put(name: String, source: String) {
            next.put(next.length().toString(), JSONObject()
                .put("name", name)
                .put("source", source))
        }

        for (mod in ordered) {
            val name = mod.title.ifBlank { mod.dir.name }
            if (!used.add(name)) continue
            put(name, LOCAL_SOURCE)
        }

        notes.add("启动器的新启用列表 ${next.length()} 个：${namesOf(next).take(6)}")
        if (sameOrder(applied, next)) return false

        val backup = File(file.parentFile, file.name + ".bak")
        runCatching { file.copyTo(backup, overwrite = true) }

        base.put(APPLIED, next)
        // 新启用的 mod 追加进历史表，保持游戏自己的记录习惯
        val persistent = base.optJSONObject(PERSISTENT)?.optJSONObject(APPLIED)
        if (persistent != null) {
            var index = 0
            while (persistent.has(index.toString())) index++
            for (i in 0 until next.length()) {
                val entry = next.getJSONObject(i.toString())
                val name = entry.getString("name")
                var exists = false
                for (key in persistent.keys()) {
                    if (persistent.optJSONObject(key)?.optString("name") == name) { exists = true; break }
                }
                if (!exists) persistent.put((index++).toString(), entry)
            }
        }

        val tmp = File(file.parentFile, file.name + ".tmp")
        val wrote = runCatching {
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse {
            runCatching { tmp.delete() }
            notes.add("$file 写回失败：${it.message}")
            false
        }
        if (!wrote) return null

        // 写完立刻读回来，确认落盘的就是我们排的顺序
        val back = runCatching {
            val t = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            locateAppliedParent(JSONObject(t))?.getJSONObject(APPLIED)?.let(::namesOf)
        }.getOrNull()
        notes.add("写回后读到的顺序 ${back?.size} 个：${back?.take(6)}")
        return true
    }

    /** 正常在 base_root 下；不认识的外层结构就往下找第一个带 applied_ugcs_1_0 的对象 */
    private fun locateAppliedParent(root: JSONObject): JSONObject? {
        root.optJSONObject("base_root")?.let { b ->
            if (b.optJSONObject(APPLIED) != null) return b
        }
        var layer = listOf(root)
        repeat(6) {
            val nextLayer = ArrayList<JSONObject>()
            for (o in layer) {
                if (o.optJSONObject(APPLIED) != null) return o
                for (k in o.keys()) o.optJSONObject(k)?.let(nextLayer::add)
            }
            if (nextLayer.isEmpty()) return null
            layer = nextLayer
        }
        return null
    }

    private fun namesOf(o: JSONObject): List<String> =
        (0 until o.length()).mapNotNull { o.optJSONObject(it.toString())?.optString("name") }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun writeBytes(file: File, bytes: ByteArray, notes: MutableList<String>): Boolean {
        val backup = File(file.parentFile, file.name + ".bak")
        // 只保留第一次写入前的原件，别让后续写入把可恢复的备份覆盖掉
        if (!backup.exists()) runCatching { file.copyTo(backup, overwrite = false) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse {
            runCatching { tmp.delete() }
            notes.add("$file 写回失败：${it.message}")
            false
        }
        if (ok) notes.add("$file 已写入新顺序")
        return ok
    }

    private fun sameOrder(a: JSONObject, b: JSONObject): Boolean {
        if (a.length() != b.length()) return false
        for (i in 0 until b.length()) {
            val x = a.optJSONObject(i.toString()) ?: return false
            val y = b.optJSONObject(i.toString()) ?: return false
            if (x.optString("name") != y.optString("name")) return false
            if (x.optString("source") != y.optString("source")) return false
        }
        return true
    }

    /** 结构对不上时把顶层类型打进日志，别再靠猜 */
    private fun dumpShape(o: JSONObject, notes: MutableList<String>) {
        notes.add(
            "顶层类型=" + o.keys().asSequence()
                .map { k -> "$k:${o.opt(k)?.javaClass?.simpleName}" }
                .toList(),
        )
    }

    /** 存档不是明文 JSON 时，复制一份到 files/ 下方便 adb 拉出来分析 */
    private fun dumpForDebug(file: File, notes: MutableList<String>) {
        runCatching {
            val files = file.parentFile?.parentFile?.parentFile?.parentFile ?: return
            val out = File(files, "persist.dump.bin")
            file.copyTo(out, overwrite = true)
            notes.add("已复制一份到 ${out.absolutePath}")
        }.onFailure { notes.add("复制失败：${it.message}") }
    }

    /** 存档可能是 `profile_0/persist.game.json`，也可能是单档的 `save/persist.game.json` */
    private fun saveFiles(): List<File> {
        val dir = Game.saveDir()
        if (!dir.isDirectory) return emptyList()
        val direct = File(dir, "persist.game.json")
        val out = ArrayList<File>()
        if (direct.isFile) out.add(direct)
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(Regex("profile_\\d+")) }
            .sortedBy { it.name }
            .forEach { p -> File(p, "persist.game.json").takeIf { it.isFile }?.let(out::add) }
        return out
    }

    /** 工坊 mod 在存档里可能记 folder 名，也可能记 PublishedFileId */
    private fun candidateNames(mod: ModInfo): List<String> = buildList {
        mod.title.trim().takeIf { it.isNotEmpty() }?.let(::add)
        mod.dir.name.takeIf { it.isNotBlank() }?.let(::add)
        mod.workshopId.trim().takeIf { it.isNotEmpty() }?.let(::add)
    }
}
