package com.swan1127.repland.data.team

import android.content.Context
import com.swan1127.repland.data.room.TeamSessionDao
import com.swan1127.repland.data.room.TeamSessionEntity
import com.swan1127.repland.data.room.TeamSettingsEntity
import com.swan1127.repland.domain.team.BlockVisibility
import com.swan1127.repland.domain.team.CharterRule
import com.swan1127.repland.domain.team.CharterRuleKind
import com.swan1127.repland.domain.team.CompetitionProfileCard
import com.swan1127.repland.domain.team.ExtraEntry
import com.swan1127.repland.domain.team.FreeWindow
import com.swan1127.repland.domain.team.LedgerEntry
import com.swan1127.repland.domain.team.LedgerKind
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.Objection
import com.swan1127.repland.domain.team.ObjectionResolution
import com.swan1127.repland.domain.team.PersonalBlock
import com.swan1127.repland.domain.team.Phase
import com.swan1127.repland.domain.team.ProfileField
import com.swan1127.repland.domain.team.ProfileSlotGroup
import com.swan1127.repland.domain.team.ScheduleConfidence
import com.swan1127.repland.domain.team.StageKind
import com.swan1127.repland.domain.team.StageMoment
import com.swan1127.repland.domain.team.TaskRank
import com.swan1127.repland.domain.team.TaskTier
import com.swan1127.repland.domain.team.TeamCharter
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamMemoryEntry
import com.swan1127.repland.domain.team.TeamMemoryKind
import com.swan1127.repland.domain.team.TeamNotification
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import com.swan1127.repland.domain.team.TransferRecord
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 团队会话本地持久化（TEAM_COLLAB_DESIGN.md §13/§14）。
 * T3：TeamCollabState 整份快照按 JSON payload 存 Room（一队一行，主副本）；后端同步落地时按表级增量拉取。
 * 枚举一律按 name 序列化，保证前向兼容；未知枚举读取时丢弃该条目而不是崩溃。
 */
object TeamStateCodec {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val DATETIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun toJson(state: TeamCollabState): String = JSONObject().apply {
        put("schema", 1)
        put("teamId", state.teamId)
        put("teamName", state.teamName)
        state.competitionDeadline?.let { put("competitionDeadline", it.format(DATE)) }
        state.profileCard?.let { put("profileCard", profileCard(it)) }
        put("charter", charter(state.charter))
        put("members", JSONArray().apply { state.members.forEach { put(member(it)) } })
        put("phases", JSONArray().apply { state.phases.forEach { put(phase(it)) } })
        put("tasks", JSONArray().apply { state.tasks.forEach { put(task(it)) } })
        put("blocks", JSONArray().apply { state.blocks.forEach { put(block(it)) } })
        put("ledger", JSONArray().apply { state.ledger.forEach { put(ledgerEntry(it)) } })
        put("objections", JSONArray().apply { state.objections.forEach { put(objection(it)) } })
        put("transfers", JSONArray().apply { state.transfers.forEach { put(transfer(it)) } })
        put("notifications", JSONArray().apply { state.notifications.forEach { put(notification(it)) } })
        put("memories", JSONArray().apply { state.memories.forEach { put(memory(it)) } })
        put("archived", state.archived)
        state.archivedAt?.let { put("archivedAt", it.format(DATETIME)) }
    }.toString()

