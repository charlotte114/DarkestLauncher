package moe.ddlauncher

/**
 * 引擎自带的 `heirarchical_file` 二进制存档（magic 0xb101）。
 *
 * 结构（相对文件头 64 字节）：
 * ```
 * 0x00  magic=0xb101, flags
 * 0x10  A 表字节数, A 表条数
 * 0x18  A 表偏移(64)
 * 0x2c  B 表条数
 * 0x30  B 表偏移
 * 0x38  blob 字节数
 * 0x3c  blob 偏移
 * A 表 16 字节/条：parent, 名字的 B 表下标, 直接子项数, 子树跨度
 * B 表 12 字节/条：hash(名字)=h*53+c, blob 偏移, 长度<<2 | 节点<<11 | 是否对象
 * blob：按 B 表顺序拼接的「键字符串 + 值」块（值类型由游戏代码按 schema 决定）
 * ```
 *
 * `applied_ugcs_1_0` 既是 mod 启用列表，也是加载顺序：`"0"` 优先级最高。
 * 这里按启动器里的启用列表整棵重建该子树，所以既能排序，也能新增/移除启用项。
 */
internal object BinarySave {

    const val MAGIC = 0xB101
    private const val APPLIED = "applied_ugcs_1_0"
    private const val LOCAL_SOURCE = "mod_local_source"

    sealed class Outcome {
        /** 结构不认识，没动 */
        object Unsupported : Outcome()
        /** 内容本来就一致 */
        object Unchanged : Outcome()
        class Changed(val bytes: ByteArray) : Outcome()
    }

    /** 启动器里的一个 mod：title 为空时按 names 里的目录名/工坊 id 兜底 */
    class Want(val title: String?, val names: List<String>)

