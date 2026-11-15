package com.swan1127.repland.ui.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swan1127.repland.data.team.AccountMeInfo
import com.swan1127.repland.data.team.AuthLoginResult
import com.swan1127.repland.data.team.BackendConfigStore
import com.swan1127.repland.data.team.BackendProbeReport
import com.swan1127.repland.data.team.HumanProfileStore
import com.swan1127.repland.data.team.MemorySearchResult
import com.swan1127.repland.data.team.MemorySyncResult
import com.swan1127.repland.data.team.NoopNotifier
import com.swan1127.repland.data.team.PersonCardStore
import com.swan1127.repland.data.team.SmsCodeResult
import com.swan1127.repland.data.team.TeamDemoSeed
import com.swan1127.repland.data.team.TeamNotifier
import com.swan1127.repland.data.team.TeamRosterStore
import com.swan1127.repland.data.team.TeamSessionStore
import com.swan1127.repland.data.team.TeamSnapshot
import com.swan1127.repland.data.team.TeamStateCodec
import com.swan1127.repland.data.team.TeamSummary
import com.swan1127.repland.data.team.WechatQrStartResult
import com.swan1127.repland.data.competition.CompetitionCatalogStore
import com.swan1127.repland.domain.competition.AwardGrade
import com.swan1127.repland.domain.competition.AwardLevel
import com.swan1127.repland.domain.competition.CompetitionAward
import com.swan1127.repland.domain.competition.CompetitionInstance
import com.swan1127.repland.domain.competition.CompetitionTracking
import com.swan1127.repland.domain.competition.CompetitionTrackStatus
import com.swan1127.repland.domain.competition.CompetitionWebFetcher
import com.swan1127.repland.domain.competition.SchoolCompetitionSeed
import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.AgentActionResult
import com.swan1127.repland.domain.model.AgentChatMessage
import com.swan1127.repland.domain.model.AgentConversationEngine
import com.swan1127.repland.domain.model.AgentConversationEvent
import com.swan1127.repland.domain.model.AgentSessionState
import com.swan1127.repland.domain.model.AgentTurnContext
import com.swan1127.repland.domain.model.AgentTurnModel
import com.swan1127.repland.domain.model.AgentToolSpec
import com.swan1127.repland.domain.model.AiAdvisorFailureReason
import com.swan1127.repland.domain.model.NoOpAgentTurnModel
import com.swan1127.repland.domain.team.CARD_DRAFT_CONFIRM
import com.swan1127.repland.domain.team.CardVisibility
import com.swan1127.repland.domain.team.CompetitionImport
import com.swan1127.repland.domain.team.CompetitionProfileCard
import com.swan1127.repland.domain.team.ConfirmationApplier
import com.swan1127.repland.domain.team.DeadlineEngine
import com.swan1127.repland.domain.team.HumanProfileMemory
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.MemberImport
import com.swan1127.repland.domain.team.NotificationCardRef
import com.swan1127.repland.domain.team.NotificationChannel
import com.swan1127.repland.domain.team.ObjectionResolution
import com.swan1127.repland.domain.team.PersonCard
import com.swan1127.repland.domain.team.PersonCardBook
import com.swan1127.repland.domain.team.PersonCardSync
import com.swan1127.repland.domain.team.Phase
import com.swan1127.repland.domain.team.Placement
import com.swan1127.repland.domain.team.ProfileSlotGroup
import com.swan1127.repland.domain.team.ScheduleConfidence
import com.swan1127.repland.domain.team.TeamCharter
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamReminderDefaults
import com.swan1127.repland.domain.team.TeamMemoryKind
import com.swan1127.repland.domain.team.TeamNotification
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeamPlanDraft
import com.swan1127.repland.domain.team.TeamRosterBook
import com.swan1127.repland.domain.team.TeamScheduler
import com.swan1127.repland.domain.team.TaskTier
import com.swan1127.repland.domain.team.TeamNeed
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import com.swan1127.repland.domain.team.TeammateMatch
import com.swan1127.repland.domain.team.TeammateMatchEngine
import com.swan1127.repland.domain.team.inviteCodeOf
import com.swan1127.repland.domain.team.agents.Liaison
import com.swan1127.repland.domain.team.agents.TeamSchedulerInputFactory
import com.swan1127.repland.domain.team.agents.UpdateKind
import com.swan1127.repland.domain.team.runtime.TeamEvent
import com.swan1127.repland.domain.team.runtime.TeamOutcome
import com.swan1127.repland.domain.team.runtime.TeamRequestKind
import com.swan1127.repland.domain.team.runtime.TeamAgentRuntime
import com.swan1127.repland.domain.team.runtime.TeamMemoryBank
import com.swan1127.repland.domain.team.runtime.TeamPrompt
import com.swan1127.repland.domain.team.runtime.TeamSessionContext
import com.swan1127.repland.domain.team.runtime.TeamToolbox
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TEAM tab 状态_holder（TEAM_COLLAB_DESIGN.md §7/§17 T2）。
 * 纪律沿用（§14）：runtime 只读计算，一切写仓库动作走 TeamCollabState 方法；草案→确认；越权直接报错上浮。
 */
enum class AssistantSource(val label: String) {
    AI("AI 助手"),
    LOCAL("本地规则"),
}

/** 正在进行的动作；界面按类型标注对应按钮，不再共用一个布尔。 */
enum class TeamBusy { SCHEDULING, TALKING, CONFIRMING, REPORTING }

private const val PERSONAL_TIME_TIMEOUT_MILLIS = 5_000L
private const val SESSION_WATCHDOG_MILLIS = 20_000L
/** 外层兜底，必须大于 AgentConversationEngine 的整回合预算（120s）：先让引擎自救（本地兜底），救不住才在这里断。 */
private const val AI_TURN_WATCHDOG_MILLIS = 130_000L