    fun fromJson(text: String, now: () -> LocalDateTime): TeamCollabState? = runCatching {
        val json = JSONObject(text)
        val state = TeamCollabState(
            teamId = json.getString("teamId"),
            teamName = json.getString("teamName"),
            competitionDeadline = json.optString("competitionDeadline", null)?.let { LocalDate.parse(it, DATE) },
            now = now,
        )
        json.optJSONObject("profileCard")?.let { card ->
            state.adoptProfileCard(
                CompetitionProfileCard(
                    competitionName = card.getString("competitionName"),
                    fields = buildMap {
                        card.optJSONObject("fields")?.let { groups ->
                            groups.keys().forEach { groupKey ->
                                runCatching { ProfileSlotGroup.valueOf(groupKey) }.getOrNull()?.let { group ->
                                    put(group, fields(groups.getJSONArray(groupKey)))
                                }
                            }
                        }
                    },
                    extras = card.optJSONArray("extras")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let { e -> ExtraEntry(e.getString("key"), e.getString("value")) }
                        }
                    }.orEmpty(),
                ),
            )
        }
        json.optJSONObject("charter")?.let { state.charter = charter(it) }
        json.optJSONArray("members")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { m -> runCatching { state.addMember(member(m)) } } }
        }
        json.optJSONArray("phases")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { p -> state.phases += phase(p) } }
        }
        json.optJSONArray("tasks")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { t -> runCatching { state.tasks += task(t) } } }
        }
        json.optJSONArray("blocks")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { b -> runCatching { state.blocks += block(b) } } }
        }
        json.optJSONArray("ledger")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { e -> runCatching { state.ledger += ledgerEntry(e) } } }
        }
        json.optJSONArray("objections")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { o -> runCatching { state.objections += objection(o) } } }
        }
        json.optJSONArray("transfers")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { t -> runCatching { state.transfers += transfer(t) } } }
        }
        json.optJSONArray("notifications")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { n -> runCatching { state.notifications += notification(n) } } }
        }
        json.optJSONArray("memories")?.let { arr ->
            (0 until arr.length()).forEach { i -> arr.optJSONObject(i)?.let { m -> runCatching { state.memories += memory(m) } } }
        }
        if (json.optBoolean("archived")) {
            state.archived = true
            state.archivedAt = json.optString("archivedAt", null)?.let { LocalDateTime.parse(it, DATETIME) }
        }
        state
    }.getOrNull()

    // ---------- 序列化 ----------

    private fun charter(c: TeamCharter) = JSONObject().apply {
        put("goal", c.goal)
        put("rules", JSONArray().apply {
            c.rules.forEach { r ->
                put(JSONObject().put("id", r.id).put("text", r.text).put("kind", r.kind.name).put("enabled", r.enabled))
            }
        })
    }

    private fun charter(json: JSONObject): TeamCharter {
        val stored = json.optJSONArray("rules")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { r ->
                    runCatching {
                        CharterRule(
                            id = r.getString("id"),
                            text = r.getString("text"),
                            kind = CharterRuleKind.valueOf(r.getString("kind")),
                            enabled = r.optBoolean("enabled", true),
                        )
                    }.getOrNull()
                }
            }
        }.orEmpty()
        // LOCKED 保守底线以代码为准：存档无法抹掉或改写它，只能叠加 TOGGLE/CUSTOM。
        val locked = TeamCharter.defaultRules().filter { it.kind == CharterRuleKind.LOCKED }
        return TeamCharter(
            goal = json.optString("goal", ""),
            rules = locked + stored.filter { it.kind != CharterRuleKind.LOCKED },
        )
    }

    private fun profileCard(card: CompetitionProfileCard) = JSONObject().apply {
        put("competitionName", card.competitionName)
        put("fields", JSONObject().apply {
            card.fields.forEach { (group, list) ->
                put(group.name, JSONArray().apply {
                    list.forEach { f -> put(JSONObject().put("key", f.key).put("label", f.label).put("value", f.value ?: "")) }
                })
            }
        })
        put("extras", JSONArray().apply {
            card.extras.forEach { e -> put(JSONObject().put("key", e.key).put("value", e.value)) }
        })
    }

    private fun fields(arr: JSONArray): List<ProfileField> =
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { f ->
                ProfileField(f.optString("key"), f.optString("label"), f.optString("value", "").ifBlank { null })
            }
        }

    private fun member(m: MemberCard) = JSONObject().apply {
        put("memberId", m.memberId)
        put("name", m.name)
        put("isCaptain", m.isCaptain)
        m.roleTag?.let { put("roleTag", it) }
        put("responsibility", m.responsibility)
        m.profileRef?.let { put("profileRef", it) }
        put("permissions", JSONArray().apply { m.permissions.forEach { put(it.name) } })
        put("availability", JSONObject().apply {
            m.weeklyAvailability.forEach { (day, windows) ->
                put(day.name, JSONArray().apply {
                    windows.forEach { w -> put(JSONObject().put("s", w.startMinute).put("e", w.endMinute)) }
                })
            }
        })
        put("scheduleConfidence", m.scheduleConfidence.name)
        put("dailyCapacityHours", m.dailyCapacityHours)
    }

    private fun member(m: JSONObject): MemberCard {
        val permissions = m.optJSONArray("permissions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> runCatching { TeamPermission.valueOf(arr.getString(i)) }.getOrNull() }.toSet()
        }.orEmpty()
        val availability = buildMap {
            m.optJSONObject("availability")?.let { days ->
                days.keys().forEach { key ->
                    runCatching { DayOfWeek.valueOf(key) }.getOrNull()?.let { day ->
                        val windows = days.getJSONArray(key).let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optJSONObject(i)?.let { w -> FreeWindow(w.optInt("s"), w.optInt("e")) }
                            }
                        }
                        if (windows.isNotEmpty()) put(day, windows)
                    }
                }
            }
        }
        return MemberCard(
            memberId = m.getString("memberId"),
            name = m.getString("name"),
            isCaptain = m.optBoolean("isCaptain"),
            roleTag = m.optString("roleTag", null),
            responsibility = m.optString("responsibility", ""),
            profileRef = m.optString("profileRef", null),
            permissions = permissions,
            weeklyAvailability = availability,
            scheduleConfidence = runCatching { ScheduleConfidence.valueOf(m.optString("scheduleConfidence", "HIGH")) }
                .getOrDefault(ScheduleConfidence.HIGH),
            dailyCapacityHours = m.optDouble("dailyCapacityHours", 3.0),
        )
    }

    private fun phase(p: Phase) = JSONObject().apply {
        put("phaseId", p.phaseId); put("name", p.name)
        put("start", p.start.format(DATE)); put("end", p.end.format(DATE))
        if (p.kind != StageKind.CUSTOM) put("kind", p.kind.name)
        if (p.deliverables.isNotEmpty()) put("deliverables", stringArray(p.deliverables))
        if (p.checkpoints.isNotEmpty()) put("checkpoints", stringArray(p.checkpoints))
        if (p.moments.isNotEmpty()) {
            put(
                "moments",
                JSONArray().apply {
                    p.moments.forEach { moment ->
                        put(
                            JSONObject()
                                .put("label", moment.label)
                                .put("offsetDays", moment.offsetDays)
                                .put("hard", moment.hard),
                        )
                    }
                },
            )
        }
        if (p.isGate) put("isGate", true)
    }

    private fun phase(p: JSONObject) = Phase(
        phaseId = p.getString("phaseId"),
        name = p.getString("name"),
        start = LocalDate.parse(p.getString("start"), DATE),
        end = LocalDate.parse(p.getString("end"), DATE),
        kind = runCatching { StageKind.valueOf(p.optString("kind", StageKind.CUSTOM.name)) }
            .getOrDefault(StageKind.CUSTOM),
        deliverables = strings(p.optJSONArray("deliverables")),
        checkpoints = strings(p.optJSONArray("checkpoints")),
        moments = moments(p.optJSONArray("moments")),
        isGate = p.optBoolean("isGate", false),
    )

    private fun moments(arr: JSONArray?): List<StageMoment> =
        arr?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val item = a.optJSONObject(i) ?: return@mapNotNull null
                val label = item.optString("label", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                StageMoment(
                    label = label,
                    offsetDays = item.optInt("offsetDays", 0).coerceAtLeast(0),
                    hard = item.optBoolean("hard", false),
                )
            }
        }.orEmpty()

    private fun stringArray(values: List<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }

    private fun strings(arr: JSONArray?): List<String> =
        arr?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, null)?.takeIf(String::isNotBlank) } }.orEmpty()

    private fun task(t: TeamTask) = JSONObject().apply {
        put("taskId", t.taskId); put("title", t.title); put("tier", t.tier.name)
        t.phaseId?.let { put("phaseId", it) }
        put("estimatedHours", t.estimatedHours); put("rank", t.rank.name); put("status", t.status.name)
        t.assignedTo?.let { put("assignedTo", it) }
        put("dependsOn", JSONArray().apply { t.dependsOn.forEach { put(it) } })
        t.hardDeadline?.let { put("hardDeadline", it.format(DATE)) }
        t.deadlineHour?.let { put("deadlineHour", it) }
        t.computedDeadline?.let { put("computedDeadline", it.format(DATE)) }
        put("isBaseline", t.isBaseline); put("createdBy", t.createdBy)
    }

    private fun task(t: JSONObject) = TeamTask(
        taskId = t.getString("taskId"),
        title = t.getString("title"),
        tier = runCatching { TaskTier.valueOf(t.getString("tier")) }.getOrDefault(TaskTier.MID_TERM),
        phaseId = t.optString("phaseId", null),
        estimatedHours = t.optDouble("estimatedHours", 1.0),
        rank = runCatching { TaskRank.valueOf(t.optString("rank", "SHOULD")) }.getOrDefault(TaskRank.SHOULD),
        status = runCatching { TeamTaskStatus.valueOf(t.optString("status", "NOT_STARTED")) }.getOrDefault(TeamTaskStatus.NOT_STARTED),
        assignedTo = t.optString("assignedTo", null),
        dependsOn = t.optJSONArray("dependsOn")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty(),
        hardDeadline = t.optString("hardDeadline", null)?.let { LocalDate.parse(it, DATE) },
        deadlineHour = if (t.has("deadlineHour") && !t.isNull("deadlineHour")) t.optInt("deadlineHour") else null,
        isBaseline = t.optBoolean("isBaseline", true),
        createdBy = t.optString("createdBy", ""),
    )

    private fun block(b: PersonalBlock) = JSONObject().apply {
        put("blockId", b.blockId); put("memberId", b.memberId); put("date", b.date.format(DATE))
        put("s", b.startMinute); put("e", b.endMinute)
        b.sourceTeamId?.let { put("sourceTeamId", it) }
        b.taskId?.let { put("taskId", it) }
        put("visibility", b.visibility.name)
        b.label?.let { put("label", it) }
        if (b.countsTowardCapacity) put("countsTowardCapacity", true)
    }

    private fun block(b: JSONObject) = PersonalBlock(
        blockId = b.getString("blockId"),
        memberId = b.getString("memberId"),
        date = LocalDate.parse(b.getString("date"), DATE),
        startMinute = b.optInt("s"),
        endMinute = b.optInt("e"),
        sourceTeamId = b.optString("sourceTeamId", null),
        taskId = b.optString("taskId", null),
        visibility = runCatching { BlockVisibility.valueOf(b.optString("visibility", "TEAM_VISIBLE")) }
            .getOrDefault(BlockVisibility.TEAM_VISIBLE),
        label = b.optString("label", null),
        countsTowardCapacity = b.optBoolean("countsTowardCapacity", false),
    )

    // internal：个人域 HumanProfileCodec 复用台账序列化（§12 个人域沉淀最终台账）
    internal fun ledgerEntry(e: LedgerEntry) = JSONObject().apply {
        put("entryId", e.entryId); put("kind", e.kind.name)
        e.taskId?.let { put("taskId", it) }
        put("memberId", e.memberId); put("at", e.at.format(DATETIME)); put("evidence", e.evidence)
        e.externalUrl?.let { put("externalUrl", it) }
        e.amountCents?.let { put("amountCents", it) }
        put("isBaseline", e.isBaseline)
    }

    internal fun ledgerEntry(e: JSONObject) = LedgerEntry(
        entryId = e.getString("entryId"),
        kind = runCatching { LedgerKind.valueOf(e.getString("kind")) }.getOrDefault(LedgerKind.entries.first()),
        taskId = e.optString("taskId", null),
        memberId = e.getString("memberId"),
        at = LocalDateTime.parse(e.getString("at"), DATETIME),
        evidence = e.getString("evidence"),
        externalUrl = e.optString("externalUrl", null),
        amountCents = if (e.has("amountCents") && !e.isNull("amountCents")) e.optLong("amountCents") else null,
        isBaseline = e.optBoolean("isBaseline", true),
    )

    private fun objection(o: Objection) = JSONObject().apply {
        put("objectionId", o.objectionId); put("taskId", o.taskId); put("raisedBy", o.raisedBy)
        put("reason", o.reason); put("at", o.at.format(DATETIME)); put("resolution", o.resolution.name)
        o.resolvedBy?.let { put("resolvedBy", it) }
    }

    private fun objection(o: JSONObject) = Objection(
        objectionId = o.getString("objectionId"),
        taskId = o.getString("taskId"),
        raisedBy = o.getString("raisedBy"),
        reason = o.getString("reason"),
        at = LocalDateTime.parse(o.getString("at"), DATETIME),
        resolution = runCatching { ObjectionResolution.valueOf(o.optString("resolution", "PENDING")) }
            .getOrDefault(ObjectionResolution.PENDING),
        resolvedBy = o.optString("resolvedBy", null),
    )

    private fun transfer(t: TransferRecord) = JSONObject().apply {
        put("transferId", t.transferId); put("taskId", t.taskId)
        put("fromMemberId", t.fromMemberId); put("toMemberId", t.toMemberId); put("byMemberId", t.byMemberId)
        put("at", t.at.format(DATETIME))
        t.objectionId?.let { put("objectionId", it) }
    }

    private fun transfer(t: JSONObject) = TransferRecord(
        transferId = t.getString("transferId"),
        taskId = t.getString("taskId"),
        fromMemberId = t.getString("fromMemberId"),
        toMemberId = t.getString("toMemberId"),
        byMemberId = t.getString("byMemberId"),
        at = LocalDateTime.parse(t.getString("at"), DATETIME),
        objectionId = t.optString("objectionId", null),
    )

    // T5 §12：团队域记忆（旧档无该字段照常读回）
    private fun memory(m: TeamMemoryEntry) = JSONObject().apply {
        put("memoryId", m.memoryId); put("kind", m.kind.name); put("content", m.content)
        put("authorId", m.authorId); put("at", m.at.format(DATETIME))
    }

    private fun memory(m: JSONObject) = TeamMemoryEntry(
        memoryId = m.getString("memoryId"),
        kind = runCatching { TeamMemoryKind.valueOf(m.getString("kind")) }.getOrDefault(TeamMemoryKind.PROFILE),
        content = m.getString("content"),
        authorId = m.optString("authorId", ""),
        at = LocalDateTime.parse(m.getString("at"), DATETIME),
    )

    private fun notification(n: TeamNotification) = JSONObject().apply {
        put("notificationId", n.notificationId); put("channel", n.channel.name)
        put("audience", n.audience); put("message", n.message); put("at", n.at.format(DATETIME))
        // T4c：卡片元数据（可缺省，旧档无该字段照常读回）
        n.card?.let { c ->
            put(
                "card",
                JSONObject().put("cardKind", c.cardKind).put("refId", c.refId).put("revision", c.revision),
            )
        }
    }

    private fun notification(n: JSONObject) = TeamNotification(
        notificationId = n.getString("notificationId"),
        channel = com.swan1127.repland.domain.team.NotificationChannel.valueOf(n.optString("channel", "APP_ONLY")),
        audience = n.getString("audience"),
        message = n.getString("message"),
        at = LocalDateTime.parse(n.getString("at"), DATETIME),
        card = n.optJSONObject("card")?.let { c ->
            com.swan1127.repland.domain.team.NotificationCardRef(
                cardKind = c.optString("cardKind"),
                refId = c.optString("refId"),
                revision = c.optInt("revision", 1),
            )
        },
    )
}