    /**
     * 用 [desired] 完全替换 `applied_ugcs_1_0` 的启用列表。
     * [desired] 的顺序就是启动器里的最终顺序。
     */
    fun setApplied(bytes: ByteArray, desired: List<Want>, notes: MutableList<String>): Outcome {
        if (bytes.size < 0x40) return Outcome.Unsupported
        val d = Doc(bytes)
        if (d.aOff < 0x40 || d.countA <= 0 || d.countB <= 0 ||
            d.countA > 100_000 || d.countB > 1_000_000
        ) {
            notes.add("二进制头不合法：A=${d.aOff}/${d.countA} B=${d.bOff}/${d.countB}")
            return Outcome.Unsupported
        }
        if (d.aOff.toLong() + d.countA.toLong() * 16 > d.bOff ||
            d.bOff.toLong() + d.countB.toLong() * 12 > d.blobOff ||
            d.blobOff > bytes.size
        ) {
            notes.add("二进制分区越界：A=${d.aOff}/${d.countA} B=${d.bOff}/${d.countB} blob=${d.blobOff}")
            return Outcome.Unsupported
        }

        val rootA = (0 until d.countA).firstOrNull { d.nodeParent(it) == -1 } ?: run {
            notes.add("没有根节点")
            return Outcome.Unsupported
        }

        val aToB = IntArray(d.countA) { -1 }
        for (i in 0 until d.countB) {
            val flags = d.entFlags(i)
            if ((flags and 3) != 1) continue
            val ai = (flags ushr 11) and 0x1ff
            if (ai !in 0 until d.countA || aToB[ai] >= 0) {
                notes.add("B 表对象节点映射不合法：条目 $i -> A$ai")
                return Outcome.Unsupported
            }
            aToB[ai] = i
        }
        if (aToB.any { it < 0 }) {
            notes.add("A 表里有节点在 B 表找不到对象键")
            return Outcome.Unsupported
        }

        val root = try {
            parseObject(d, rootA, aToB)
        } catch (t: Throwable) {
            notes.add("解析二进制树失败：${t.message ?: t.javaClass.simpleName}")
            return Outcome.Unsupported
        }

        val applied = root.children.firstOrNull { it.obj && it.key == APPLIED } ?: run {
            notes.add("没有 $APPLIED（在根下）")
            return Outcome.Unsupported
        }

        val old = applied.children.mapNotNull { entry ->
            val name = entry.children.firstOrNull { it.key == "name" }?.value
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val source = entry.children.firstOrNull { it.key == "source" }?.value?.trim()
                .orEmpty().ifBlank { LOCAL_SOURCE }
            Entry(name, source)
        }

        val target = LinkedHashMap<String, Entry>()
        for (want in desired) {
            val name = want.title?.takeIf { it.isNotBlank() }
                ?: want.names.asSequence().firstOrNull { it.isNotBlank() }
                ?: continue
            if (target.putIfAbsent(name, Entry(name, LOCAL_SOURCE)) != null) {
                notes.add("重复的 mod 名，只保留第一个：$name")
            }
        }
        val wanted = target.values.toList()

        val persistentApplied = root.children
            .firstOrNull { it.obj && it.key == "persistent_ugcs" }
            ?.children
            ?.firstOrNull { it.obj && it.key == APPLIED }
        val persistentNames = persistentApplied?.children.orEmpty().mapNotNull { entry ->
            entry.children.firstOrNull { it.key == "name" }?.value?.takeIf { it.isNotBlank() }
        }.toHashSet()
        val persistentAdd = persistentApplied?.let { p ->
            wanted.filter { it.name !in persistentNames }
        }.orEmpty()

        val appliedSame = old.size == wanted.size &&
            applied.children.size == wanted.size &&
            old.indices.all {
                old[it].name == wanted[it].name && old[it].source == wanted[it].source
            }
        if (appliedSame && persistentAdd.isEmpty()) {
            notes.add("二进制存档里有 ${old.size} 个启用 mod，启用状态和顺序已经一致")
            return Outcome.Unchanged
        }

        applied.children.clear()
        for ((index, entry) in wanted.withIndex()) {
            applied.children.add(entryNode(index.toString(), entry.name, entry.source))
        }

        if (persistentApplied != null && persistentAdd.isNotEmpty()) {
            var next = persistentApplied.children
                .mapNotNull { it.key.toIntOrNull() }
                .maxOrNull()?.plus(1) ?: 0
            for (entry in persistentAdd) {
                persistentApplied.children.add(entryNode(next.toString(), entry.name, entry.source))
                next++
            }
        }

        val out = try {
            rebuild(d, root)
        } catch (t: Throwable) {
            notes.add("重建二进制存档失败：${t.message ?: t.javaClass.simpleName}")
            return Outcome.Unsupported
        }

        val back = appliedOrder(out)
        if (back == null || back != wanted.map { it.name }) {
            notes.add("重建后自检失败：读回 ${back?.size ?: -1} 个，期望 ${wanted.size} 个")
            return Outcome.Unsupported
        }

        val removed = old.count { it.name !in target }
        val added = wanted.count { w -> old.none { it.name == w.name } }
        notes.add(
            "二进制存档启用列表 ${old.size} -> ${wanted.size} 个" +
                if (added > 0 || removed > 0) "（新增 $added，移除 $removed）" else "",
        )
        if (persistentAdd.isNotEmpty()) {
            notes.add("历史列表补了 ${persistentAdd.size} 个：${persistentAdd.take(6).map { it.name }}")
        }
        return if (out.contentEquals(bytes)) Outcome.Unchanged else Outcome.Changed(out)
    }

    /** 按存档里的枚举顺序读出 `applied_ugcs_1_0` 的 mod 名，用于写回后自检 */
    fun appliedOrder(bytes: ByteArray): List<String>? {
        if (bytes.size < 0x40) return null
        val d = Doc(bytes)
        if (d.aOff < 0x40 || d.countA <= 0 || d.countB <= 0) return null
        val root = (0 until d.countA).firstOrNull { d.nodeParent(it) == -1 } ?: return null
        val applied = (0 until d.countA).firstOrNull {
            d.nodeParent(it) == root && d.keyAt(d.nodeName(it)) == APPLIED
        } ?: return null
        return (0 until d.countA)
            .filter { d.nodeParent(it) == applied }
            .sortedBy { d.nodeName(it) }
            .map { titleOf(d, d.nodeName(it), d.nodeSpan(it) + 1) ?: "?" }
    }

