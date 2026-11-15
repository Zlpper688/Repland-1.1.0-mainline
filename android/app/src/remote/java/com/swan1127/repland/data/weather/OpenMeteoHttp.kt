package com.swan1127.repland.data.weather

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One bounded HTTPS GET, shared by the two weather adapters.
 *
 * Shaped like the AI transport on purpose: HTTPS only, no redirect, no retry, no logging, and a
 * response cap. The cap matters more here than it does for a chat call — a widget update runs inside
 * a broadcast receiver with the process's patience, and a service that streams forever would be an
 * ANR on the home screen.
 *
 * Returns null rather than throwing. The callers' contract is "say nothing when the sky cannot be
 * reached", so there is no failure to distinguish and nothing to log — least of all a URL, which
 * carries the city the user chose.
 */
internal suspend fun openMeteoGet(url: String): String? = withContext(Dispatchers.IO) {
    val uri = runCatching { URI(url) }.getOrNull()
        ?: return@withContext null
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return@withContext null

    var connection: HttpURLConnection? = null
    try {
        connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        // Far shorter than the AI call's: this answer decorates a schedule, and a slow sky should
        // lose its line for this redraw rather than hold up the widget.
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        if (status !in 200..299) return@withContext null
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) return@withContext null
                output.write(buffer, 0, count)
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }
    } catch (failure: IOException) {
        null
    } catch (failure: SecurityException) {
        null
    } finally {
        connection?.disconnect()
    }
}

private const val MAX_RESPONSE_BYTES = 65_536