/** UI 订阅用的不可变快照：TeamCollabState 是可变聚合，靠 revision 驱动重组。 */
data class TeamSnapshot(val state: TeamCollabState, val revision: Long)

/** 队伍选择器条目：不解析 payload 就能列出多队伍（§16 多队）。 */
data class TeamSummary(val teamId: String, val teamName: String, val updatedAtEpochMillis: Long)

/**
 * 团队会话本地持久化（TEAM_COLLAB_DESIGN.md §13，T3）。
 * 本地 Room 是主副本：一队一行整份快照（TeamStateCodec schema 1），激活指针存 team_settings。
 * 兼容：首次 load 时把 T2 落盘的 team-session.json 整份导入 Room（原文件保留作备份）；
 * 激活指针失效或 payload 损坏时回退到最近更新的队伍，绝不因坏档崩溃。
 */
class TeamSessionStore internal constructor(
    private val dao: TeamSessionDao,
    private val legacyFile: File?,
) {
    constructor(dao: TeamSessionDao, context: Context) : this(
        dao,
        File(context.applicationContext.filesDir, "team-session.json"),
    )

    private val mutex = Mutex()
    private val mutable = MutableStateFlow<TeamSnapshot?>(null)
    val snapshot = mutable.asStateFlow()
    private val mutableTeams = MutableStateFlow<List<TeamSummary>>(emptyList())
    val teams = mutableTeams.asStateFlow()
    private var revision = 0L

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (mutable.value == null) {
                importLegacySnapshotLocked()
                mutable.value = resolveActiveSnapshotLocked()
            }
            refreshTeamsLocked()
        }
    }

    /** 保存 = 写回当前队伍行 + 把激活指针指向它（新建/演示队伍因此自动成为当前队伍）。 */
    suspend fun save(state: TeamCollabState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val previous = dao.get(state.teamId)
            dao.upsert(
                TeamSessionEntity(
                    teamId = state.teamId,
                    teamName = state.teamName,
                    payload = TeamStateCodec.toJson(state),
                    createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                ),
            )
            dao.putSetting(TeamSettingsEntity(ACTIVE_KEY, state.teamId, now))
            revision += 1
            mutable.value = TeamSnapshot(state, revision)
            refreshTeamsLocked()
        }
    }

    /** 按 teamId 读整份状态但不改变激活指针；个人侧回传完成时用。 */
    suspend fun read(teamId: String): TeamCollabState? = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutable.value?.state?.takeIf { it.teamId == teamId }
                ?: dao.get(teamId)?.let { TeamStateCodec.fromJson(it.payload) { LocalDateTime.now() } }
        }
    }

    /** §13 表级同步：云端快照导入并落库（服务器赢语义）；解析失败返回 null。激活指针随之切到该队伍。 */
    suspend fun importSnapshot(payload: String): TeamCollabState? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = TeamStateCodec.fromJson(payload) { LocalDateTime.now() } ?: return@withContext null
            val now = System.currentTimeMillis()
            val previous = dao.get(state.teamId)
            dao.upsert(
                TeamSessionEntity(
                    teamId = state.teamId,
                    teamName = state.teamName,
                    payload = payload,
                    createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                ),
            )
            dao.putSetting(TeamSettingsEntity(ACTIVE_KEY, state.teamId, now))
            revision += 1
            mutable.value = TeamSnapshot(state, revision)
            refreshTeamsLocked()
            state
        }
    }

    /** 读出全部队伍（不改变激活指针）：队友名册启动回填用；坏档跳过。 */
    suspend fun readAllStates(): List<TeamCollabState> = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.getAll().mapNotNull { TeamStateCodec.fromJson(it.payload) { LocalDateTime.now() } }
        }
    }

    /** 多队切换（§16）：加载目标队伍并把激活指针写回；目标不存在或坏档返回 false。 */
    suspend fun switchTo(teamId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entity = dao.get(teamId) ?: return@withLock false
            val state = TeamStateCodec.fromJson(entity.payload) { LocalDateTime.now() } ?: return@withLock false
            val now = System.currentTimeMillis()
            dao.putSetting(TeamSettingsEntity(ACTIVE_KEY, teamId, now))
            revision += 1
            mutable.value = TeamSnapshot(state, revision)
            refreshTeamsLocked()
            true
        }
    }

    /** 删除当前激活队伍（其余队伍不受影响）；随后回退到最近更新的队伍或空。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutable.value?.state?.teamId?.let { dao.delete(it) }
            mutable.value = resolveActiveSnapshotLocked()
            refreshTeamsLocked()
        }
    }

    // ---------- 内部：都在 mutex 保护下调用 ----------

    /** T2 JSON 快照升级进 Room（一次性；同 teamId 以 Room 为准，原文件保留）。 */
    private suspend fun importLegacySnapshotLocked() {
        val file = legacyFile ?: return
        if (!file.exists()) return
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        val state = TeamStateCodec.fromJson(text) { LocalDateTime.now() } ?: return
        if (dao.get(state.teamId) != null) return
        val now = System.currentTimeMillis()
        dao.upsert(
            TeamSessionEntity(
                teamId = state.teamId,
                teamName = state.teamName,
                payload = TeamStateCodec.toJson(state),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    private suspend fun resolveActiveSnapshotLocked(): TeamSnapshot? {
        val activeId = dao.getSetting(ACTIVE_KEY)?.activeTeamId
        val candidates = buildList {
            activeId?.let { add(it) }
            dao.getAll().firstOrNull()?.teamId?.let { add(it) }
        }.distinct()
        for (teamId in candidates) {
            val entity = dao.get(teamId) ?: continue
            val state = TeamStateCodec.fromJson(entity.payload) { LocalDateTime.now() } ?: continue
            if (teamId != activeId) {
                dao.putSetting(TeamSettingsEntity(ACTIVE_KEY, teamId, System.currentTimeMillis()))
            }
            revision += 1
            return TeamSnapshot(state, revision)
        }
        return null
    }

    private suspend fun refreshTeamsLocked() {
        mutableTeams.value = dao.getAll().map { TeamSummary(it.teamId, it.teamName, it.updatedAtEpochMillis) }
    }

    private companion object {
        const val ACTIVE_KEY = "active"
    }
}

/** T2 演示种子（与 domain/team 端到端测试同一支队伍的形状），日期全部相对今天生成。 */
object TeamDemoSeed {

    const val DEMO_TEAM_ID = "t-demo"

    fun build(today: LocalDate = LocalDate.now()): TeamCollabState {
        val monday = today.with(DayOfWeek.MONDAY)
        val deadline = monday.plusWeeks(6)
        val now = { LocalDateTime.now() }
        val team = TeamCollabState.create(
            teamId = DEMO_TEAM_ID,
            teamName = "互联网+校赛小队",
            competitionDeadline = deadline,
            captain = MemberCard("m-captain", "阿岚", roleTag = "队长"),
            now = now,
        )
        team.addMember(
            MemberCard(
                "m-ke", "小柯", roleTag = "技术负责",
                responsibility = "负责原型开发与技术实现", weeklyAvailability = evenings(),
            ),
        )
        // 阿橙没导入课表 → 低置信度（§5：照排但保守）
        team.addMember(MemberCard("m-orange", "阿橙", roleTag = "文案", responsibility = "负责项目书初稿与文案"))
        team.addMember(
            MemberCard(
                "m-bai", "老白", roleTag = "数据",
                responsibility = "负责市场调研与数据整理", weeklyAvailability = evenings(),
            ),
        )
        team.addMember(
            MemberCard(
                "m-man", "小满", roleTag = "设计",
                responsibility = "负责路演PPT与视觉设计", weeklyAvailability = evenings(),
            ),
        )
        team.adoptProfileCard(
            CompetitionProfileCard(
                competitionName = "中国国际大学生创新大赛校赛",
                fields = mapOf(
                    ProfileSlotGroup.BASIC_INFO to listOf(
                        ProfileField("signup_deadline", "报名截止", today.plusDays(10).toString()),
                        ProfileField("member_limit", "人数上限", "5"),
                        ProfileField("organizer", "主办方", "教务处"),
                    ),
                    ProfileSlotGroup.TIMELINE to listOf(
                        ProfileField("school_final", "校赛终审", deadline.toString()),
                    ),
                    ProfileSlotGroup.DELIVERABLES to listOf(
                        ProfileField("proposal", "项目书", null),
                    ),
                ),
                extras = listOf(ExtraEntry("答辩时长", "8 分钟路演 + 5 分钟问答")),
            ),
        )
        team.setBaseline(
            "m-captain",
            listOf(
                Phase("phase_early", "前期", monday, monday.plusDays(5), kind = StageKind.PREP),
                Phase("phase_mid", "中期", monday.plusDays(6), monday.plusDays(26), kind = StageKind.BUILD),
                Phase(
                    "phase_late", "后期", monday.plusDays(27), deadline.minusDays(1),
                    kind = StageKind.WRITE,
                    deliverables = listOf("项目书", "路演PPT", "提交材料"),
                    checkpoints = listOf("提交前核对格式"),
                    isGate = true,
                ),
            ),
            listOf(
                TeamTask("market", "市场调研", TaskTier.MID_TERM, "phase_early", estimatedHours = 8.0, createdBy = "m-captain"),
                TeamTask("proposal", "项目书初稿", TaskTier.MID_TERM, "phase_mid", estimatedHours = 12.0, dependsOn = listOf("market"), createdBy = "m-captain"),
                TeamTask("prototype", "原型开发", TaskTier.MID_TERM, "phase_mid", estimatedHours = 16.0, dependsOn = listOf("market"), createdBy = "m-captain"),
                TeamTask("pitch", "路演PPT", TaskTier.MID_TERM, "phase_late", estimatedHours = 8.0, dependsOn = listOf("proposal", "prototype"), createdBy = "m-captain"),
                TeamTask(
                    "submit", "提交材料", TaskTier.MID_TERM, "phase_late", estimatedHours = 4.0,
                    rank = TaskRank.MUST, dependsOn = listOf("pitch"), hardDeadline = deadline.minusDays(1), deadlineHour = 18, createdBy = "m-captain",
                ),
                TeamTask("kickoff", "启动会", TaskTier.SHORT_TERM, estimatedHours = 1.0, createdBy = "m-captain"),
            ),
        )
        // 小满的跨队软块 + 私有忙块：验证冲突标红与「在忙」斜纹（§8）
        listOf(2, 4).forEach { offset ->
            val date = monday.plusDays(offset.toLong())
            team.blocks += PersonalBlock("blk-x-$offset", "m-man", date, 19 * 60, 21 * 60, "t-another", null, BlockVisibility.TEAM_VISIBLE, label = "他队例会")
            team.blocks += PersonalBlock("blk-p-$offset", "m-man", date.plusDays(1), 19 * 60, 20 * 60, null, null, BlockVisibility.PRIVATE_BUSY, label = "私事")
        }
        return team
    }

    private fun evenings() = mapOf(
        DayOfWeek.MONDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.TUESDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.WEDNESDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.THURSDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.FRIDAY to listOf(FreeWindow(18 * 60, 22 * 60)),
        DayOfWeek.SATURDAY to listOf(FreeWindow(8 * 60, 22 * 60)),
        DayOfWeek.SUNDAY to listOf(FreeWindow(8 * 60, 22 * 60)),
    )
}
