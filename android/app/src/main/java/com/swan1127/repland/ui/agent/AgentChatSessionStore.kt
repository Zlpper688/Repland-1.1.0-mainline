package com.swan1127.repland.ui.agent

import android.content.Context
import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.AgentChatMessage
import com.swan1127.repland.domain.model.AgentConversationEngine
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.ToolOutcome
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话落盘：关掉软件不等于丢掉这段对话。消息、协议记录、待确认草案、会话内账本和
 * 没发出去的输入一起存；下次打开原样回来，待确认卡仍可继续改、继续确认。
 *
 * 只存最近一段：消息超过 [MAX_ITEMS] 时从最早的丢，协议记录由引擎侧压缩成摘要。
 */
internal data class PersistedAgentSession(
    val items: List<ChatItem>,
    val transcript: List<AgentChatMessage>,
    val pending: List<AgentAction>,
    val nextId: Int,
    val createdTasks: List<AgentAppToolbox.CreatedTask>,
    val scheduledTaskIds: Set<String>,
    val draft: String,
    val lastActiveAtMillis: Long?,
)

internal class AgentChatSessionStore internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "agent-chat-session.json"))

    private val mutex = Mutex()

    suspend fun load(): PersistedAgentSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (!file.exists()) return@runCatching null
                AgentChatSessionCodec.fromJson(file.readText(Charsets.UTF_8))
            }.getOrNull()
        }
    }

    suspend fun save(snapshot: PersistedAgentSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.writeText(AgentChatSessionCodec.toJson(snapshot), Charsets.UTF_8) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { file.delete() } }
    }

    companion object {
        /** 只留最近一段可见消息；更早的已在协议记录里压缩成摘要。 */
        const val MAX_ITEMS = 80
    }
}

internal object AgentChatSessionCodec {
    private const val VERSION = 1

    fun toJson(session: PersistedAgentSession): String = JSONObject()
        .put("version", VERSION)
        .put("lastActiveAt", session.lastActiveAtMillis ?: JSONObject.NULL)
        .put("nextId", session.nextId)
        .put("draft", session.draft)
        .put("items", JSONArray().apply {
            session.items.takeLast(AgentChatSessionStore.MAX_ITEMS).forEach { put(itemToJson(it)) }
        })
        .put("transcript", JSONArray().apply {
            session.transcript.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        })
        .put("pending", JSONArray().apply {
            session.pending.forEach { action ->
                put(
                    JSONObject()
                        .put("id", action.id)
                        .put("toolName", action.toolName)
                        .put("label", action.label)
                        .put("summary", action.summary)
                        .put("argumentsJson", action.argumentsJson),
                )
            }
        })
        .put("createdTasks", JSONArray().apply {
            session.createdTasks.forEach { task ->
                put(
                    JSONObject()
                        .put("taskId", task.taskId)
                        .put("name", task.name)
                        .put("durationMinutes", task.durationMinutes ?: JSONObject.NULL)
                        .put("startHintMinute", task.startHintMinute ?: JSONObject.NULL)
                        .put("category", task.category.name)
                        .put("priority", task.priority.name),
                )
            }
        })
        .put("scheduledTaskIds", JSONArray(session.scheduledTaskIds.toList()))
        .toString()

