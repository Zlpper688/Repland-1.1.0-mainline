package com.swan1127.repland.data.competition

import com.swan1127.repland.domain.competition.CompetitionHtml
import com.swan1127.repland.domain.competition.CompetitionWebFetcher
import com.swan1127.repland.domain.competition.CompetitionWebResult
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 竞赛资讯联网抓取（debug/internal 构建专属）：GET 公开网页，提标题与正文。
 * 边界（§14）：含 HttpURLConnection，只在含 src/remote 的构建编译，main 反射装载。
 */
class HttpCompetitionWebFetcher : CompetitionWebFetcher {

    override suspend fun fetch(url: String): CompetitionWebResult = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return@withContext CompetitionWebResult(ok = false, url = target, error = "只支持 http/https 链接：$target")
        }
        runCatching {
            val connection = URL(target).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                val status = connection.responseCode
                if (status !in 200..299) {
                    return@withContext CompetitionWebResult(
                        ok = false,
                        url = target,
                        statusCode = status,
                        error = "HTTP $status：页面打不开（可能已改版或下线）",
                    )
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                val head = String(bytes, 0, minOf(bytes.size, HEAD_SNIFF_BYTES), Charsets.ISO_8859_1)
                val charset = CompetitionHtml.resolveCharset(connection.contentType, head)
                val html = String(bytes, charset)
                CompetitionWebResult(
                    ok = true,
                    url = target,
                    finalUrl = connection.url.toString(),
                    statusCode = status,
                    fetchedAt = LocalDateTime.now().format(TIME_FORMAT),
                    title = CompetitionHtml.extractTitle(html),
                    text = CompetitionHtml.extractText(html),
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { failure ->
            CompetitionWebResult(ok = false, url = target, error = "连接失败：${failure.message ?: "网络不可达"}")
        }
    }

    companion object {
        private const val HEAD_SNIFF_BYTES = 2048
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Repland/1.0"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
