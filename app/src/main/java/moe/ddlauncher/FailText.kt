package moe.ddlauncher

/**
 * 把下载抛出来的英文异常翻译成人话。
 *
 * 上游 workshop-core 里只有 `userVisibleDownloadFailureMessage()`（取最外层非空 message），
 * 界面上就直接冒出 `Unable to download UGC manifest` —— 用户看不出到底卡在哪。
 * 这里按异常链逐条匹配，先说人话，再把原始原因缩到 60 字挂在后面，方便反馈时对照。
 */
object FailText {

    fun of(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        val msgs = chain.mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        val names = chain.map { it.javaClass.simpleName }
        val all = (names + msgs).joinToString(" | ")

        fun hit(vararg keys: String) = keys.any { all.contains(it, ignoreCase = true) }

        val human = when {
            hit("CLEARTEXT") ->
                "被系统的明文 HTTP 限制拦了（Steam 的 CDN 只给 http），检查 network_security_config 是否被改掉"

            hit("Steam CDN request failed: 401", "eresult 401") ->
                "Steam 拒绝下载（401）：这个内容可能需要拥有游戏的 Steam 账号"

            hit("Unable to download UGC manifest") ->
                "拿不到这个 mod 的文件清单（连不上 Steam CDN 节点，或该内容已下架）"

            hit("No CDN servers available", "No CDN download servers available") ->
                "Steam 没给可用的下载节点，稍后再试（偶尔是节点列表接口抽风）"

            hit("Unable to resolve host", "UnknownHostException") ->
                "域名解析失败，网络不通或被污染"

            hit("SSLHandshake", "handshake", "CertPathValidator") ->
                "和 Steam 的加密握手失败（网络中间有干扰），换网络重试"

            hit("SocketTimeout", "timeout", "timed out") ->
                "连接超时，Steam 这会儿不太通，重试或换个网络"

            hit("Socket closed", "Connection reset", "Unexpected end of stream",
                "connection abort", "Broken pipe", "EOF") ->
                "连接被中途掐断（Steam 的 CM 长连接不稳），重试一次通常就好"

            hit("chunk") -> "下载文件块时失败，多半还是网络中断，重试即可"

            hit("No space left", "ENOSPC") -> "手机存储空间不够了"

            hit("Permission denied", "EACCES") -> "没有写 mods 目录的权限，去系统设置里给「所有文件访问」"

            else -> null
        }

        val raw = msgs.firstOrNull()?.take(60)
        return when {
            human == null && raw == null -> t.javaClass.simpleName
            human == null -> raw!!
            raw == null || raw.contains(human) -> human
            else -> "$human（$raw）"
        }
    }
}