    private data class Entry(val name: String, val source: String)

    private class Node(
        val key: String,
        val obj: Boolean,
        val chunk: ByteArray,
        val align: Int,
        val value: String? = null,
    ) {
        val children = ArrayList<Node>()
        var bi = -1
        var ai = -1
        var parentAi = -1
        var subtree = 1
        var offset = 0
    }

    private fun parseObject(d: Doc, ai: Int, aToB: IntArray): Node {
        val bi = aToB[ai]
        val node = Node(d.keyAt(bi), true, d.chunk(bi), d.entOff(bi) and 3)
        var cursor = bi + 1
        repeat(d.nodeCount(ai)) {
            if (cursor !in 0 until d.countB) throw IllegalStateException("子项越界 A$ai")
            val flags = d.entFlags(cursor)
            if ((flags and 3) == 1) {
                val childAi = (flags ushr 11) and 0x1ff
                if (childAi !in 0 until d.countA) {
                    throw IllegalStateException("子对象越界 A$childAi")
                }
                node.children.add(parseObject(d, childAi, aToB))
                cursor += 1 + d.nodeSpan(childAi)
            } else {
                node.children.add(
                    Node(
                        key = d.keyAt(cursor),
                        obj = false,
                        chunk = d.chunk(cursor),
                        align = d.entOff(cursor) and 3,
                        value = d.valueString(cursor),
                    ),
                )
                cursor++
            }
        }
        if (cursor != bi + 1 + d.nodeSpan(ai)) {
            throw IllegalStateException("子树跨度对不上 A$ai")
        }
        return node
    }

    private fun entryNode(key: String, name: String, source: String): Node {
        val node = Node(key, true, objectChunk(key), 0)
        node.children.add(stringNode("name", name))
        node.children.add(stringNode("source", source))
        return node
    }

    private fun stringNode(key: String, value: String): Node {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        val valueAt = (keyBytes.size + 4) and 3.inv()
        val chunk = ByteArray(valueAt + 4 + valueBytes.size + 1)
        System.arraycopy(keyBytes, 0, chunk, 0, keyBytes.size)
        putU32(chunk, valueAt, valueBytes.size + 1)
        System.arraycopy(valueBytes, 0, chunk, valueAt + 4, valueBytes.size)
        return Node(key, false, chunk, 0, value)
    }

    private fun objectChunk(key: String): ByteArray {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val chunk = ByteArray(keyBytes.size + 1)
        System.arraycopy(keyBytes, 0, chunk, 0, keyBytes.size)
        return chunk
    }

    private fun rebuild(d: Doc, root: Node): ByteArray {
        val flat = ArrayList<Node>(d.countB + 16)
        var countA = 0

        fun visit(node: Node, parentAi: Int) {
            node.bi = flat.size
            node.parentAi = parentAi
            flat.add(node)
            if (node.obj) {
                node.ai = countA++
            }
            for (child in node.children) visit(child, node.ai)
        }
        visit(root, -1)

        fun subtree(node: Node): Int {
            var size = 1
            for (child in node.children) size += subtree(child)
            node.subtree = size
            return size
        }
        subtree(root)

        if (countA > 511) throw IllegalStateException("对象节点超过 511 个")

        val blob = java.io.ByteArrayOutputStream(flat.size * 24)
        for (node in flat) {
            while ((blob.size() and 3) != (node.align and 3)) blob.write(0)
            node.offset = blob.size()
            blob.write(node.chunk)
        }
        val blobBytes = blob.toByteArray()

        val aSize = countA * 16
        val bOff = 0x40 + aSize
        val blobOff = bOff + flat.size * 12
        val out = ByteArray(blobOff + blobBytes.size)
        System.arraycopy(d.raw, 0, out, 0, 0x40)

        for (node in flat) {
            if (!node.obj) continue
            val o = 0x40 + node.ai * 16
            putU32(out, o, if (node.parentAi < 0) -1 else node.parentAi)
            putU32(out, o + 4, node.bi)
            putU32(out, o + 8, node.children.size)
            putU32(out, o + 12, node.subtree - 1)
        }

        for (node in flat) {
            val o = bOff + node.bi * 12
            val keyLen = node.key.toByteArray(Charsets.UTF_8).size + 1
            if (keyLen > 511) throw IllegalStateException("键太长了：${node.key.take(32)}")
            val flags = (keyLen shl 2) or
                ((if (node.obj) node.ai else 0) shl 11) or
                (if (node.obj) 1 else 0)
            putU32(out, o, hash(node.key))
            putU32(out, o + 4, node.offset)
            putU32(out, o + 8, flags)
        }

        System.arraycopy(blobBytes, 0, out, blobOff, blobBytes.size)
        putU32(out, 0x10, aSize)
        putU32(out, 0x14, countA)
        putU32(out, 0x18, 0x40)
        putU32(out, 0x2c, flat.size)
        putU32(out, 0x30, bOff)
        putU32(out, 0x38, blobBytes.size)
        putU32(out, 0x3c, blobOff)
        return out
    }

