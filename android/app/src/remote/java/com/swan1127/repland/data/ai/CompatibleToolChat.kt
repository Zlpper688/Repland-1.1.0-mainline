package com.swan1127.repland.data.ai

import com.swan1127.repland.domain.model.AgentChatMessage
import com.swan1127.repland.domain.model.AgentToolCall
import com.swan1127.repland.domain.model.AgentTurn
import com.swan1127.repland.domain.model.AgentTurnModel
import com.swan1127.repland.domain.model.AiAdvisorFailureReason
import com.swan1127.repland.domain.ports.AiProviderConfigRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The tool-calling transport for the agent conversation.
 *
 * The planner replies in a single-line JSON envelope — `{"say":…,"tool":{…}}` — rather than
 * via native function calling, because the BYOK endpoint is whatever the user configured
 * and there is no guarantee it implements OpenAI `tools`. The envelope is parsed leniently:
 * a fenced, prose-prefixed, or entirely plain answer still resolves (plain text becomes a
 * say-only turn), so a weaker model degrades to prose instead of failing the whole turn.
 *
 * The one-argument constructor is reflected on by `AppContainer`, hence `@JvmOverloads`.
 */
class CompatibleToolChat @JvmOverloads constructor(
    private val configRepository: AiProviderConfigRepository,
) : AgentTurnModel {

    /** 只有真正读出非空密钥才算 AI 可用；否则界面如实标注「本地规则」。 */
    override suspend fun ready(): Boolean =
        runCatching { configRepository.readSecret()?.apiKey?.isNotBlank() == true }.getOrDefault(false)

    override suspend fun complete(messages: List<AgentChatMessage>): AgentTurn {
        val secret = configRepository.readSecret()
            ?: return AgentTurn.Failed(AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED)
        if (secret.apiKey.isBlank()) return AgentTurn.Failed(AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED)

        val uri = runCatching { java.net.URI(secret.baseUrl) }.getOrNull()
            ?: return AgentTurn.Failed(AiAdvisorFailureReason.TRANSPORT_FAILURE)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) {
            return AgentTurn.Failed(AiAdvisorFailureReason.TRANSPORT_FAILURE)
        }

        val body = JSONObject()
            .put("model", secret.model)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { message ->
                        put(JSONObject().put("role", message.role).put("content", message.content))
                    }
                },
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (body.size > 131_072) return AgentTurn.Failed(AiAdvisorFailureReason.INVALID_RESPONSE)

        return withContext(Dispatchers.IO) {
            val connection = URL(secret.baseUrl.trimEnd('/') + "/chat/completions")
                .openConnection() as HttpURLConnection
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 60_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Authorization", "Bearer ${secret.apiKey}")
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    connection.outputStream.use { it.write(body) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val detail = connection.readProviderRejection()
                        logProviderRejection(secret.baseUrl, code, detail, secret.apiKey.length)
                        throw ProviderHttpFailure(
                            when (code) {
                                401, 403 -> AiAdvisorFailureReason.AUTHENTICATION_FAILURE
                                429 -> AiAdvisorFailureReason.RATE_LIMITED
                                in 500..599 -> AiAdvisorFailureReason.REMOTE_FAILURE
                                else -> AiAdvisorFailureReason.TRANSPORT_FAILURE
                            },
                        )
                    }
                    val response = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 262_144) {
                                throw ProviderHttpFailure(AiAdvisorFailureReason.INVALID_RESPONSE)
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    val content = JSONObject(response).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").get("content")
                    val text = when (content) {
                        is String -> content
                        is JSONArray -> (0 until content.length()).joinToString("") {
                            content.getJSONObject(it).getString("text")
                        }
                        else -> throw org.json.JSONException("Missing completion text")
                    }
                    if (continuation.isActive) continuation.resume(parseEnvelope(text))
                } catch (failure: Exception) {
                    if (continuation.isActive) {
                        val reason = (failure as? ProviderHttpFailure)?.reason
                            ?: AiAdvisorFailureReason.TRANSPORT_FAILURE
                        continuation.resume(AgentTurn.Failed(reason))
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    /**
     * Lenient envelope parse. The tool envelope is preferred, but anything that is not
     * recognisable JSON comes back as prose — a model that ignores the protocol must not
     * wedge the loop, it should simply talk.
     */
    private fun parseEnvelope(raw: String): AgentTurn {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val withoutOpen = text.removePrefix("```")
            val body = withoutOpen.substringAfter('\n', withoutOpen)
            text = body.substringBeforeLast("```", body).trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val parsed = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
            if (parsed != null) {
                val say = parsed.optString("say").takeIf { it.isNotBlank() }
                val tool = parsed.optJSONObject("tool")
                val name = tool?.optString("name").orEmpty().trim()
                if (tool != null && name.isNotEmpty()) {
                    val arguments = tool.optJSONObject("arguments")?.toString() ?: "{}"
                    return AgentTurn.Reply(say, AgentToolCall(name, arguments))
                }
                if (say != null) return AgentTurn.Reply(say)
            }
        }
        val prose = text.take(MAX_CHARS)
        return if (prose.isBlank()) AgentTurn.Failed(AiAdvisorFailureReason.INVALID_RESPONSE) else AgentTurn.Reply(prose)
    }

    private companion object {
        const val MAX_CHARS = 600
    }
}
