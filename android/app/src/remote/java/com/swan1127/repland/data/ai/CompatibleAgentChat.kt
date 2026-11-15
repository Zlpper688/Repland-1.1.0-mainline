package com.swan1127.repland.data.ai

import com.swan1127.repland.domain.model.AgentChatModel
import com.swan1127.repland.domain.model.AgentChatResult
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
 * The group chat's one real network call, in the remote source set like every other
 * transport (builds without remote capability carry no chat transport at all).
 *
 * It differs from the structured advisors in exactly one respect: it asks for **prose**, so
 * it does not validate against a contract schema. What it does instead is strip the
 * decoration models habitually add — a Markdown fence, a leading "好的，", a trailing
 * "希望这对你有帮助" — because that text is shown verbatim in a chat bubble.
 *
 * The one-argument constructor is reflected on by `AppContainer`, hence `@JvmOverloads`.
 */
class CompatibleAgentChat @JvmOverloads constructor(
    private val configRepository: AiProviderConfigRepository,
) : AgentChatModel {

    override suspend fun say(systemPrompt: String, userPrompt: String): AgentChatResult {
        val secret = configRepository.readSecret()
            ?: return fail(AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED)
        if (secret.apiKey.isBlank()) return fail(AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED)

        val uri = runCatching { java.net.URI(secret.baseUrl) }.getOrNull()
            ?: return fail(AiAdvisorFailureReason.TRANSPORT_FAILURE)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) {
            return fail(AiAdvisorFailureReason.TRANSPORT_FAILURE)
        }

        val body = JSONObject()
            .put("model", secret.model)
            .put("temperature", 0.75)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (body.size > 131_072) return fail(AiAdvisorFailureReason.INVALID_RESPONSE)

        return withContext(Dispatchers.IO) {
            val connection = URL(secret.baseUrl.trimEnd('/') + "/chat/completions")
                .openConnection() as HttpURLConnection
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 45_000
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
                    val cleaned = clean(text)
                    if (continuation.isActive) {
                        if (cleaned.isNullOrBlank()) {
                            continuation.resume(fail(AiAdvisorFailureReason.INVALID_RESPONSE))
                        } else {
                            continuation.resume(AgentChatResult.Said(cleaned))
                        }
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) {
                        val reason = (failure as? ProviderHttpFailure)?.reason
                            ?: AiAdvisorFailureReason.TRANSPORT_FAILURE
                        continuation.resume(fail(reason))
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    /**
     * Removes the wrappers a chat bubble must not show. Deliberately conservative: it does
     * not rewrite wording, only surrounding decoration, so a model that answers in an
     * unexpected style still shows up as itself.
     */
    private fun clean(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            // "```json\nbody\n```" → "body". The default keeps the text intact when the
            // model opened a fence and forgot to close it, which is the common typo.
            val withoutOpen = text.removePrefix("```")
            val body = withoutOpen.substringAfter('\n', withoutOpen)
            text = body.substringBeforeLast("```", body).trim()
        }
        text = PREAMBLE.replace(text, "")
        text = SIGN_OFF.replace(text, "")
        return text.trim().take(MAX_CHARS)
    }

    private fun fail(reason: AiAdvisorFailureReason) = AgentChatResult.Failed(reason)

    private companion object {
        /** 600 characters is already four chat bubbles; longer is a monologue. */
        const val MAX_CHARS = 600

        val PREAMBLE = Regex("^(好的|好|收到|当然|没问题|明白)[，,。.!！:：]?\\s*")
        val SIGN_OFF = Regex("(希望(这|以上)[^\\n]*|还有(什么|其他)[^\\n]*吗[？?]?|如需[^\\n]*)\\s*$")
    }
}