    fun fromJson(raw: String): PersistedAgentSession? {
        val root = JSONObject(raw)
        if (root.optInt("version") != VERSION) return null
        val items = root.optJSONArray("items")?.let { array ->
            (0 until array.length()).mapNotNull { index -> itemFromJson(array.optJSONObject(index)) }
        }.orEmpty()
        val transcript = root.optJSONArray("transcript")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { line ->
                    val role = line.optString("role")
                    if (role != AgentConversationEngine.ROLE_USER && role != AgentConversationEngine.ROLE_ASSISTANT) {
                        null
                    } else {
                        AgentChatMessage(role, line.optString("content"))
                    }
                }
            }
        }.orEmpty()
        val pending = root.optJSONArray("pending")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { action ->
                    val id = action.optString("id")
                    val toolName = action.optString("toolName")
                    if (id.isBlank() || toolName.isBlank()) {
                        null
                    } else {
                        AgentAction(
                            id = id,
                            toolName = toolName,
                            label = action.optString("label"),
                            summary = action.optString("summary"),
                            argumentsJson = action.optString("argumentsJson", "{}"),
                        )
                    }
                }
            }
        }.orEmpty()
        val createdTasks = root.optJSONArray("createdTasks")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { task ->
                    val taskId = task.optString("taskId")
                    if (taskId.isBlank()) return@let null
                    AgentAppToolbox.CreatedTask(
                        taskId = taskId,
                        name = task.optString("name"),
                        durationMinutes = task.optIntOrNull("durationMinutes"),
                        startHintMinute = task.optIntOrNull("startHintMinute"),
                        category = runCatching { TaskCategory.valueOf(task.optString("category")) }
                            .getOrDefault(TaskCategory.UNSPECIFIED),
                        priority = runCatching { TaskPriority.valueOf(task.optString("priority")) }
                            .getOrDefault(TaskPriority.MEDIUM),
                    )
                }
            }
        }.orEmpty()
        val scheduled = root.optJSONArray("scheduledTaskIds")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
        }.orEmpty()
        return PersistedAgentSession(
            items = items,
            transcript = transcript,
            pending = pending,
            nextId = root.optInt("nextId", 0),
            createdTasks = createdTasks,
            scheduledTaskIds = scheduled,
            draft = root.optString("draft"),
            lastActiveAtMillis = if (root.isNull("lastActiveAt")) null else root.optLong("lastActiveAt"),
        )
    }

    private fun itemToJson(item: ChatItem): JSONObject = when (item) {
        is ChatItem.User -> JSONObject().put("type", "user").put("id", item.id).put("text", item.text)

        is ChatItem.Agent -> JSONObject()
            .put("type", "agent")
            .put("id", item.id)
            .put("agentId", item.agentId)
            .put("text", item.text)
            .put("feedback", item.feedback ?: JSONObject.NULL)

        is ChatItem.Typing -> JSONObject().put("type", "typing").put("id", item.id).put("agentId", item.agentId)

        is ChatItem.ToolCall -> JSONObject()
            .put("type", "tool")
            .put("id", item.id)
            .put("label", item.label)
            .put("argsSummary", item.argsSummary)
            .put("resultSummary", item.resultSummary)
            .put("outcome", item.outcome.name)

        is ChatItem.Adjustment -> JSONObject()
            .put("type", "adjustment")
            .put("id", item.id)
            .put("label", item.label)
            .put("summary", item.summary)
            .put("ok", item.ok)

        is ChatItem.Brief -> JSONObject()
            .put("type", "brief")
            .put("id", item.id)
            .put("date", item.date.toString())
            .put("headline", item.headline)
            .put("evidence", JSONArray(item.evidence))
            .put("freeWindows", JSONArray(item.freeWindows))
            .put("footer", item.footer)
            .put("time", item.time)
            .put("updated", item.updated)

        is ChatItem.Notice -> JSONObject()
            .put("type", "notice")
            .put("id", item.id)
            .put("reason", item.reason.name)
            .put("agentName", item.agentName ?: JSONObject.NULL)

        is ChatItem.DraftRecord -> JSONObject()
            .put("type", "draft")
            .put("id", item.id)
            .put("title", item.title)
            .put("status", item.status.name)
            .put("time", item.time)
            .put("rows", JSONArray().apply {
                item.rows.forEach { row ->
                    put(JSONObject().put("name", row.name).put("detail", row.detail))
                }
            })
    }

    private fun itemFromJson(json: JSONObject?): ChatItem? {
        if (json == null) return null
        val id = json.optString("id")
        if (id.isBlank()) return null
        return when (json.optString("type")) {
            "user" -> ChatItem.User(id, json.optString("text"))
            "agent" -> ChatItem.Agent(
                id = id,
                agentId = json.optString("agentId"),
                text = json.optString("text"),
                feedback = when {
                    json.isNull("feedback") -> null
                    else -> json.optBoolean("feedback")
                },
            )
            // 打字气泡描述的是已经死掉的那一轮，恢复时不再显示。
            "typing" -> null
            "tool" -> ChatItem.ToolCall(
                id = id,
                label = json.optString("label"),
                argsSummary = json.optString("argsSummary"),
                resultSummary = json.optString("resultSummary"),
                outcome = runCatching { ToolOutcome.valueOf(json.optString("outcome")) }
                    .getOrDefault(ToolOutcome.DONE),
            )
            "adjustment" -> ChatItem.Adjustment(
                id = id,
                label = json.optString("label"),
                summary = json.optString("summary"),
                ok = json.optBoolean("ok"),
            )
            "brief" -> ChatItem.Brief(
                id = id,
                date = runCatching { LocalDate.parse(json.optString("date"), DateTimeFormatter.ISO_LOCAL_DATE) }
                    .getOrElse { LocalDate.now() },
                headline = json.optString("headline"),
                evidence = json.optJSONArray("evidence")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty(),
                freeWindows = json.optJSONArray("freeWindows")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty(),
                footer = json.optString("footer"),
                time = json.optString("time"),
                updated = json.optBoolean("updated"),
            )
            "notice" -> ChatItem.Notice(
                id = id,
                reason = runCatching {
                    com.swan1127.repland.domain.model.AiAdvisorFailureReason.valueOf(json.optString("reason"))
                }.getOrDefault(com.swan1127.repland.domain.model.AiAdvisorFailureReason.REMOTE_FAILURE),
                agentName = if (json.isNull("agentName")) null else json.optString("agentName"),
            )
            "draft" -> ChatItem.DraftRecord(
                id = id,
                title = json.optString("title"),
                status = runCatching { DraftStatus.valueOf(json.optString("status")) }
                    .getOrDefault(DraftStatus.CANCELLED),
                time = json.optString("time"),
                rows = json.optJSONArray("rows")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let { row -> DraftRecordRow(row.optString("name"), row.optString("detail")) }
                    }
                }.orEmpty(),
            )
            else -> null
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)
}
