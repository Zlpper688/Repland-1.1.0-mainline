package com.swan1127.repland.domain.competition

import java.nio.charset.Charset

/** 抓回来的一个网页快照：来源与抓取时间如实携带，助手引用时必须报出处。 */
data class CompetitionWebPage(
    val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val fetchedAt: String,
    val title: String,
    val text: String,
)

/** 网络抓取结果：ok=false 时 error 是给模型与用户看的失败原因，不吞异常。 */
data class CompetitionWebResult(
    val ok: Boolean,
    val url: String,
    val finalUrl: String = "",
    val statusCode: Int = 0,
    val fetchedAt: String = "",
    val title: String = "",
    val text: String = "",
    val error: String = "",
)

/**
 * 联网抓取端口：抓一个公开网页并提取标题与正文。
 * 只在含 src/remote 的构建有实现（HttpCompetitionWebFetcher 反射装载）；
 * 正式版没有实现，工具面如实报「本构建没有联网抓取能力」。
 */
interface CompetitionWebFetcher {
    suspend fun fetch(url: String): CompetitionWebResult
}

/**
 * HTML → 纯文本的确定性提取：只做结构剥离与实体解码，不做任何智能猜测。
 * JS 渲染页拿不到正文就返回空串，由调用方如实报告，不编造。
 */
object CompetitionHtml {

    fun extractTitle(html: String): String {
        val raw = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)
            ?: return ""
        return decodeEntities(raw.replace(Regex("(?is)<[^>]+>"), " ")).trim()
    }

    fun extractText(html: String, maxChars: Int = 4000): String {
        val withoutBlocks = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript\\b[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?is)<title\\b[^>]*>.*?</title>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        val withBreaks = withoutBlocks.replace(
            Regex("(?is)<br\\s*/?>|</(p|div|li|tr|td|th|h[1-6]|ul|ol|section|article|table|header|footer)>"),
            "\n",
        )
        val bare = decodeEntities(withBreaks.replace(Regex("(?is)<[^>]+>"), " "))
        var text = bare.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.length > maxChars) text = text.take(maxChars) + "…（正文过长已截断）"
        return text
    }

    /** 编码判定：HTTP 头声明优先，其次页面 meta 声明（国内高校站点大量 GBK/GB2312），默认 UTF-8。 */
    fun resolveCharset(contentType: String?, htmlHead: String): Charset {
        val name = contentType?.let { charsetName(it) } ?: charsetName(htmlHead) ?: "UTF-8"
        return runCatching { Charset.forName(name.trim().uppercase()) }.getOrDefault(Charsets.UTF_8)
    }

    private fun charsetName(text: String): String? =
        Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            code.toChar().toString()
        }
        .replace("&amp;", "&")
}
