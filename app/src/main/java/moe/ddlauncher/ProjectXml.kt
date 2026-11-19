package moe.ddlauncher

import java.io.File

/**
 * Darkest Dungeon 的 project.xml 读写。
 * 移植层自己用的就是宽松的标签抽取（xmlTag），这里保持一致：
 * 损坏/残缺的 xml 也尽量把能读的字段读出来，而不是直接抛异常。
 */
object ProjectXml {

    private val TAGS_BLOCK = Regex("<Tags>([\\s\\S]*?)</Tags>")
    private val ANY_MARKUP = Regex("<[^>]+>")

    data class Meta(
        val title: String = "",
        val version: String = "",
        val workshopId: String = "",
        val language: String = "",
        val tags: List<String> = emptyList(),
        val description: String = "",
        val previewIcon: String? = null,
        val raw: String = "",
    )

    /** 读文本：兼容 UTF-16（工坊里不少 PC mod 的 project.xml 是 UTF-16）+ 无 BOM 的 UTF-8 */
    fun readText(file: File): String = readText(file.readBytes())

    fun readText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (b0 == 0xEF && b1 == 0xBB && bytes.size > 3) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
        }
        if (bytes.isNotEmpty() && bytes.take(128).count { it == 0.toByte() } > 8) {
            return String(bytes, Charsets.UTF_16LE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /**
     * 逐字段抽取，而不是「匹配任意标签」：
     * 外层 <project> 会把整个文件包住，通用的 <tag>...</tag> 正则会把内容全吞掉。
     */
    private fun tagRaw(text: String, name: String): String =
        Regex("<$name>([\\s\\S]*?)</$name>").find(text)?.groupValues?.get(1).orEmpty()

    private fun tag(text: String, name: String): String = tagRaw(text, name).trim()

    fun meta(text: String): Meta {
        val tags = TAGS_BLOCK.findAll(text)
            .flatMap { it.groupValues[1].split(',', ';').asSequence() }
            .map { unescape(ANY_MARKUP.replace(it, "")).trim() }
            .filter { it.isNotEmpty() && it.length < 48 }
            .distinct()
            .toList()

        val major = tag(text, "VersionMajor")
        val minor = tag(text, "VersionMinor")
        val version = when {
            major.isNotEmpty() && minor.isNotEmpty() -> "$major.$minor"
            major.isNotEmpty() -> major
            else -> ""
        }

        val icon = tag(text, "PreviewIconFile").takeIf { it.isNotEmpty() }

        return Meta(
            // 游戏按 project.xml 里的原始 Title 登记 mod，末尾空格也不能丢；
            // 之前这里 trim() 会让带尾空格的 mod 在存档里匹配不上。
            title = unescape(tagRaw(text, "Title"))
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' '),
            version = version,
            workshopId = tag(text, "PublishedFileId").takeUnless { it == "0" || it.isBlank() } ?: "",
            language = tag(text, "Language"),
            tags = tags,
            description = unescape(tag(text, "ItemDescription")).trim(),
            previewIcon = icon,
            raw = text,
        )
    }

    /** 缺 project.xml 的 mod 由启动器补一个，字段与移植层自己生成的完全一致，保证被识别 */
    fun template(title: String): String {
        val t = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """<?xml version="1.0" encoding="utf-8"?>
<project>
    <PreviewIconFile>preview_icon.png</PreviewIconFile>
    <ItemDescriptionShort></ItemDescriptionShort>
    <ModDataPath></ModDataPath>
    <Title>$t</Title>
    <Language>english</Language>
    <UpdateDetails></UpdateDetails>
    <Visibility>private</Visibility>
    <UploadMode>dont_submit</UploadMode>
    <VersionMajor>1</VersionMajor>
    <VersionMinor>0</VersionMinor>
    <TargetBuild>0</TargetBuild>
    <Tags></Tags>
    <ItemDescription></ItemDescription>
    <PublishedFileId></PublishedFileId>
</project>
"""
    }
}
