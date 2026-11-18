package moe.ddlauncher

/**
 * 识别「数据包是哪个版本的」。
 *
 * 数据包是从移植版 APK 里抽出来的 assets/nx，APK 的 versionName 就是游戏版本
 * （移植者一直这么对齐），所以直接读包里的二进制 AndroidManifest.xml 拿 versionName。
 * 有些人是把 assets/nx 重新压成 zip 的，那种包里没有清单，退回用文件名猜（xxx_2.2.2.zip）。
 */
object PackVersion {

    private val VERSION_RE = Regex("""\d{1,3}(?:\.\d{1,3}){1,3}""")

    /** 整个字符串就是一个版本号（用来在清单的字符串池里兜底找） */
    private val PURE_VERSION_RE = Regex("""^\d{1,3}(?:\.\d{1,3}){1,3}$""")

    /** 统一成 "2.2.2" 这种形态；认不出来就返回 null */
    fun normalize(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return VERSION_RE.find(text)?.value
    }

    /** 文件名里最后一个像版本号的片段：Darkest Dungeon_2.2.2.apk → 2.2.2 */
    fun fromFileName(name: String?): String? {
        val base = name?.trim().orEmpty()
        if (base.isEmpty()) return null
        val stem = base.substringBeforeLast('.', base)
        return normalize(VERSION_RE.findAll(stem).lastOrNull()?.value)
    }

    /**
     * 从二进制 XML（AXML）里取 <manifest android:versionName="...">。
     * 解析失败、找不到、或者值不是 "x.y.z" 形态都返回 null。
     */
    fun fromManifest(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 16) return null
        return try {
            parseManifestVersion(bytes)
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 简版 AXML 解析 ----------------

    private fun parseManifestVersion(b: ByteArray): String? {
        if (u16(b, 0) != 0x0003) return null // 不是 XML chunk
        val headerSize = u16(b, 2)
        val total = u32(b, 4).let { if (it in 0..b.size) it else b.size }

        var pool: List<String> = emptyList()
        var pos = headerSize
        while (pos + 8 <= total) {
            val type = u16(b, pos)
            val chunkSize = u32(b, pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > b.size) break
            when (type) {
                0x0001 -> pool = readStringPool(b, pos)
                0x0102 -> {
                    // start element：chunk 头 8 + lineNumber 4 + comment 4 = 16，
                    // 之后是 ns(4) + name(4) + attributeStart/attributeSize/attributeCount...
                    val ext = pos + u16(b, pos + 2)
                    val nameIdx = u32(b, ext + 4)
                    if (nameIdx in pool.indices && pool[nameIdx] == "manifest") {
                        val direct = normalize(readVersionAttr(b, ext, pool))
                        if (direct != null) return direct
                        // versionName 是指向资源的引用 / 读不出来时，退一步在字符串池里找纯版本号
                        return poolVersion(pool)
                    }
                }
            }
            pos += chunkSize
        }
        return null
    }

    /** 字符串池里第一个「整条就是版本号」的字符串 */
    private fun poolVersion(pool: List<String>): String? =
        pool.firstOrNull { PURE_VERSION_RE.matches(it.trim()) }?.trim()

    /** ext = 属性结构起点（chunk 头之后） */
    private fun readVersionAttr(b: ByteArray, ext: Int, pool: List<String>): String? {
        val attrStart = u16(b, ext + 8)
        val attrSize = u16(b, ext + 10).let { if (it <= 0) 20 else it }
        val count = u16(b, ext + 12)
        val base = ext + attrStart
        for (i in 0 until count) {
            val a = base + i * attrSize
            if (a + 20 > b.size) break
            val nameIdx = u32(b, a + 4)
            val rawIdx = u32(b, a + 8)
            val dataType = u8(b, a + 15)
            val data = u32(b, a + 16)
            val attrName = if (nameIdx in pool.indices) pool[nameIdx] else null
            if (attrName != "versionName") continue
            if (rawIdx in pool.indices) return pool[rawIdx]
            if (dataType == 0x03 && data in pool.indices) return pool[data]
            return null
        }
        return null
    }

    private fun readStringPool(b: ByteArray, chunk: Int): List<String> {
        val headerSize = u16(b, chunk + 2)
        val count = u32(b, chunk + 8)
        if (count < 0 || count > 100_000) return emptyList()
        val flags = u32(b, chunk + 16)
        val stringsStart = u32(b, chunk + 20)
        val utf8 = (flags and 0x100) != 0
        val offsets = chunk + headerSize
        val dataStart = chunk + stringsStart
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = u32(b, offsets + i * 4)
            val p = dataStart + off
            if (p < 0 || p >= b.size) {
                out.add("")
                continue
            }
            out.add(if (utf8) decodeUtf8(b, p) else decodeUtf16(b, p))
        }
        return out
    }

    private fun decodeUtf8(b: ByteArray, start: Int): String {
        var i = start
        var len = u8(b, i)
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or u8(b, i + 1)
            i += 2
        } else {
            i += 1
        }
        var byteLen = u8(b, i)
        if (byteLen and 0x80 != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or u8(b, i + 1)
            i += 2
        } else {
            i += 1
        }
        if (len == 0 || byteLen <= 0) return ""
        val end = (i + byteLen).coerceAtMost(b.size)
        return String(b, i, end - i, Charsets.UTF_8)
    }

    private fun decodeUtf16(b: ByteArray, start: Int): String {
        var i = start
        var len = u16(b, i)
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(b, i + 2)
            i += 4
        } else {
            i += 2
        }
        if (len <= 0) return ""
        val end = (i + len * 2).coerceAtMost(b.size)
        return String(b, i, end - i, Charsets.UTF_16LE)
    }

    private fun u8(b: ByteArray, i: Int): Int = if (i in b.indices) b[i].toInt() and 0xFF else 0

    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun u32(b: ByteArray, i: Int): Int =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
}