    /** 子项里的 `name` 值就是 project.xml 的 Title */
    private fun titleOf(d: Doc, start: Int, len: Int): String? {
        for (k in 0 until len) {
            if (d.keyAt(start + k) != "name") continue
            return d.valueString(start + k)
        }
        return null
    }

    private fun hash(s: String): Int {
        var h = 0
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            h = h * 53 + (byte.toInt() and 0xff)
        }
        return h
    }

    private class Doc(val raw: ByteArray) {
        val countA = u32(0x14)
        val aOff = u32(0x18)
        val countB = u32(0x2c)
        val bOff = u32(0x30)
        val blobOff = u32(0x3c)

        fun u32(o: Int): Int {
            if (o < 0 || o + 4 > raw.size) return -1
            return (raw[o].toInt() and 0xff) or
                ((raw[o + 1].toInt() and 0xff) shl 8) or
                ((raw[o + 2].toInt() and 0xff) shl 16) or
                ((raw[o + 3].toInt() and 0xff) shl 24)
        }

        fun nodeParent(i: Int) = u32(aOff + i * 16)
        fun nodeName(i: Int) = u32(aOff + i * 16 + 4)
        fun nodeCount(i: Int) = u32(aOff + i * 16 + 8)
        fun nodeSpan(i: Int) = u32(aOff + i * 16 + 12)

        fun entOff(i: Int) = u32(bOff + i * 12 + 4)
        fun entFlags(i: Int) = u32(bOff + i * 12 + 8)
        fun entLen(i: Int) = (entFlags(i) ushr 2) and 0x1ff

        fun chunk(i: Int): ByteArray {
            val s = blobOff + entOff(i)
            val e = if (i + 1 < countB) blobOff + entOff(i + 1) else raw.size
            if (s < 0 || e < s || e > raw.size) {
                throw IllegalStateException("blob 块越界：条目 $i $s..$e")
            }
            return raw.copyOfRange(s, e)
        }

        fun keyAt(i: Int): String {
            val s = blobOff + entOff(i)
            if (s < 0 || s >= raw.size) return ""
            var e = s
            while (e < raw.size && raw[e].toInt() != 0) e++
            return String(raw, s, e - s, Charsets.UTF_8)
        }

        /** 值紧跟在键字符串后面，按 4 字节对齐；字符串值前面是 u32 长度（含结尾 0） */
        fun valueString(i: Int): String? {
            val keyEnd = blobOff + entOff(i) + entLen(i)
            val a = (keyEnd + 3) and 3.inv()
            if (a < 0 || a + 4 > raw.size) return null
            val n = u32(a)
            if (n <= 0 || n > 1 shl 20 || a + 4 + n > raw.size) return null
            return String(raw, a + 4, n - 1, Charsets.UTF_8)
        }
    }

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v ushr 8).toByte()
        b[o + 2] = (v ushr 16).toByte()
        b[o + 3] = (v ushr 24).toByte()
    }
}