class TeamViewModel(
    private val store: TeamSessionStore,
    private val backendConfigStore: BackendConfigStore? = null,
    private val notifierProvider: () -> TeamNotifier = { NoopNotifier },
    private val humanProfileStore: HumanProfileStore? = null,
    private val personalBridge: com.swan1127.repland.domain.team.TeamPersonalBridge? = null,
    private val planRepository: com.swan1127.repland.domain.ports.PlanRepository? = null,
    private val timeRepository: com.swan1127.repland.domain.ports.TimeRepository? = null,
    agentTurnModel: AgentTurnModel? = null,
    private val rosterStore: TeamRosterStore? = null,
    private val personCardStore: PersonCardStore? = null,
    private val competitionCatalogStore: CompetitionCatalogStore? = null,
    private val competitionTrackingRepository: com.swan1127.repland.domain.ports.CompetitionTrackingRepository? = null,
    private val competitionAwardRepository: com.swan1127.repland.domain.ports.CompetitionAwardRepository? = null,
    private val competitionReminderScheduler: com.swan1127.repland.reminders.CompetitionReminderScheduler? = null,
    /** 竞赛资讯联网抓取（src/remote 反射装载）；正式版没有，工具如实报没有联网能力。 */
    private val competitionWebFetcher: CompetitionWebFetcher? = null,
    /** 用户「关于我」自述（个人资产）；队伍助手也读它，免得换个场景就不认识用户。 */
    private val personaDocProvider: () -> String = { "" },
) : ViewModel() {

    data class UiState(
        val snapshot: TeamSnapshot? = null,
        val teams: List<TeamSummary> = emptyList(),
        val actorId: String? = null,
        val busy: TeamBusy? = null,
        val message: String? = null,
        val outcome: TeamOutcome? = null,
        val events: List<String> = emptyList(),
        val weeklyReport: com.swan1127.repland.domain.team.WeeklyReportDraft? = null,
        // T5 §12 个人域：仅本人可见，跨队复用（换队不重置）
        val humanProfile: HumanProfileMemory? = null,
        /** 跨队队友名册：共同参赛过的人 + 你想了解的人；邀请队员从这里拉人。 */
        val roster: TeamRosterBook = TeamRosterBook(),
        /** 个人资料卡：默认卡恒有；竞赛卡只从真实参赛队伍同步而来。 */
        val cards: PersonCardBook = PersonCardBook.empty(),
        /** 内置竞赛库种子（assets 快照）：换学校 = 换种子文件；null 表示种子不可用。 */
        val competitionSeed: SchoolCompetitionSeed? = null,
        /** 本地竞赛追踪（Room 单一事实源）：截止提醒由它驱动。 */
        val competitionTracking: List<CompetitionTracking> = emptyList(),
        /** 获奖履历（个人线）：只由用户登记，竞赛卡履历不从这里写。 */
        val competitionAwards: List<CompetitionAward> = emptyList(),
        // 团队助手：一轮回复 + 工具轨迹 + 待确认写入（确认后才落库）
        val assistantReply: String? = null,
        val assistantTrace: List<String> = emptyList(),
        val pendingActions: List<AgentAction> = emptyList(),
        val assistantSource: AssistantSource? = null,
        val assistantError: String? = null,
        /** 云端模型是否可用；false 时说事走本地确定性规则，界面如实标注。 */
        val aiAvailable: Boolean = false,
    )

    private val ui = MutableStateFlow(UiState())
    val state = ui.asStateFlow()

    private val runtime = TeamAgentRuntime(memoryBank = TeamMemoryBank())

    /** 云端模型；未配置时用 NoOp，说事直接走本地确定性规则（不冒充 AI）。 */
    private val turnModel: AgentTurnModel = agentTurnModel ?: NoOpAgentTurnModel

    /** 脱离 viewModelScope 的会话作用域：看门狗放弃一轮时能立刻收尾，不必等模型自己退出。 */
    private val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 跨轮保留：协议记录 + 未确认写入；换身份/换队时清空，避免旧身份的动作被新身份确认。 */
    private var agentSession = AgentSessionState()

    /** 身份/队伍代际：换人换队后，旧身份的在途回复与排期结果直接作废，不回写到新视角。 */
    private var sessionEpoch = 0

    /** 队员凭码加入后的身份：换队收集器会把默认视角重置为队长，这里临时指定加入者。 */
    private var pendingActorId: String? = null

    /** 个人域缓存实例（落盘经 humanProfileStore）；未配置 store 时为 null（域内测试/离线降级）。 */
    private var profileMemory: HumanProfileMemory? = null

    /** 个人资料卡缓存（落盘经 personCardStore）；默认卡恒有，竞赛卡只从真实队伍同步。 */
    private var cardBook: PersonCardBook = PersonCardBook.empty()
    private var cardsLoaded = false

    init {
        refreshAiAvailability()
        viewModelScope.launch { store.load() }
        // §13 表级同步：启动即恢复——有本地队伍拉增量，本地没队伍则按账号成员位从云端找回（重装/换机不丢队）
        viewModelScope.launch { pullTeamState() }
        viewModelScope.launch {
            humanProfileStore?.let { profileStore ->
                profileMemory = profileStore.load()
                ui.update { it.copy(humanProfile = profileMemory) }
            }
            refreshPersonCards()
        }
        // 内置竞赛库：assets 种子一次性读入；坏种子静默为 null，界面显示空态而不是崩
        viewModelScope.launch {
            competitionCatalogStore?.defaultSeed()?.let { seed ->
                ui.update { it.copy(competitionSeed = seed) }
            }
        }
        // 竞赛追踪：列表每变一次就全量重摆截止闹钟（取消关注/关提醒立即停响）
        viewModelScope.launch {
            competitionTrackingRepository?.observeAll()?.collect { rows ->
                ui.update { it.copy(competitionTracking = rows) }
                competitionReminderScheduler?.sync(rows)
            }
        }
        // 获奖履历：同一条 Room 流；登记/删除即时反映
        viewModelScope.launch {
            competitionAwardRepository?.observeAll()?.collect { rows ->
                ui.update { it.copy(competitionAwards = rows) }
            }
        }
        // 名册启动回填：先读个人名册，再把本机所有队伍的成员补进来（清队伍也不丢历史队友）
        viewModelScope.launch {
            val store = rosterStore ?: return@launch
            var book = store.load()
            runCatching { this@TeamViewModel.store.readAllStates() }.getOrDefault(emptyList())
                .forEach { state -> book = book.recordTeam(state, rosterSeenAt()) }
            ui.value.snapshot?.state?.let { state -> book = book.recordTeam(state, rosterSeenAt()) }
            val merged = ui.value.roster.merge(book)
            ui.update { it.copy(roster = merged) }
            runCatching { store.save(merged) }
        }
        // 快照与队伍列表都由 store 的 StateFlow 驱动；换队（teamId 变化）时重置为该队队长视角（§10）
        viewModelScope.launch {
            store.snapshot.collect { snapshot ->
                val teamChanged = ui.value.snapshot?.state?.teamId != snapshot?.state?.teamId
                val joinActor = pendingActorId
                if (joinActor != null) pendingActorId = null
                if (teamChanged) {
                    sessionEpoch++
                    agentSession = AgentSessionState()
                }
                ui.update { current ->
                    current.copy(
                        snapshot = snapshot,
                        actorId = when {
                            snapshot == null -> null
                            joinActor != null -> joinActor
                            teamChanged -> snapshot.state.members.firstOrNull { it.isCaptain }?.memberId
                            else -> current.actorId
                        },
                        busy = if (teamChanged) null else current.busy,
                        assistantReply = if (teamChanged) null else current.assistantReply,
                        assistantTrace = if (teamChanged) emptyList() else current.assistantTrace,
                        pendingActions = if (teamChanged) emptyList() else current.pendingActions,
                        assistantSource = if (teamChanged) null else current.assistantSource,
                        assistantError = if (teamChanged) null else current.assistantError,
                    )
                }
                // T4c：换队/首帧到达后立刻拉一次确认记录（§13 拉取起步）
                if (teamChanged && snapshot != null) {
                    pullConfirmations()
                    pullTeamState()
                    viewModelScope.launch { syncPersonal(snapshot.state) }
                }
                // 队员加入/批量导入/建队都经快照回流：名册自动记下「一起参赛过」的人
                snapshot?.let { recordRoster(it.state) }
                if (snapshot != null && (teamChanged || !cardsLoaded)) {
                    viewModelScope.launch { refreshPersonCards() }
                }
            }
        }
        viewModelScope.launch {
            store.teams.collect { teams -> ui.update { it.copy(teams = teams) } }
        }
    }

    /** 云端模型是否真的能接单（提供方已配置且密钥非空）；界面据此如实标注 AI / 本地规则。 */
    fun refreshAiAvailability() {
        viewModelScope.launch {
            val ready = runCatching { turnModel.ready() }.getOrDefault(false)
            ui.update { it.copy(aiAvailable = ready) }
        }
    }

    override fun onCleared() {
        turnScope.cancel()
        super.onCleared()
    }

    val current: TeamCollabState? get() = ui.value.snapshot?.state
    val actor: MemberCard? get() {
        val id = ui.value.actorId ?: return null
        return current?.members?.firstOrNull { it.memberId == id }
    }

    fun clearMessage() = ui.update { it.copy(message = null) }
    fun dismissOutcome() = ui.update { it.copy(outcome = null, events = emptyList()) }

    fun dismissAssistant() = ui.update {
        it.copy(assistantReply = null, assistantTrace = emptyList(), assistantSource = null, assistantError = null)
    }

    // ---------- 建队与生命周期（§16） ----------

    fun createTeam(
        name: String,
        captainName: String,
        deadline: LocalDate?,
        profileCard: CompetitionProfileCard? = null,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val captain = MemberCard("m-captain", captainName.ifBlank { "队长" }, roleTag = "队长")
        val team = TeamCollabState.create(
            teamId = "t-" + UUID.randomUUID().toString().take(8),
            teamName = trimmed,
            competitionDeadline = deadline,
            captain = captain,
        )
        profileCard?.let { team.adoptProfileCard(it) }
        viewModelScope.launch {
            runCatching { saveAndSync(team) }.onSuccess {
                ui.update { it.copy(actorId = captain.memberId, message = "队伍已创建，先完善档案卡与基线") }
            }.onFailure { failure -> ui.update { it.copy(message = failure.message ?: "建队失败") } }
        }
    }

    /** 队员凭队伍码加入：码由 teamId 派生，本机已有队伍即可匹配。 */
    fun joinTeam(code: String, memberName: String) {
        val trimmedCode = code.trim()
        val trimmedName = memberName.trim()
        if (trimmedCode.isEmpty()) {
            ui.update { it.copy(message = "先填队长给你的队伍码") }
            return
        }
        if (trimmedName.isEmpty()) {
            ui.update { it.copy(message = "先填你的名字") }
            return
        }
        val target = ui.value.teams.firstOrNull { inviteCodeOf(it.teamId).equals(trimmedCode, ignoreCase = true) }
        if (target == null) {
            ui.update {
                it.copy(message = "没有找到这个队伍码：跨设备加入需先连接协作后端，本机离线时只能加入这台设备上已有的队伍")
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                val state = store.read(target.teamId) ?: error("队伍不存在或档案已损坏")
                val member = state.joinAsMember(trimmedName)
                pendingActorId = member.memberId
                saveAndSync(state)
                flushNotifications(state)
                flushLedger(state)
                state.teamName to member.name
            }.onSuccess { (teamName, name) ->
                ui.update { it.copy(message = "$name 已加入「$teamName」") }
            }.onFailure { failure ->
                pendingActorId = null
                ui.update { it.copy(message = failure.message ?: "加入队伍失败") }
            }
        }
    }

    fun loadDemo() {
        val demo = TeamDemoSeed.build()
        viewModelScope.launch {
            saveAndSync(demo)
            ui.update { it.copy(actorId = "m-captain", message = "演示队伍已载入") }
        }
    }

    /** 团队域暂以演示数据打底：没有任何真实队伍与快照时自动载入演示种子；建队后走真实数据。 */
    private var demoEnsured = false
    fun ensureDemoTeam() {
        if (demoEnsured) return
        demoEnsured = true
        if (ui.value.snapshot != null) return
        if (store.teams.value.isNotEmpty()) return
        loadDemo()
    }

    fun switchActor(memberId: String) {
        // 换身份就作废旧身份的会话与待确认动作：动作的权限是按发起人判的。
        sessionEpoch++
        agentSession = AgentSessionState()
        ui.update {
            it.copy(
                actorId = memberId,
                busy = null,
                outcome = null,
                assistantReply = null,
                assistantTrace = emptyList(),
                pendingActions = emptyList(),
                assistantSource = null,
                assistantError = null,
            )
        }
        ui.value.snapshot?.let { snapshot -> viewModelScope.launch { syncPersonal(snapshot.state) } }
        viewModelScope.launch { refreshPersonCards() }
    }

    /** 多队切换（§16）：store 换快照，收集器负责重置身份。 */
    fun switchTeam(teamId: String) {
        if (ui.value.snapshot?.state?.teamId == teamId) return
        viewModelScope.launch {
            runCatching { store.switchTo(teamId) }
                .onFailure { failure -> ui.update { it.copy(message = failure.message ?: "切换队伍失败") } }
        }
    }

    // ---------- 落库动作（全部走 TeamCollabState 方法；错误原样上浮） ----------

    private fun mutate(what: String, block: (TeamCollabState) -> Unit): Boolean {
        val snapshot = ui.value.snapshot ?: return false
        // T5 §16：归档后只读——UI 层拦截（域层 guardMutable 双保险）
        if (snapshot.state.archived) {
            ui.update { it.copy(message = "队伍已归档，已转只读") }
            return false
        }
        return runCatching { block(snapshot.state) }
            .onFailure { failure -> ui.update { it.copy(message = "${what}被拒绝：${failure.message}") } }
            .onSuccess {
                // 域对象是就地变更的：先推一版快照让界面立刻反映，落库/同步随后完成
                bumpRevision()
                flushNotifications(snapshot.state)
                flushLedger(snapshot.state)
                viewModelScope.launch { saveAndSync(snapshot.state) }
                pullConfirmations()
                pullTeamState()
            }
            .isSuccess
    }

    /** 域对象就地修改后必须自增 revision，界面才会重算列表。 */
    private fun bumpRevision() {
        ui.update { current ->
            current.snapshot?.let { snap -> current.copy(snapshot = snap.copy(revision = snap.revision + 1)) } ?: current
        }
    }

    // 已投递通知去重（后端幂等之外的第二道防线；封顶防泄漏）
    private val deliveredNotifications = LinkedHashSet<String>()

    /** 已投递台账条目去重（T4c，§5 fire-and-forget 副本；封顶防泄漏）。 */
    private val pushedLedgerIds = LinkedHashSet<String>()

    /**
     * T4c：把新增台账条目投给后端副本（§5 只做副本不做同步）。
     * 投递失败静默——副本允许落后，App 功能不受影响；未配置后端时为纯空转。
     */
    private fun flushLedger(state: TeamCollabState) {
        val config = backendConfigStore ?: return
        if (!config.isConfigured) return
        val sink = config.buildLedgerSink()
        state.ledger
            .filter { pushedLedgerIds.add(it.entryId) }
            .forEach { entry ->
                val memberName = state.members.firstOrNull { it.memberId == entry.memberId }?.name ?: entry.memberId
                viewModelScope.launch {
                    runCatching { sink.push(entry, state.teamId, state.teamName, memberName) }
                }
            }
        if (pushedLedgerIds.size > 512) {
            pushedLedgerIds.take(256).forEach(pushedLedgerIds::remove)
        }
    }

    /**
     * T4a：把本次 mutate 产生的 PUSH_AND_FEISHU 通知投给自托管后端（§2 链路末端）。
     * 未配置后端时 notifier 为 Noop，本函数是纯开销为零的空转（§3 降级）。
     */
    private fun flushNotifications(state: TeamCollabState) {
        state.notifications
            .filter { it.channel == NotificationChannel.PUSH_AND_FEISHU }
            .filter { deliveredNotifications.add(it.notificationId) }
            .forEach { notification ->
                val audienceId = resolveAudienceId(state, notification.audience) ?: return@forEach
                viewModelScope.launch {
                    // 每次经 provider 取：配置面运行时改后端配置后立即生效（T4b）
                    notifierProvider().dispatch(notification, state.teamId, state.teamName, audienceId)
                }
            }
        if (deliveredNotifications.size > 512) {
            deliveredNotifications.take(256).forEach(deliveredNotifications::remove)
        }
    }

    /** 受众解析：App 只上报 memberId，open_id 绑定关系在后端（§2.3）。 */
    private fun resolveAudienceId(state: TeamCollabState, audience: String): String? = when {
        audience == "全员" -> null // 全员广播不在 T4a 范围（现有全员通知均为 APP_ONLY）
        audience == "队长" -> state.members.firstOrNull { it.isCaptain }?.memberId
        else -> state.members.firstOrNull { it.name == audience }?.memberId
            ?: state.members.firstOrNull { it.memberId == audience }?.memberId
    }

    /** 截止日期设定（§7 时间轮盘）：超过比赛截止标红拦截；设定即倒算联动。 */
    fun setDeadline(taskId: String, date: LocalDate, hour: Int?) {
        val snapshot = ui.value.snapshot ?: return
        val task = snapshot.state.tasks.firstOrNull { it.taskId == taskId } ?: return
        val issue = DeadlineEngine.validateDeadlineSetting(taskId, task.title, date, snapshot.state.competitionDeadline)
        if (issue != null) {
            ui.update { it.copy(message = issue.message) }
            return
        }
        mutate("设定截止") { state ->
            state.setDeadlineBy(requireActorId(), taskId, date, hour)
            // 倒算联动：重算全部 computedDeadline（§4 期限 = f(类型, 阶段截止, 依赖位置)）
            val recomputed = DeadlineEngine.recompute(state.tasks, state.phases, state.competitionDeadline)
            recomputed.tasks.forEach { if (it.computedDeadline != null) state.updateTask(it) }
        }
    }

    fun cancelTask(taskId: String) {
        mutate("取消任务") { state -> state.cancelTask(requireActorId(), taskId) }
    }

    /** 撤销取消（划线+可撤销）：WONT → NOT_STARTED，台账留痕。 */
    fun restoreTask(taskId: String) {
        mutate("撤销取消") { state -> state.restoreTaskBy(requireActorId(), taskId) }
    }

    /** 手动报告完成：与助手 report_task_done 共用同一道权限门（TeamState.reportDone）。 */
    fun reportDone(taskId: String, note: String? = null) {
        mutate("报告完成") { state ->
            val changed = state.reportDone(requireActorId(), taskId, note?.takeIf { it.isNotBlank() })
            if (!changed) ui.update { it.copy(message = "这项任务本来就已完成") }
        }
    }

    fun transferTask(taskId: String, toMemberId: String) {
        mutate("转派") { state -> state.transfer(requireActorId(), taskId, toMemberId) }
    }

    fun raiseObjection(taskId: String, reason: String) {
        mutate("异议") { state -> state.raiseObjection(requireActorId(), taskId, reason.ifBlank { "不同意该转派" }) }
    }

    fun resolveObjection(objectionId: String, keepTransfer: Boolean) {
        mutate("异议裁决") { state -> state.resolveObjection(requireActorId(), objectionId, keepTransfer) }
    }

    fun commitDraft(draft: TeamPlanDraft) {
        val actorId = requireActorId()
        // T4c：UI 已确认的草案移出待确认登记——飞书侧再确认只留提示（§7 卡片确认等价一次 UI 确认操作）
        pendingDrafts.entries.removeAll { it.value === draft }
        mutate("确认草案") { state ->
            state.commitDraft(actorId, draft, replacesTaskIds = draft.placements.map { it.taskId }.toSet())
        }
        dismissOutcome()
        postMeetingCandidates(actorId, draft)
    }

    /** T4c §6：会议候选随草案确认写飞书日历（队长确认后创建；非队长/未配置静默跳过）。 */
    private fun postMeetingCandidates(actorId: String, draft: TeamPlanDraft) {
        if (draft.meetingCandidates.isEmpty()) return
        val config = backendConfigStore ?: return
        if (!config.isConfigured) return
        val actor = actor ?: return
        if (!actor.isCaptain) return
        val state = current ?: return
        val poster = config.buildMeetingPoster()
        viewModelScope.launch {
            draft.meetingCandidates.forEach { slot ->
                runCatching {
                    poster.post(
                        teamId = state.teamId,
                        teamName = state.teamName,
                        title = "会议",
                        startAtMs = slot.date.atTime(slot.startMinute / 60, slot.startMinute % 60)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        endAtMs = slot.date.atTime(slot.endMinute / 60, slot.endMinute % 60)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        attendeeMemberIds = slot.attendeeIds,
                        reminderMinutes = TeamReminderDefaults.leadMinutes,
                    )
                }
            }
        }
    }

    fun updateResponsibility(memberId: String, text: String) {
        mutate("负责说明") { state -> state.updateResponsibility(requireActorId(), memberId, text) }
    }

    fun setPermission(memberId: String, permission: TeamPermission, enabled: Boolean) {
        mutate("权限配置") { state -> state.setMemberPermission(requireActorId(), memberId, permission, enabled) }
    }

    /** 队伍大局与队规：只有队长能改（域层校验）；改完通知全员并立即注入下一轮提示词。 */
    fun updateCharter(charter: TeamCharter) {
        mutate("队伍大局") { state -> state.updateCharter(requireActorId(), charter) }
    }

    /** 加队员：与助手 add_team_member 同一权限门（§16）。 */
    fun addMember(name: String, roleTag: String? = null, responsibility: String = "") {
        if (name.isBlank()) return
        mutate("添加队员") { state ->
            state.addMemberBy(
                requireActorId(),
                MemberCard(
                    memberId = "m-" + UUID.randomUUID().toString().take(8),
                    name = name.trim(),
                    roleTag = roleTag?.trim()?.takeIf { it.isNotEmpty() },
                    responsibility = responsibility.trim(),
                ),
            )
        }
    }

    /** 批量邀请：一行一人粘贴；重名和格式不符的行如实跳过，一次落库、一次刷新。 */
    fun importMembers(text: String) {
        val parsed = MemberImport.parse(text)
        if (parsed.members.isEmpty()) {
            ui.update { it.copy(message = "没有识别到队员：每行写「姓名 | 角色 | 负责说明 | 空闲时段」") }
            return
        }
        val cards = parsed.members.map { row ->
            MemberCard(
                memberId = "m-" + UUID.randomUUID().toString().take(8),
                name = row.name,
                roleTag = row.roleTag,
                responsibility = row.responsibility,
                weeklyAvailability = row.availability,
                scheduleConfidence = if (row.availability.isEmpty()) ScheduleConfidence.LOW else ScheduleConfidence.HIGH,
            )
        }
        var added = 0
        if (!mutate("批量邀请队员") { state -> added = state.addMembersBy(requireActorId(), cards) }) return
        val skipped = parsed.skippedCount + (parsed.members.size - added)
        ui.update {
            it.copy(
                message = if (added == 0) {
                    "这些队员都已在队里，没有重复添加"
                } else {
                    buildString {
                        append("已邀请 $added 名队员")
                        if (skipped > 0) append("；跳过 $skipped 行（重名或格式不符）")
                    }
                },
            )
        }
    }

    /** 移除队员：名下任务自动转派队长、台账留痕（§16）。 */
    fun removeMember(memberId: String) {
        mutate("移除队员") { state -> state.removeMemberBy(requireActorId(), memberId) }
    }

    // ---------- 组队名册（个人域，跨队复用） ----------

    /** 名册时间按天取整：同一支队伍反复落库不会每次都重写名册文件。 */
    private fun rosterSeenAt(): Long = LocalDate.now().toEpochDay() * 86_400_000L

    private fun recordRoster(state: TeamCollabState) {
        val next = ui.value.roster.recordTeam(state, rosterSeenAt())
        if (next === ui.value.roster) return
        ui.update { it.copy(roster = next) }
        persistRoster(next)
    }

    private fun persistRoster(book: TeamRosterBook) {
        val store = rosterStore ?: return
        viewModelScope.launch { runCatching { store.save(book) } }
    }

    /** 把还没合作、但想拉进队的人记进「你想了解」。 */
    fun pinRosterPerson(name: String, note: String = "") {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val key = TeamRosterBook.keyOf(trimmed)
        val existing = ui.value.roster.person(key)
        val next = ui.value.roster.pin(trimmed, note, System.currentTimeMillis())
        if (next === ui.value.roster) {
            ui.update { it.copy(message = "「${existing?.name ?: trimmed}」已经在名册里了") }
            return
        }
        val savedName = next.person(key)?.name ?: trimmed
        ui.update {
            it.copy(
                roster = next,
                message = when {
                    existing?.hasTeamed == true -> "「$savedName」已在共同参赛过里，备注已更新"
                    existing != null -> "已更新「$savedName」"
                    else -> "已加入「你想了解」：$savedName"
                },
            )
        }
        persistRoster(next)
    }

    fun forgetRosterPerson(key: String) {
        val next = ui.value.roster.forget(key)
        if (next === ui.value.roster) return
        ui.update { it.copy(roster = next, message = "已从名册移除") }
        persistRoster(next)
    }

    /** 从名册一键邀入当前队伍：分工与空闲时段一起带过去，排期不用重填。 */
    fun inviteRosterPerson(key: String) {
        val state = current ?: return
        val person = ui.value.roster.person(key) ?: return
        if (state.members.any { TeamRosterBook.keyOf(it.name) == key }) {
            ui.update { it.copy(message = "「${person.name}」已经在队里了") }
            return
        }
        val card = person.toMemberCard("m-" + UUID.randomUUID().toString().take(8))
        if (!mutate("邀请队员") { s -> s.addMemberBy(requireActorId(), card) }) return
        ui.update { it.copy(message = "已邀请 ${person.name}；空闲时段沿用名册里的记录") }
    }

    // ---------- 个人资料卡（个人域：默认卡 + 竞赛履历） ----------

    /**
     * 读盘 + 用「本机所有队伍 + 已归档结果」同步竞赛卡。
     * 别名只取默认卡名字与当前身份名：队里没这个人就不会生成卡，竞赛履历不能手填。
     */
    private suspend fun refreshPersonCards() {
        var book = if (cardsLoaded) {
            cardBook
        } else {
            personCardStore?.let { store -> runCatching { store.load() }.getOrNull() } ?: PersonCardBook.empty()
        }
        cardsLoaded = true
        actor?.let { member -> book = book.seedFromMember(member, System.currentTimeMillis()) }
        val aliases = buildSet {
            TeamRosterBook.keyOf(book.defaultCard().name).takeIf { it.isNotEmpty() }?.let(::add)
            actor?.let { TeamRosterBook.keyOf(it.name) }?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        if (aliases.isNotEmpty()) {
            val states = runCatching { store.readAllStates() }.getOrDefault(emptyList())
            val known = book.competitionCards().map { it.cardId }.toSet()
            val participations = PersonCardSync.participations(
                states = states,
                archives = profileMemory?.archives.orEmpty(),
                aliases = aliases,
                seenAtEpochMillis = System.currentTimeMillis(),
            ).map { participation ->
                // 已有卡不因刷新而更新「最近变化」时间；新卡才记当前时间。
                if (PersonCard.competitionCardId(participation.teamId) in known) {
                    participation.copy(seenAtEpochMillis = 0L)
                } else {
                    participation
                }
            }
            book = book.syncTeams(participations)
        }
        if (book != cardBook) persistCards(book) else ui.update { it.copy(cards = book) }
    }

    private fun persistCards(book: PersonCardBook) {
        cardBook = book
        ui.update { it.copy(cards = book) }
        val store = personCardStore ?: return
        viewModelScope.launch { runCatching { store.save(book) } }
    }

    /** 默认卡可组队前先写：能力与空闲都是匹配依据；公开后随邀请文案发出。 */
    fun saveDefaultCard(
        name: String,
        headline: String,
        abilities: String,
        expectation: String,
        availability: Map<DayOfWeek, List<com.swan1127.repland.domain.team.FreeWindow>>,
        dailyCapacityHours: Double,
        visibility: CardVisibility,
    ) {
        val next = ui.value.cards.editDefault(
            name = name,
            headline = headline,
            abilities = abilities,
            expectation = expectation,
            availability = availability,
            dailyCapacityHours = dailyCapacityHours,
            visibility = visibility,
            nowEpochMillis = System.currentTimeMillis(),
        )
        persistCards(next)
        // 资料卡里的空闲是个人域的源；在队里时同步给队伍，排期与队伍空闲才不是两套数据。
        val me = actor
        if (me != null && availability.isNotEmpty()) {
            mutate("同步空闲") { state ->
                state.syncMemberAvailability(me.memberId, me.memberId, availability, dailyCapacityHours)
            }
        }
        ui.update {
            it.copy(
                message = if (visibility == CardVisibility.PUBLIC) "默认卡已更新：邀请文案会带上它" else "默认卡已保存（仅自己可见）",
            )
        }
    }

    fun saveCompetitionCard(cardId: String, contribution: String, result: String, visibility: CardVisibility) {
        val next = ui.value.cards.editCompetition(cardId, contribution, result, visibility, System.currentTimeMillis())
        if (next == ui.value.cards) return
        persistCards(next)
    }

    fun setCardVisibility(cardId: String, visibility: CardVisibility) {
        val next = ui.value.cards.setVisibility(cardId, visibility, System.currentTimeMillis())
        if (next == ui.value.cards) return
        persistCards(next)
        ui.update {
            it.copy(message = if (visibility == CardVisibility.PUBLIC) "已公开：可随邀请发给别人" else "已收回：仅自己可见")
        }
    }

    /** 按需求匹配：只排名册里的人，排除已在队中的；理由来自资料本身。 */
    fun matchTeammates(needText: String, hoursPerWeek: Double): List<TeammateMatch> {
        val inTeam = current?.members?.map { TeamRosterBook.keyOf(it.name) }?.toSet().orEmpty()
        return TeammateMatchEngine.rank(
            people = ui.value.roster.people,
            need = TeamNeed(title = needText.trim(), hoursPerWeek = hoursPerWeek.coerceAtLeast(0.0)),
            excludeKeys = inTeam,
        )
    }

    /** 公开卡摘要：邀请面板拼进分享文案；没有公开卡时为 null。 */
    fun publicCardSummary(): String? = ui.value.cards.publicSummary()

    // ---------- 内置竞赛库：本地追踪（关注 → 备赛 → 已报名 → 已参赛；放弃随时） ----------

    /** 关注一场比赛：预填平台窗口截止作为提醒日；没有窗口日期就只追踪不提醒。 */
    fun followCompetition(instance: CompetitionInstance) {
        val repo = competitionTrackingRepository ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching {
                repo.upsert(
                    CompetitionTracking(
                        instanceKey = instance.key,
                        title = instance.title,
                        level = instance.level,
                        catalogueKey = instance.catalogueKey,
                        status = CompetitionTrackStatus.WATCHING,
                        deadlineEpochDay = instance.parsedEnd?.toEpochDay(),
                        remindEnabled = true,
                        note = "",
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
            }.onSuccess {
                ui.update {
                    it.copy(
                        message = if (instance.parsedEnd != null) {
                            "已关注「${instance.title}」，截止前一天 20:00 和当天 09:00 会提醒你"
                        } else {
                            "已关注「${instance.title}」"
                        },
                    )
                }
            }.onFailure { failure -> ui.update { it.copy(message = "关注失败：${failure.message}") } }
        }
    }

    fun unfollowCompetition(instanceKey: String) {
        val repo = competitionTrackingRepository ?: return
        viewModelScope.launch {
            runCatching { repo.remove(instanceKey) }
                .onSuccess { ui.update { it.copy(message = "已取消关注") } }
        }
    }

    /** 状态是标签不是闸门：用户想怎么改就怎么改，不设非法流转。 */
    fun setTrackStatus(instanceKey: String, status: CompetitionTrackStatus) {
        updateTracking(instanceKey, "已改为「${status.label}」") {
            it.copy(status = status, updatedAtEpochMillis = System.currentTimeMillis())
        }
    }

    fun toggleTrackRemind(instanceKey: String) {
        val current = ui.value.competitionTracking.firstOrNull { it.instanceKey == instanceKey } ?: return
        updateTracking(instanceKey, if (current.remindEnabled) "截止提醒已关闭" else "截止提醒已开启") {
            it.copy(remindEnabled = !it.remindEnabled, updatedAtEpochMillis = System.currentTimeMillis())
        }
    }

    private fun updateTracking(
        instanceKey: String,
        successMessage: String,
        change: (CompetitionTracking) -> CompetitionTracking,
    ) {
        val repo = competitionTrackingRepository ?: return
        viewModelScope.launch {
            val current = runCatching { repo.get(instanceKey) }.getOrNull() ?: return@launch
            runCatching { repo.upsert(change(current)) }
                .onSuccess { ui.update { it.copy(message = successMessage) } }
                .onFailure { failure -> ui.update { it.copy(message = "追踪更新失败：${failure.message}") } }
        }
    }

    /** 登记获奖进个人履历线；推免/学分预览在弹窗里按获奖年度口径实时算，这里只落库。 */
    fun registerAward(
        instanceKey: String,
        title: String,
        catalogueKey: String,
        level: AwardLevel,
        grade: AwardGrade,
        awardYear: Int,
        isTeam: Boolean,
        teamRank: Int,
        teammates: String,
        note: String,
    ) {
        val repo = competitionAwardRepository ?: return
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            ui.update { it.copy(message = "获奖名称不能为空") }
            return
        }
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching {
                repo.upsert(
                    CompetitionAward(
                        awardId = "aw-" + UUID.randomUUID().toString().take(8),
                        instanceKey = instanceKey,
                        title = trimmedTitle,
                        catalogueKey = catalogueKey,
                        level = level,
                        grade = grade,
                        awardYear = awardYear,
                        isTeam = isTeam,
                        teamRank = teamRank,
                        teammates = teammates.trim(),
                        note = note.trim(),
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
            }.onSuccess { ui.update { it.copy(message = "获奖已登记进履历") } }
                .onFailure { failure -> ui.update { it.copy(message = "登记失败：${failure.message}") } }
        }
    }

    fun deleteAward(awardId: String) {
        val repo = competitionAwardRepository ?: return
        viewModelScope.launch {
            runCatching { repo.remove(awardId) }
                .onSuccess { ui.update { it.copy(message = "已删除这条获奖记录") } }
        }
    }

    /** 助手确认路径的直写：不另发界面消息（结果由动作回执呈现），提醒随追踪收集器自动重排。 */
    private suspend fun applyAssistantFollow(instance: CompetitionInstance) {
        val repo = competitionTrackingRepository ?: error("竞赛追踪在本机不可用")
        val existing = runCatching { repo.get(instance.key) }.getOrNull()
        if (existing != null) return
        val now = System.currentTimeMillis()
        repo.upsert(
            CompetitionTracking(
                instanceKey = instance.key,
                title = instance.title,
                level = instance.level,
                catalogueKey = instance.catalogueKey,
                status = CompetitionTrackStatus.WATCHING,
                deadlineEpochDay = instance.parsedEnd?.toEpochDay(),
                remindEnabled = true,
                note = "",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    private suspend fun applyAssistantAward(award: CompetitionAward) {
        val repo = competitionAwardRepository ?: error("获奖履历在本机不可用")
        repo.upsert(award)
    }

    fun saveProfileCardField(group: ProfileSlotGroup, key: String, label: String, value: String) {
        mutate("档案卡") { state ->
            val card = state.profileCard ?: com.swan1127.repland.domain.team.CompetitionProfileCard(
                competitionName = "未命名比赛",
                fields = emptyMap(),
            )
            state.updateProfileCard(requireActorId(), card.withField(group, key, label, value.trim()))
        }
    }

    fun addProfileExtra(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        mutate("档案卡补充区") { state ->
            val card = state.profileCard ?: com.swan1127.repland.domain.team.CompetitionProfileCard("未命名比赛", fields = emptyMap())
            state.updateProfileCard(
                requireActorId(),
                card.withExtra(com.swan1127.repland.domain.team.ExtraEntry(key.trim(), value.trim())),
            )
        }
    }

    fun promoteExtra(group: ProfileSlotGroup, key: String) {
        mutate("升级为槽位") { state ->
            val card = state.profileCard ?: return@mutate
            val label = card.extras.firstOrNull { it.key == key }?.key ?: key
            state.updateProfileCard(requireActorId(), card.promoteExtraToSlot(group, key, label))
        }
    }

    fun addPhase(name: String, start: LocalDate, end: LocalDate) {
        if (name.isBlank()) return
        mutate("新增阶段") { state ->
            state.addPhaseBy(
                requireActorId(),
                Phase("phase-" + UUID.randomUUID().toString().take(8), name.trim(), start, end),
            )
        }
    }

    /** 套用比赛环节模板：报名→准备→攻关→提交→答辩一次建好，队长仍可逐项改。 */
    fun applyStageTemplate(templateId: String, start: LocalDate) {
        val stages = com.swan1127.repland.domain.team.CompetitionStages
        val template = stages.byId(templateId) ?: return
        var skipped: List<String> = emptyList()
        mutate("套用环节模板") { state ->
            val actorId = requireActorId()
            val suffix = UUID.randomUUID().toString().take(6)
            val phases = stages.instantiate(template, start, phaseIdPrefix = "phase-$suffix")
            val tasks = stages.baselineTasks(template, phases, taskIdPrefix = "task-$suffix", createdBy = actorId)
            skipped = state.applyStagePlan(actorId, template.label, phases, tasks).skipped
        }
        if (skipped.isNotEmpty()) {
            ui.update { it.copy(message = "已套用「${template.label}」，跳过 ${skipped.size} 项：${skipped.first()}") }
        }
    }

    fun addTask(title: String, tier: TaskTier, phaseId: String?, estimatedHours: Double) {
        if (title.isBlank()) return
        mutate("新增任务") { state ->
            val task = TeamTask(
                taskId = "task-" + UUID.randomUUID().toString().take(8),
                title = title.trim(),
                tier = tier,
                phaseId = phaseId,
                estimatedHours = estimatedHours.coerceAtLeast(0.5),
                isBaseline = tier != TaskTier.SHORT_TERM,
                createdBy = requireActorId(),
            )
            state.addTaskBy(requireActorId(), task)
        }
    }

    // ---------- 一步建任务：设好参数与截止，当场按现有空闲排进日程 ----------

    private val taskDateFmt = DateTimeFormatter.ofPattern("M月d日")

    private fun minuteText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun memberNameOf(state: TeamCollabState, memberId: String): String =
        state.members.firstOrNull { it.memberId == memberId }?.name ?: memberId

    private fun canScheduleNow(state: TeamCollabState, actorId: String): Boolean =
        state.members.firstOrNull { it.memberId == actorId }?.hasPermission(TeamPermission.SCHEDULE_TRANSFER) == true

    /**
     * 只给这一项任务找时段：其余任务与个人占用都是刚性边界；
     * 重排时把本任务的旧块作为「作废块」排除，避免自己挡住自己。
     */
    private fun scheduleSingleTask(
        state: TeamCollabState,
        task: TeamTask,
        personal: com.swan1127.repland.domain.team.PersonalTimeInput?,
        actorId: String,
        voided: List<com.swan1127.repland.domain.team.PersonalBlock> = emptyList(),
    ): TeamPlanDraft {
        val input = TeamSchedulerInputFactory.of(
            state = state,
            tasks = listOf(task),
            today = LocalDate.now(),
            extraRigidBlocks = emptyList(),
            voidedBlocks = voided,
            personalTime = personal,
            actorId = actorId,
        )
        val output = TeamScheduler.schedule(input)
        return output.draft.copy(placements = output.draft.placements.filter { it.taskId == task.taskId })
    }

    private fun deadlineProblem(state: TeamCollabState, deadline: LocalDate): String? =
        DeadlineEngine.validateDeadlineSetting(
            taskId = "new-task",
            title = "任务",
            date = deadline,
            competitionDeadline = state.competitionDeadline,
        )?.message

    /** 新建任务：参数 + 截止一次设完，当场排期落库；改参数走 [saveTaskParameters]。 */
    fun createScheduledTask(
        title: String,
        tier: TaskTier,
        phaseId: String?,
        estimatedHours: Double,
        assignedTo: String?,
        deadline: LocalDate,
        deadlineHour: Int?,
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val snapshot = ui.value.snapshot ?: return
        val actorId = ui.value.actorId ?: return
        if (ui.value.busy != null) return
        deadlineProblem(snapshot.state, deadline)?.let { problem ->
            ui.update { it.copy(message = problem) }
            return
        }
        if (deadline.isBefore(LocalDate.now())) {
            ui.update { it.copy(message = "截止已过，换个日期再建") }
            return
        }
        val task = TeamTask(
            taskId = "task-" + UUID.randomUUID().toString().take(8),
            title = trimmed,
            tier = tier,
            phaseId = phaseId,
            estimatedHours = estimatedHours.coerceAtLeast(0.5),
            assignedTo = assignedTo,
            hardDeadline = deadline,
            deadlineHour = deadlineHour,
            isBaseline = tier != TaskTier.SHORT_TERM,
            createdBy = actorId,
        )
        val canSchedule = canScheduleNow(snapshot.state, actorId)
        val schedulable = tier != TaskTier.SHORT_TERM
        ui.update { it.copy(busy = TeamBusy.SCHEDULING, message = null) }
        viewModelScope.launch {
            val personal = if (canSchedule && schedulable) withTimeoutOrNull(PERSONAL_TIME_TIMEOUT_MILLIS) {
                runCatching { personalTimeFor(snapshot.state, actorId) }.getOrNull()
            } else null
            val draft = if (canSchedule && schedulable) {
                runCatching { scheduleSingleTask(snapshot.state, task, personal, actorId) }.getOrNull()
            } else null
            var placed: Placement? = null
            val ok = mutate("新建任务") { state ->
                state.addTaskBy(actorId, task)
                val own = draft?.placements.orEmpty()
                if (canSchedule && own.isNotEmpty()) {
                    state.commitDraft(actorId, TeamPlanDraft(own), setOf(task.taskId))
                    placed = own.first()
                }
            }
            ui.update {
                it.copy(
                    busy = null,
                    message = when {
                        !ok -> it.message
                        placed != null -> {
                            val at = placed!!
                            "已建「$trimmed」并排进 ${at.date.format(taskDateFmt)} ${minuteText(at.startMinute)}–${minuteText(at.endMinute)} · ${memberNameOf(snapshot.state, at.memberId)}"
                        }
                        !schedulable -> "已建「$trimmed」；会议类任务不自动排时段，去会议排期约全员"
                        !canSchedule -> "已建「$trimmed」；你没有排期权限，等有权限成员来排"
                        else -> "已建「$trimmed」；没有找到可容纳的时段，点任务改截止或工时后再排"
                    },
                )
            }
        }
    }

    /**
     * 改任务参数（标题/等级/阶段/工时/截止）：保存即按新参数重排这一项。
     * 重排失败时保留旧时段，并如实说明，不假装成功。
     */
    fun saveTaskParameters(
        taskId: String,
        title: String,
        tier: TaskTier,
        phaseId: String?,
        estimatedHours: Double,
        deadline: LocalDate,
        deadlineHour: Int?,
    ) {
        val snapshot = ui.value.snapshot ?: return
        val actorId = ui.value.actorId ?: return
        val task = snapshot.state.tasks.firstOrNull { it.taskId == taskId } ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            ui.update { it.copy(message = "任务名不能为空") }
            return
        }
        if (ui.value.busy != null) return
        deadlineProblem(snapshot.state, deadline)?.let { problem ->
            ui.update { it.copy(message = problem) }
            return
        }
        val updated = task.copy(
            title = trimmed,
            tier = tier,
            phaseId = phaseId,
            estimatedHours = estimatedHours.coerceAtLeast(0.5),
            hardDeadline = deadline,
            deadlineHour = deadlineHour,
        )
        val canSchedule = canScheduleNow(snapshot.state, actorId)
        if (!canSchedule) {
            val ok = mutate("保存任务参数") { state -> state.editTask(actorId, updated) }
            if (ok) ui.update { it.copy(message = "任务参数已保存；你没有排期权限，时段需有权限成员调整") }
            return
        }
        if (updated.tier == TaskTier.SHORT_TERM) {
            val ok = mutate("保存任务参数") { state -> state.editTask(actorId, updated) }
            if (ok) ui.update { it.copy(message = "参数已保存；会议类任务不自动排时段") }
            return
        }
        ui.update { it.copy(busy = TeamBusy.SCHEDULING, message = null) }
        viewModelScope.launch {
            val personal = withTimeoutOrNull(PERSONAL_TIME_TIMEOUT_MILLIS) {
                runCatching { personalTimeFor(snapshot.state, actorId) }.getOrNull()
            }
            val voided = snapshot.state.blocks.filter {
                it.sourceTeamId == snapshot.state.teamId && it.taskId == taskId
            }
            val draft = runCatching {
                scheduleSingleTask(snapshot.state, updated, personal, actorId, voided)
            }.getOrNull()
            var placed: Placement? = null
            var rescheduled = false
            val ok = mutate("保存任务参数") { state ->
                state.editTask(actorId, updated)
                val own = draft?.placements.orEmpty()
                if (own.isNotEmpty()) {
                    state.commitDraft(actorId, TeamPlanDraft(own), setOf(taskId))
                    placed = own.first()
                    rescheduled = true
                }
            }
            ui.update {
                it.copy(
                    busy = null,
                    message = when {
                        !ok -> it.message
                        placed != null -> {
                            val at = placed!!
                            "参数已保存并重排：${at.date.format(taskDateFmt)} ${minuteText(at.startMinute)}–${minuteText(at.endMinute)}"
                        }
                        rescheduled -> "参数已保存"
                        else -> "参数已保存；没有找到能容纳新工时的时段，旧安排保持不变"
                    },
                )
            }
        }
    }

    fun addExternalLink(taskId: String, url: String) {
        if (url.isBlank()) return
        mutate("外链登记") { state -> state.addExternalLink(requireActorId(), taskId, url.trim()) }
    }

    fun createProfileCard(competitionName: String) {
        if (competitionName.isBlank()) return
        mutate("建档") { state ->
            state.updateProfileCard(
                requireActorId(),
                com.swan1127.repland.domain.team.CompetitionProfileCard(competitionName.trim(), fields = emptyMap()),
            )
        }
    }

    /** 粘贴比赛材料：预设字段落槽位，未知字段进补充区；已有档案卡按字段合并，不清空旧内容。 */
    fun importCompetition(text: String) {
        val parsed = CompetitionImport.parse(text)
        if (parsed.recognized == 0) {
            ui.update { it.copy(message = "没有识别到比赛信息：每行写「字段：内容」，如「报名截止：2026-11-15」") }
            return
        }
        val ok = mutate("导入比赛材料") { state ->
            val base = state.profileCard
                ?: CompetitionProfileCard(parsed.competitionName ?: "未命名比赛", fields = emptyMap())
            var card = parsed.competitionName?.let { base.copy(competitionName = it) } ?: base
            parsed.fields.forEach { (group, fields) ->
                fields.forEach { field ->
                    field.value?.takeIf { it.isNotBlank() }?.let { value ->
                        card = card.withField(group, field.key, field.label, value)
                    }
                }
            }
            parsed.extras.forEach { card = card.withExtra(it) }
            state.updateProfileCard(requireActorId(), card)
        }
        if (ok) ui.update { it.copy(message = "比赛材料已导入，检查档案卡后去建基线") }
    }

    /** 队员改自己的时间块（§5）：只能在自己的空闲窗口内平移。 */
    fun moveOwnBlock(blockId: String, newStartMinute: Int) {
        mutate("调整时间") { state -> state.moveOwnBlock(requireActorId(), blockId, newStartMinute) }
    }

    fun recordFinance(amountYuan: Double, reason: String) {
        if (reason.isBlank()) return
        mutate("财务记账") { state ->
            state.recordFinance(requireActorId(), (amountYuan * 100).toLong(), reason.trim())
        }
    }

    // ---------- 记忆库与参赛归档（§12/§16 T5） ----------

    fun addTeamMemory(kind: TeamMemoryKind, content: String) {
        if (content.isBlank()) return
        mutate("记忆写入") { state -> state.addTeamMemory(requireActorId(), kind, content) }
    }

    fun removeTeamMemory(memoryId: String) {
        mutate("记忆删除") { state -> state.removeTeamMemory(requireActorId(), memoryId) }
    }

    /** 个人域画像要点：仅本人可见，跨队复用；同文去重。 */
    fun addProfileFact(content: String) {
        val profileStore = humanProfileStore ?: return
        val profile = profileMemory ?: HumanProfileMemory().also { profileMemory = it }
        val fact = profile.addFact(content, LocalDateTime.now(), current?.teamId)
        if (fact == null) {
            ui.update { it.copy(message = "该画像要点已存在") }
            return
        }
        viewModelScope.launch {
            profileStore.save(profile)
            ui.update { it.copy(humanProfile = profile, message = "已沉淀进个人记忆（仅本人可见）") }
        }
    }

    fun removeProfileFact(factId: String) {
        val profileStore = humanProfileStore ?: return
        val profile = profileMemory ?: return
        if (!profile.removeFact(factId)) return
        viewModelScope.launch {
            profileStore.save(profile)
            ui.update { it.copy(humanProfile = profile) }
        }
    }

    /** 一键归档（§16）：队伍转只读；参赛档案沉淀进个人记忆域（未配置个人域存储时仅转只读）。 */
    fun archiveTeam(result: String) {
        val snapshot = ui.value.snapshot ?: return
        runCatching { snapshot.state.archiveTeam(requireActorId(), result) }
            .onFailure { failure -> ui.update { it.copy(message = "归档被拒绝：${failure.message}") } }
            .onSuccess { entry ->
                flushNotifications(snapshot.state)
                viewModelScope.launch { saveAndSync(snapshot.state) }
                val profileStore = humanProfileStore
                if (profileStore == null) {
                    ui.update { it.copy(message = "已归档：队伍转只读") }
                } else {
                    viewModelScope.launch {
                        val profile = profileMemory ?: HumanProfileMemory().also { profileMemory = it }
                        profile.depositArchive(entry)
                        profileStore.save(profile)
                        ui.update { it.copy(humanProfile = profile, message = "已归档：参赛档案沉淀进个人记忆域，队伍转只读") }
                        refreshPersonCards()
                    }
                }
            }
    }

    fun exportArchiveText(archiveId: String): String? = profileMemory?.exportArchiveText(archiveId)

    // ---------- 会话驱动（§6 数据流：说事 → LIAISON → SCHEDULER → 草案 → 确认/异议 → 落库） ----------

    /**
     * 说事入口：云端模型可用时走真实工具循环（先读现查 → 写入选确认卡）；
     * 未配置、失败或模型没接住时退回确定性规则，并如实标注「本地规则」。
     */
    fun say(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val snapshot = ui.value.snapshot ?: return
        val actorId = ui.value.actorId ?: return
        if (snapshot.state.members.none { it.memberId == actorId }) return
        if (ui.value.busy != null) return
        val epoch = sessionEpoch
        if (!ui.value.aiAvailable) {
            ui.update {
                it.copy(
                    assistantSource = AssistantSource.LOCAL,
                    assistantReply = null,
                    assistantTrace = emptyList(),
                    assistantError = null,
                )
            }
            sayLocally(text)
            return
        }
        ui.update {
            it.copy(
                busy = TeamBusy.TALKING,
                assistantReply = null,
                assistantTrace = emptyList(),
                assistantError = null,
                assistantSource = AssistantSource.AI,
            )
        }
        viewModelScope.launch {
            // 每次说事都如实核对一次云端可用性：密钥被删/被清空后不再冒充 AI。
            val stillReady = runCatching { turnModel.ready() }.getOrDefault(false)
            if (!stillReady) {
                ui.update { it.copy(busy = null, aiAvailable = false) }
                sayLocally(text, note = "云端模型未配置，已改用本地规则")
                return@launch
            }
            val engine = engineFor(snapshot.state, actorId)
            val events = CopyOnWriteArrayList<AgentConversationEvent>()
            val context = turnContext(snapshot.state, actorId)
            val turn = turnScope.async {
                runCatching { engine.runTurn(agentSession, text, context) { event -> events += event } }
            }
            val result = withTimeoutOrNull(AI_TURN_WATCHDOG_MILLIS) { turn.await() }
            if (result == null) turn.cancel()
            if (epoch != sessionEpoch) return@launch
            val session = result?.getOrNull()
            if (session == null) {
                ui.update { it.copy(busy = null) }
                val note = if (result == null) {
                    "AI 响应超时，已改用本地规则"
                } else {
                    "AI 调用失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
                sayLocally(text, note = note)
                return@launch
            }
            agentSession = session
            val reply = events.filterIsInstance<AgentConversationEvent.TextOutput>()
                .joinToString("\n") { it.text }
                .trim()
                .takeIf(String::isNotEmpty)
            val failure = events.filterIsInstance<AgentConversationEvent.Failed>().lastOrNull()
            val fallback = events.filterIsInstance<AgentConversationEvent.LocalFallback>().lastOrNull()
            val fallbackNote = fallback?.let {
                "云端模型不可用（${it.reason?.let(::failureText) ?: "未配置"}），已改用本地规则"
            }
            val trace = events.mapNotNull { it.traceLine() }.takeLast(8)
            val control = events.filterIsInstance<AgentConversationEvent.ControlRequested>().lastOrNull()?.control
            if (reply == null && session.pendingActions.isEmpty()) {
                ui.update { it.copy(busy = null, assistantTrace = trace) }
                sayLocally(text, note = fallbackNote ?: failure?.let { "AI 暂时不可用（${failureText(it.reason)}）" })
                return@launch
            }
            ui.update {
                it.copy(
                    busy = null,
                    assistantSource = if (fallback != null) AssistantSource.LOCAL else AssistantSource.AI,
                    assistantReply = reply ?: fallbackNote,
                    assistantTrace = trace,
                    pendingActions = session.pendingActions,
                    assistantError = fallbackNote ?: failure?.let { failureText(it.reason) },
                )
            }
            // 模型读懂「确认/取消」后走和点击完全相同的执行/放弃路径；没有草案时控制请求自然落空。
            when (control) {
                com.swan1127.repland.domain.model.AgentControl.CONFIRM_DRAFT ->
                    if (session.pendingActions.isNotEmpty()) confirmAssistantActions()
                com.swan1127.repland.domain.model.AgentControl.CANCEL_DRAFT ->
                    if (session.pendingActions.isNotEmpty()) discardAssistantActions()
                else -> Unit
            }
        }
    }

    /** 确认执行全部待确认动作；执行结果回填协议记录，派生动作（如整周排期草案）继续排队等确认。 */
    fun confirmAssistantActions() {
        val snapshot = ui.value.snapshot ?: return
        val actorId = ui.value.actorId ?: return
        val pending = ui.value.pendingActions
        if (pending.isEmpty() || ui.value.busy != null) return
        ui.update { it.copy(busy = TeamBusy.CONFIRMING, assistantError = null) }
        viewModelScope.launch {
            try {
                val toolbox = toolboxFor(snapshot.state, actorId)
                val engine = AgentConversationEngine(
                    chat = turnModel,
                    toolbox = toolbox,
                    systemPromptOverride = systemPromptFor(snapshot.state, actorId),
                    turnTimeoutMillis = 120_000L,
                )
                val results = mutableListOf<Pair<AgentAction, AgentActionResult>>()
                val trace = ui.value.assistantTrace.toMutableList()
                pending.forEach { action ->
                    val result = try {
                        toolbox.runWrite(action)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        AgentActionResult(false, failure.message ?: "执行失败")
                    }
                    results += action to result
                    trace += "${if (result.ok) "已执行" else "未执行"} · ${action.label}：${result.summary}"
                }
                if (results.any { it.second.ok }) {
                    bumpRevision()
                    flushNotifications(snapshot.state)
                    flushLedger(snapshot.state)
                    saveAndSync(snapshot.state)
                }
                runCatching { pullConfirmations() }
                pullTeamState()
                val settleEvents = mutableListOf<AgentConversationEvent>()
                val settled = runCatching {
                    engine.resumeAfterActions(agentSession, results, turnContext(snapshot.state, actorId)) { event ->
                        settleEvents += event
                    }
                }.getOrNull()
                if (settled != null) agentSession = settled
                val queued = settleEvents.filterIsInstance<AgentConversationEvent.ActionQueued>().map { it.action }
                val failures = results.filterNot { it.second.ok }
                ui.update { current ->
                    current.copy(
                        busy = null,
                        pendingActions = settled?.pendingActions ?: (
                            current.pendingActions.filterNot { old -> results.any { it.first.id == old.id } } + queued
                            ),
                        assistantTrace = trace.takeLast(10),
                        assistantError = failures.firstOrNull()?.second?.summary,
                        message = failures.firstOrNull()?.second?.summary ?: current.message,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ui.update { it.copy(message = failure.message ?: "确认执行失败") }
            } finally {
                ui.update { if (it.busy == TeamBusy.CONFIRMING) it.copy(busy = null) else it }
            }
        }
    }

    /** 放弃待确认动作：不执行，但把「已放弃」写回协议记录，避免下一轮模型当作已完成。 */
    fun discardAssistantActions() {
        val pending = ui.value.pendingActions
        if (pending.isEmpty()) return
        agentSession = agentSession.copy(
            transcript = agentSession.transcript + AgentChatMessage(
                "user",
                "[用户放弃了待确认动作] ${pending.joinToString("；") { it.toolName }}。这些动作没有执行，不要当作已完成。",
            ),
            pendingActions = agentSession.pendingActions.filterNot { action -> pending.any { it.id == action.id } },
        )
        ui.update { it.copy(pendingActions = emptyList(), assistantReply = null, assistantError = null) }
    }

    /** 放弃单条待确认动作：不执行，写回协议记录避免模型当作已完成。 */
    fun discardAssistantAction(actionId: String) {
        val action = ui.value.pendingActions.firstOrNull { it.id == actionId } ?: return
        agentSession = agentSession.copy(
            transcript = agentSession.transcript + AgentChatMessage(
                "user",
                "[用户放弃了待确认动作] ${action.toolName}。这个动作没有执行，不要当作已完成。",
            ),
            pendingActions = agentSession.pendingActions.filterNot { it.id == actionId },
        )
        ui.update { it.copy(pendingActions = it.pendingActions.filterNot { a -> a.id == actionId }) }
    }

    /** 本地确定性说事：云端不可用时的降级路径，标签会显示「本地规则」。 */
    private fun sayLocally(rawText: String, note: String? = null) {
        val snapshot = ui.value.snapshot ?: return
        val actorId = ui.value.actorId ?: return
        val at = LocalDateTime.now()
        val probe = Liaison.parse(rawText, actorId, snapshot.state.tasks, at, at.toLocalDate())
        val kind = when (probe.kind) {
            UpdateKind.DONE, UpdateKind.EARLY_DONE -> TeamRequestKind.STATUS_REPORT
            UpdateKind.TRANSFER_REQUEST -> TeamRequestKind.TRANSFER
            UpdateKind.OBJECTION -> TeamRequestKind.OBJECTION
            else -> TeamRequestKind.MEMBER_UPDATE
        }
        runSession(kind, rawText, at) { outcome ->
            applyOutcome(outcome, probe)
            ui.update { current ->
                current.copy(
                    assistantSource = AssistantSource.LOCAL,
                    assistantReply = listOfNotNull(note, outcome.summaryLine).joinToString("\n"),
                    assistantTrace = emptyList(),
                    assistantError = note,
                    pendingActions = emptyList(),
                )
            }
        }
    }

    /** 技能清单货架用：当前身份实际能调的工具面（隐藏工具不展示）。 */
    fun currentToolSpecs(): List<AgentToolSpec> {
        val snapshot = ui.value.snapshot ?: return emptyList()
        val actorId = ui.value.actorId ?: return emptyList()
        return toolboxFor(snapshot.state, actorId).specs().filter { !it.hidden }
    }

    private fun toolboxFor(state: TeamCollabState, actorId: String): TeamToolbox = TeamToolbox(
        state,
        actorId,
        LocalDate.now(),
        ui.value.roster,
        ui.value.cards,
        personalTime = { personalTimeFor(state, actorId) },
        competitionSeed = ui.value.competitionSeed,
        competitionTracking = ui.value.competitionTracking,
        followCompetition = { applyAssistantFollow(it) },
        registerAward = { applyAssistantAward(it) },
        competitionAwards = ui.value.competitionAwards,
        competitionWebFetcher = competitionWebFetcher,
    )

    private fun systemPromptFor(state: TeamCollabState, actorId: String): (AgentTurnContext) -> String {
        val actor = state.members.firstOrNull { it.memberId == actorId }
        return { TeamPrompt.system(actorId, actor?.name ?: actorId, actor?.isCaptain == true, state.charter) }
    }

    private fun engineFor(state: TeamCollabState, actorId: String): AgentConversationEngine =
        AgentConversationEngine(
            chat = turnModel,
            toolbox = toolboxFor(state, actorId),
            systemPromptOverride = systemPromptFor(state, actorId),
            turnTimeoutMillis = 120_000L,
        )

    private fun turnContext(state: TeamCollabState, actorId: String): AgentTurnContext {
        val today = LocalDate.now()
        return AgentTurnContext(
            date = today,
            occupiedEntries = emptyList(),
            domainBriefing = TeamPrompt.briefing(state, actorId, today, ui.value.roster, ui.value.cards),
            personaDoc = personaDocProvider(),
        )
    }

    private fun AgentConversationEvent.traceLine(): String? = when (this) {
        is AgentConversationEvent.ToolInvoked -> "$label：$resultSummary".take(140)
        else -> null
    }

    private fun failureText(reason: AiAdvisorFailureReason): String = when (reason) {
        AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED -> "未配置 AI 提供方"
        AiAdvisorFailureReason.AUTHENTICATION_FAILURE -> "AI 密钥被拒绝"
        AiAdvisorFailureReason.RATE_LIMITED -> "AI 请求被限流"
        AiAdvisorFailureReason.TIMEOUT -> "AI 响应超时"
        AiAdvisorFailureReason.TRANSPORT_FAILURE -> "网络连不上 AI"
        AiAdvisorFailureReason.INVALID_RESPONSE -> "AI 返回格式不对"
        AiAdvisorFailureReason.DISABLED -> "AI 已被关闭"
        AiAdvisorFailureReason.REMOTE_FAILURE -> "AI 服务返回错误"
        else -> "AI 暂不可用"
    }

    /** 整周排期（§2 软调度层）：确定性重排草案 → 队长确认落库。 */
    fun planWeek() = runSession(TeamRequestKind.PLAN_WEEK, "整周排期") {}

    /** 周报（§11）：开关制，零记录也能按表现数据总结；只写项目进展。 */
    fun generateWeeklyReport() {
        runSession(TeamRequestKind.WEEKLY_REPORT, "生成本周周报") { outcome ->
            ui.update { it.copy(weeklyReport = outcome.weeklyReport) }
        }
    }

    private fun busyFor(kind: TeamRequestKind): TeamBusy = when (kind) {
        TeamRequestKind.PLAN_WEEK -> TeamBusy.SCHEDULING
        TeamRequestKind.WEEKLY_REPORT -> TeamBusy.REPORTING
        else -> TeamBusy.TALKING
    }

    private fun runSession(
        kind: TeamRequestKind,
        rawText: String,
        at: LocalDateTime = LocalDateTime.now(),
        onReady: (TeamOutcome) -> Unit,
    ) {
        val snapshot = ui.value.snapshot ?: return
        val actorId = ui.value.actorId ?: return
        if (ui.value.busy != null) return
        val epoch = sessionEpoch
        ui.update { it.copy(busy = busyFor(kind), message = null) }
        val events = CopyOnWriteArrayList<String>()
        viewModelScope.launch {
            val personal = withTimeoutOrNull(PERSONAL_TIME_TIMEOUT_MILLIS) {
                runCatching { personalTimeFor(snapshot.state, actorId) }.getOrNull()
            }
            val session = turnScope.async {
                runCatching {
                    runtime.runSession(
                        TeamSessionContext(
                            requestKind = kind,
                            actorId = actorId,
                            rawText = rawText,
                            weekStart = at.toLocalDate().with(java.time.DayOfWeek.MONDAY),
                            at = at,
                            personalTime = personal,
                        ),
                        snapshot.state,
                    ) { event: TeamEvent -> events += event.summary }
                }
            }
            val result = withTimeoutOrNull(SESSION_WATCHDOG_MILLIS) { session.await() }
            if (result == null) session.cancel()
            if (epoch != sessionEpoch) return@launch
            val outcome = result?.getOrNull()
            val failure = result?.exceptionOrNull()
            // 副作用必须在 update 之外：update 的 lambda 可能因 CAS 失败重跑，回调重入会自激成死循环。
            if (outcome != null) onReady(outcome)
            ui.update {
                it.copy(
                    busy = null,
                    outcome = outcome ?: it.outcome,
                    events = events.toList(),
                    message = when {
                        outcome != null -> outcome.summaryLine
                        failure != null -> failure.message ?: "会话失败"
                        result == null -> "计算超时，请重试"
                        else -> "会话失败"
                    },
                )
            }
            // T4c §16：SCHEDULER 产出草案 → 登记「待确认草案」双通道通知（飞书卡片回调闭环的起点）
            outcome?.draft?.let { registerDraftForConfirmation(it) }
        }
    }

    /** 会话产出落库（runtime 只读；这里按 §5/§9/§7 纪律把结构化结果写进仓库）。 */
    private fun applyOutcome(outcome: TeamOutcome, probe: com.swan1127.repland.domain.team.agents.StructuredRequest) {
        val snapshot = ui.value.snapshot ?: return
        val state = snapshot.state
        val kind = outcome.structuredRequest?.kind ?: probe.kind
        val taskId = outcome.structuredRequest?.taskId ?: probe.taskId
        runCatching {
            val actorId = requireActorId()
            val isDone = kind == UpdateKind.DONE || kind == UpdateKind.EARLY_DONE
            // DONE 的留痕由 reportDone 统一写（状态流转 + 口语），这里不重复并入，避免双份台账。
            if (!isDone) {
                outcome.proposedLedgerEntries.forEach { entry ->
                    if (state.ledger.none { it.entryId == entry.entryId }) state.ledger += entry
                }
            }
            when (kind) {
                // 报告完成也走权限入口：不在名下且无排期权限会被拒绝并上浮，不再静默改状态。
                UpdateKind.DONE, UpdateKind.EARLY_DONE -> {
                    if (taskId != null) state.reportDone(actorId, taskId, note = probe.rawText)
                }
                UpdateKind.OBJECTION -> {
                    if (taskId != null) state.raiseObjection(actorId, taskId, probe.rawText)
                }
                else -> Unit
            }
            // BUSY/DELAY/整周排期的 draft 不在此落库：草案需确认（UI 出确认卡，commitDraft 落库）
        }.onSuccess {
            bumpRevision()
            // T4c：说事路径的台账/通知同样进推送与副本管道（§2/§5）
            flushNotifications(state)
            flushLedger(state)
            viewModelScope.launch { saveAndSync(state) }
        }.onFailure { failure ->
            ui.update { it.copy(message = failure.message ?: "产出落库失败") }
        }
    }

    private fun requireActorId(): String = ui.value.actorId ?: error("尚未选择身份")

    class Factory(
        private val store: TeamSessionStore,
        private val backendConfigStore: BackendConfigStore? = null,
        private val humanProfileStore: HumanProfileStore? = null,
        private val notifierProvider: () -> TeamNotifier = { NoopNotifier },
        private val personalBridge: com.swan1127.repland.domain.team.TeamPersonalBridge? = null,
        private val planRepository: com.swan1127.repland.domain.ports.PlanRepository? = null,
        private val timeRepository: com.swan1127.repland.domain.ports.TimeRepository? = null,
        private val agentTurnModel: AgentTurnModel? = null,
        private val rosterStore: TeamRosterStore? = null,
        private val personCardStore: PersonCardStore? = null,
        private val competitionCatalogStore: CompetitionCatalogStore? = null,
        private val competitionTrackingRepository: com.swan1127.repland.domain.ports.CompetitionTrackingRepository? = null,
        private val competitionAwardRepository: com.swan1127.repland.domain.ports.CompetitionAwardRepository? = null,
        private val competitionReminderScheduler: com.swan1127.repland.reminders.CompetitionReminderScheduler? = null,
        private val competitionWebFetcher: CompetitionWebFetcher? = null,
        private val personaDocProvider: () -> String = { "" },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TeamViewModel(
                store, backendConfigStore, notifierProvider, humanProfileStore,
                personalBridge, planRepository, timeRepository, agentTurnModel, rosterStore, personCardStore,
                competitionCatalogStore, competitionTrackingRepository, competitionAwardRepository,
                competitionReminderScheduler, competitionWebFetcher, personaDocProvider,
            ) as T
    }

    // ---------- 后端连接配置（T4b 配置面；App 只持有后端地址 + 账号 token） ----------

    val backendConfigured: Boolean get() = backendConfigStore?.isConfigured == true
    val backendBaseUrl: String get() = backendConfigStore?.baseUrl.orEmpty()
    val backendAccountToken: String get() = backendConfigStore?.accountToken.orEmpty()

    // ---------- 进入门槛（进入前先注册/登录；「先离线使用」可跳过，个人域离线能力不受影响） ----------

    /** 登录门：未配置后端且未选离线模式时，首次进入需要登录/注册；ReplandApp 据此整页切换。 */
    private val _sessionGate = MutableStateFlow(
        backendConfigStore?.let { !it.isConfigured && !it.offlineMode } ?: false,
    )
    val sessionGate: StateFlow<Boolean> = _sessionGate.asStateFlow()

    /** 登录门即登录界面：验证码/微信配对在门上完成（SessionGateScreen 直调本类），无放行外的中间态。 */

    /** 登录门点「先离线使用」：本次安装不再弹门；登录/退出登录会翻转它。 */
    fun proceedOffline() {
        backendConfigStore?.offlineMode = true
        _sessionGate.value = false
    }

    /** 离线用户想补登录：从「账号与服务器」重新打开登录门（登录卡已上移到门上）。 */
    fun openLoginGate() {
        backendConfigStore?.offlineMode = false
        _sessionGate.value = true
    }

    /** 测试连接：GET /v1/me（联网实现反射自 src/remote；release 反射失败 → 提示无联网能力）。 */
    suspend fun probeBackend(baseUrl: String, accountToken: String): BackendProbeReport =
        withContext(Dispatchers.IO) {
            backendConfigStore?.buildProber()?.probe(baseUrl, accountToken)
                ?: BackendProbeReport(ok = false, httpCode = 0, message = "本构建未接入后端配置")
        }

    // ---------- 账号登录（T3 补齐：手机验证码 + 微信配对；§13 App 只落 token） ----------

    /** 当前队伍 ID：登录时用于认领预置成员位（未建队则游离登录）。 */
    val currentTeamId: String get() = ui.value.snapshot?.state?.teamId.orEmpty()

    suspend fun requestSmsCode(baseUrl: String, phone: String): SmsCodeResult = withContext(Dispatchers.IO) {
        backendConfigStore?.buildSmsCodeRequester()?.request(baseUrl, phone)
            ?: SmsCodeResult(false, "本构建未接入后端配置")
    }

    suspend fun verifySmsCode(
        baseUrl: String,
        phone: String,
        code: String,
        memberId: String,
        displayName: String,
    ): AuthLoginResult = withContext(Dispatchers.IO) {
        backendConfigStore?.buildSmsVerifier()
            ?.verify(baseUrl, phone, code, displayName, currentTeamId, memberId)
            ?: AuthLoginResult(false, "本构建未接入后端配置")
    }

    suspend fun startWechatQrLogin(): WechatQrStartResult =
        withContext(Dispatchers.IO) {
            backendConfigStore?.buildWechatQrStarter()?.start(backendBaseUrl)
                ?: WechatQrStartResult(false, "本构建未接入后端配置")
        }

    suspend fun pollWechatQrLogin(pollToken: String): AuthLoginResult =
        withContext(Dispatchers.IO) {
            backendConfigStore?.buildWechatQrPoller()?.poll(backendBaseUrl, pollToken)
                ?: AuthLoginResult(false, "本构建未接入后端配置")
        }

    suspend fun downloadWechatAvatar(url: String): ByteArray? = withContext(Dispatchers.IO) {
        backendConfigStore?.buildWechatAvatarDownloader()?.download(url)
    }

    /** 登录成功落库：token 即后端 accountToken，推送/同步/台账/日历四通道即时生效。 */
    fun applyLoginToken(token: String): Boolean {
        val configStore = backendConfigStore ?: return false
        if (token.isBlank()) return false
        configStore.accountToken = token
        configStore.offlineMode = false
        _sessionGate.value = false
        // 新设备登录后立刻拉一次云端队伍快照（§13 表级同步第一块）
        pullTeamState()
        return true
    }

    // ---------- 账号管理（登出 / 账号信息） ----------

    /** 账号信息（/v1/auth/account-me；未登录返回 null）。 */
    suspend fun fetchAccountMe(): AccountMeInfo? = withContext(Dispatchers.IO) {
        val config = backendConfigStore ?: return@withContext null
        if (!config.isConfigured) return@withContext null
        config.buildAccountMeFetcher().fetch(config.baseUrl, config.accountToken)
    }

    /** 登出：先吊销服务器 token，成功才清本地（服务器不可达时保留登录态，避免留下无法撤销的会话）。 */
    suspend fun logoutAccount(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val config = backendConfigStore ?: return@withContext false to "本构建未接入后端配置"
        if (!config.isConfigured) return@withContext false to "尚未登录"
        val ok = config.buildAccountLogout().logout(config.baseUrl, config.accountToken)
        if (ok) {
            config.accountToken = ""
            config.offlineMode = false
            _sessionGate.value = true
            true to "已退出登录"
        } else {
            false to "登出失败：服务器不可达"
        }
    }

    // ---------- §13 表级同步第一块：队伍快照双向同步（本地 Room 仍是主副本） ----------

    /** 上行：revision 守卫；冲突 = 服务器有更新版本 → 拉取覆盖本地（服务器赢）并提示。 */
    private suspend fun pushStateToServer(state: TeamCollabState) {
        val config = backendConfigStore ?: return
        if (!config.isConfigured) return
        val payload = runCatching { TeamStateCodec.toJson(state) }.getOrNull() ?: return
        val actorName = ui.value.actorId
            ?.let { id -> state.members.firstOrNull { it.memberId == id }?.name }
            .orEmpty()
        val result = withContext(Dispatchers.IO) {
            config.buildTeamStatePusher()
                .push(config.baseUrl, config.accountToken, state.teamId, config.knownServerRevision(state.teamId), payload, actorName)
        }
        when {
            result.ok -> config.setKnownServerRevision(state.teamId, result.revision)
            result.conflict -> {
                val applied = applyServerState(state.teamId, result.serverRevision, result.serverPayload)
                ui.update {
                    it.copy(
                        message = if (applied) "服务器上有其他成员更新的版本，已拉取合并"
                        else "云端版本无法导入（快照格式不匹配），本地改动暂未同步"
                    )
                }
            }
        }
    }

    /** 下行：换队/落库动作/AI 动作后/启动时；本地没队伍时按账号成员位从云端恢复（重装不丢队）。 */
    private fun pullTeamState() {
        val config = backendConfigStore ?: return
        if (!config.isConfigured) return
        viewModelScope.launch {
            val snapshotTeamId = ui.value.snapshot?.state?.teamId
            val teamIds = snapshotTeamId?.let { listOf(it) }
                ?: withContext(Dispatchers.IO) {
                    config.buildAccountMeFetcher().fetch(config.baseUrl, config.accountToken)
                        ?.memberships?.map { it.teamId }.orEmpty().distinct()
                }
            teamIds.forEach { teamId ->
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        config.buildTeamStatePuller()
                            .pull(config.baseUrl, config.accountToken, teamId, config.knownServerRevision(teamId))
                    }.getOrNull()
                } ?: return@forEach
                if (result.ok && result.hasNewer) {
                    if (applyServerState(teamId, result.revision, result.payload)) {
                        ui.update { it.copy(message = "已从云端恢复团队进度（${result.updatedByMember.ifBlank { "队友" }} 推送）") }
                    }
                }
            }
        }
    }

    /** 服务器赢：整份快照导入本地 Room 主副本并镜像个人域。返回 false = 云端快照本地无法解析。 */
    private suspend fun applyServerState(teamId: String, revision: Long, payload: String): Boolean {
        if (payload.isBlank()) return false
        val state = store.importSnapshot(payload) ?: return false
        backendConfigStore?.setKnownServerRevision(state.teamId.ifBlank { teamId }, revision)
        syncPersonal(state)
        return true
    }

    // ---------- §12 mem0 云记忆同步（可选远端层；本地 JSON 仍是主副本，离线优先不变） ----------

    /** 个人记忆域全量推送：画像要点 + 参赛档案导出文本（服务端按 accountId 隔离写入 mem0）。 */
    suspend fun syncPersonalMemory(baseUrl: String): MemorySyncResult = withContext(Dispatchers.IO) {
        val profile = profileMemory
            ?: return@withContext MemorySyncResult(false, "个人记忆为空，先沉淀画像要点")
        backendConfigStore?.buildMemoryPusher()
            ?.push(baseUrl, backendAccountToken, profile.facts.map { it.content }, profile.archives.map { it.exportText() })
            ?: MemorySyncResult(false, "本构建未接入后端配置")
    }

    /** 云端记忆语义检索（账号域隔离）。 */
    suspend fun searchCloudMemory(baseUrl: String, query: String): MemorySearchResult =
        withContext(Dispatchers.IO) {
            backendConfigStore?.buildMemorySearcher()?.search(baseUrl, backendAccountToken, query)
                ?: MemorySearchResult(false, "本构建未接入后端配置")
        }

    // ---------- T4c：确认记录拉取消费端（§13 拉取起步；§7 卡片确认等价一次 UI 确认操作） ----------

    /** 待确认草案登记：draftId → 草案（飞书确认到达时按 id 取回；UI 确认/放弃即移除）。 */
    private val pendingDrafts = LinkedHashMap<String, TeamPlanDraft>()

    /**
     * SCHEDULER 产出草案 → 登记「待确认草案」双通道通知。
     * 卡片按钮回调落库后，各端经 [pullConfirmations] 并入本地主副本（ConfirmationApplier）。
     */
    private fun registerDraftForConfirmation(draft: TeamPlanDraft) {
        if (pendingDrafts.values.any { it === draft }) return
        val draftId = "d-" + UUID.randomUUID().toString().take(8)
        pendingDrafts[draftId] = draft
        mutate("登记待确认草案") { state ->
            state.notifications += TeamNotification(
                "ntf-" + UUID.randomUUID().toString().take(8),
                NotificationChannel.PUSH_AND_FEISHU,
                "队长",
                "排期草案待确认：${draft.placements.size} 段安排" +
                    (draft.unassigned.takeIf { it.isNotEmpty() }?.let { "、${it.size} 项待安排" } ?: ""),
                state.now(),
                card = NotificationCardRef(CARD_DRAFT_CONFIRM, draftId),
            )
        }
    }

    /** 游标增量拉取 /v1/confirmations（§13）；每次落库动作/换队后触发，失败静默。 */
    private fun pullConfirmations() {
        val config = backendConfigStore ?: return
        if (!config.isConfigured) return
        val snapshot = ui.value.snapshot ?: return
        viewModelScope.launch {
            val since = config.confirmationCursor
            val result = runCatching {
                config.buildConfirmationPuller().pull(config.baseUrl, config.accountToken, since)
            }.getOrNull() ?: return@launch
            // 先记游标再并入：记录是一次性通知，副本落后时只留提示不重投（§5 语义）
            if (result.cursor > config.confirmationCursor) config.confirmationCursor = result.cursor
            if (result.items.isEmpty()) return@launch
            val report = ConfirmationApplier.apply(snapshot.state, result.items, pendingDrafts)
            if (report.changed) {
                flushNotifications(snapshot.state)
                flushLedger(snapshot.state)
                saveAndSync(snapshot.state)
            }
            report.notes.firstOrNull()?.let { note ->
                ui.update { it.copy(message = note.take(120)) }
            }
        }
    }

    // ---------- 个人域桥接：团队任务镜像进个人任务库，团队排期写进个人日程 ----------

    private suspend fun syncPersonal(state: TeamCollabState) {
        val bridge = personalBridge ?: return
        val actorId = ui.value.actorId ?: state.members.firstOrNull { it.isCaptain }?.memberId ?: return
        runCatching { bridge.sync(state, actorId) }
            .onSuccess { report ->
                report.note()?.let { note ->
                    ui.update { current -> current.copy(message = note) }
                }
            }
            .onFailure { failure ->
                ui.update { current -> current.copy(message = "团队任务同步到个人失败：${failure.message}") }
            }
    }

    private suspend fun saveAndSync(state: TeamCollabState) {
        store.save(state)
        syncPersonal(state)
        pushStateToServer(state)
    }

    /** 个人真实课表 + 已确认安排 + 负载；只取未来 60 天，供 SCHEDULER 定人与选时段。 */
    private suspend fun personalTimeFor(state: TeamCollabState, actorId: String): com.swan1127.repland.domain.team.PersonalTimeInput? {
        val plan = planRepository ?: return null
        val time = timeRepository ?: return null
        val segments = plan.observeCurrentPlan().first()?.segments.orEmpty()
        val weekly = time.observeWeeklyBlocks().first()
        val overrides = time.observeDateOverrides().first()
        val firstMonday = time.observeTimeConstraintSettings().first().semesterFirstWeekMonday
        if (segments.isEmpty() && weekly.isEmpty() && overrides.isEmpty()) return null
        return com.swan1127.repland.domain.team.TeamPersonalMirror.busyTime(
            actorId = actorId,
            teamId = state.teamId,
            today = LocalDate.now(),
            horizonDays = 60,
            weeklyBlocks = weekly,
            dateOverrides = overrides,
            segments = segments,
            semesterFirstWeekMonday = firstMonday,
        )
    }
}
