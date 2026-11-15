package com.swan1127.repland

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.swan1127.repland.data.importer.PdfTimetableImporter
import com.swan1127.repland.data.room.ReplandDatabase
import com.swan1127.repland.data.room.RoomCategoryPreferenceRepository
import com.swan1127.repland.data.room.RoomTaskRepository
import com.swan1127.repland.data.room.RoomTimeRepository
import com.swan1127.repland.data.room.RoomPlanRepository
import com.swan1127.repland.data.room.RoomReminderSettingsRepository
import com.swan1127.repland.data.room.RoomProfileEvidenceRepository
import com.swan1127.repland.data.room.RoomDataManagementRepository
import com.swan1127.repland.data.room.RoomAiSettingsRepository
import com.swan1127.repland.data.room.RoomEngagementRepository
import com.swan1127.repland.data.security.SecureAiProviderConfigRepository
import com.swan1127.repland.domain.model.PlanDraftGenerator
import com.swan1127.repland.domain.model.PlanGenerator
import com.swan1127.repland.domain.ports.PlanRepository
import com.swan1127.repland.domain.ports.CategoryPreferenceRepository
import com.swan1127.repland.domain.ports.TaskRepository
import com.swan1127.repland.domain.ports.TimeRepository
import com.swan1127.repland.domain.ports.ReminderSettingsRepository
import com.swan1127.repland.domain.ports.ProfileEvidenceRepository
import com.swan1127.repland.domain.ports.DataManagementRepository
import com.swan1127.repland.domain.ports.AiSettingsRepository
import com.swan1127.repland.domain.ports.AiProviderConfigRepository
import com.swan1127.repland.domain.model.AiAdvisor
import com.swan1127.repland.domain.model.NoOpAiAdvisor
import com.swan1127.repland.domain.model.ArrangementAssistantAdvisor
import com.swan1127.repland.domain.model.NoOpArrangementAssistantAdvisor
import com.swan1127.repland.domain.model.PlanningAgent
import com.swan1127.repland.domain.model.PlanningAgentWorkflow
import com.swan1127.repland.reminders.LocalReminderScheduler
import kotlinx.coroutines.flow.first

class ReplandApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        LocalReminderScheduler.createNotificationChannel(this)
        com.swan1127.repland.reminders.FocusProgressNotifier.createNotificationChannel(this)
    }
}

class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context,
        ReplandDatabase::class.java,
        "repland.db",
    ).addMigrations(
        ReplandDatabase.MIGRATION_1_2,
        ReplandDatabase.MIGRATION_2_3,
        ReplandDatabase.MIGRATION_3_4,
        ReplandDatabase.MIGRATION_4_5,
        ReplandDatabase.MIGRATION_5_6,
        ReplandDatabase.MIGRATION_6_7,
        ReplandDatabase.MIGRATION_7_8,
        ReplandDatabase.MIGRATION_8_9,
        ReplandDatabase.MIGRATION_9_10,
        ReplandDatabase.MIGRATION_10_11,
        ReplandDatabase.MIGRATION_11_12,
        ReplandDatabase.MIGRATION_12_13,
        ReplandDatabase.MIGRATION_13_14,
        ReplandDatabase.MIGRATION_14_15,
        ReplandDatabase.MIGRATION_15_16,
        ReplandDatabase.MIGRATION_16_17,
        ReplandDatabase.MIGRATION_17_18,
        ReplandDatabase.MIGRATION_18_19,
        ReplandDatabase.MIGRATION_19_20,
        ReplandDatabase.MIGRATION_20_21,
        ReplandDatabase.MIGRATION_21_22,
        ReplandDatabase.MIGRATION_22_23,
        ReplandDatabase.MIGRATION_23_24,
        ReplandDatabase.MIGRATION_24_25,
        ReplandDatabase.MIGRATION_25_26,
        ReplandDatabase.MIGRATION_26_27,
        ReplandDatabase.MIGRATION_27_28,
        ReplandDatabase.MIGRATION_28_29,
        ReplandDatabase.MIGRATION_29_30,
    ).build()

    /** 所有计划写入共用一道闸：手动、引擎、对话、撤销排在同一队列，写完不互相踩。 */
    private val planWriteGate = com.swan1127.repland.data.room.PlanWriteGate()

    val teamStore = com.swan1127.repland.data.team.TeamSessionStore(database.teamSessionDao(), context)
    /** 团队任务进个人库、团队排期进个人日程；与手动/对话写入共用同一条写闸门。 */
    val teamPersonalBridge: com.swan1127.repland.domain.team.TeamPersonalBridge =
        com.swan1127.repland.data.room.RoomTeamPersonalBridge(database, planWriteGate, teamStore)
    val taskRepository: TaskRepository = RoomTaskRepository(database)
    val taskCaptureRepository = com.swan1127.repland.data.room.RoomTaskCaptureRepository(database)
    val executionSessionRepository =
        com.swan1127.repland.data.room.RoomExecutionSessionRepository(database, teamBridge = teamPersonalBridge,
            planWriteGate = planWriteGate)
    val timeRepository: TimeRepository = RoomTimeRepository(database.timeDao())
    val planRepository: PlanRepository = RoomPlanRepository(database, planWriteGate)
    val agentWriteUndo: com.swan1127.repland.domain.model.AgentWriteUndo =
        com.swan1127.repland.data.room.AgentWriteCaretaker(database, planWriteGate)
    val autonomyRepository: com.swan1127.repland.domain.ports.AutonomyRepository =
        com.swan1127.repland.data.room.RoomAutonomyRepository(database, planWriteGate, teamPersonalBridge)
    val categoryPreferenceRepository: CategoryPreferenceRepository =
        RoomCategoryPreferenceRepository(database.categoryPreferenceDao())
    val reminderSettingsRepository: ReminderSettingsRepository =
        RoomReminderSettingsRepository(database.reminderSettingsDao())
    val profileEvidenceRepository: ProfileEvidenceRepository = RoomProfileEvidenceRepository(database)
    val aiProviderConfigRepository: AiProviderConfigRepository = SecureAiProviderConfigRepository(context)
    val numericProfileRepository = com.swan1127.repland.data.room.RoomNumericProfileRepository(database, aiProviderConfigRepository)
    val personalProfileStore = com.swan1127.repland.data.profile.LocalPersonalProfileStore(context)
    /** 「关于我」自述：用户亲口写的第一人称画像（用户资产），个人/队伍助手都读它；不是排期输入。 */
    val personaDocStore = com.swan1127.repland.data.PersonaDocStore(context)
    /** 助手日记：主动简报的只读留痕 + 用户回话状态（问责面，不是记忆推断）；属本机个人数据。 */
    val assistantJournalStore = com.swan1127.repland.data.AssistantJournalStore(context)
    val backendConfigStore = com.swan1127.repland.data.team.BackendConfigStore(context)
    val humanProfileStore = com.swan1127.repland.data.team.HumanProfileStore(context)
    val teamRosterStore = com.swan1127.repland.data.team.TeamRosterStore(context)
    /** 个人资料卡：默认卡 + 竞赛履历（只能由真实队伍生成）。 */
    val personCardStore = com.swan1127.repland.data.team.PersonCardStore(context)
    /** 内置竞赛库（assets 种子：加分目录 + 平台赛事实例 + 按获奖年度版本化的口径）。 */
    val competitionCatalogStore = com.swan1127.repland.data.competition.CompetitionCatalogStore(context)
    /** 竞赛追踪：本地 Room 小表；关注/备赛/已报名的截止提醒由此驱动。 */
    val competitionTrackingRepository: com.swan1127.repland.domain.ports.CompetitionTrackingRepository =
        com.swan1127.repland.data.room.RoomCompetitionTrackingRepository(database.competitionTrackingDao())
    /** 获奖登记：个人履历线；推免/学分预览按获奖年度口径版本算。 */
    val competitionAwardRepository: com.swan1127.repland.domain.ports.CompetitionAwardRepository =
        com.swan1127.repland.data.room.RoomCompetitionAwardRepository(database.competitionAwardDao())
    /** 比赛截止提醒管线：独立于计划提醒，只重摆自己的闹钟。 */
    val competitionReminderScheduler = com.swan1127.repland.reminders.CompetitionReminderScheduler(context)
    /** 竞赛资讯联网抓取：只在含 src/remote 的构建有实现；正式版如实没有联网能力。 */
    val competitionWebFetcher: com.swan1127.repland.domain.competition.CompetitionWebFetcher? = runCatching {
        Class.forName("com.swan1127.repland.data.competition.HttpCompetitionWebFetcher")
            .getDeclaredConstructor().newInstance() as? com.swan1127.repland.domain.competition.CompetitionWebFetcher
    }.getOrNull()
    val auxiliaryStore = com.swan1127.repland.data.AuxiliaryStore(context)
    val weatherStore = com.swan1127.repland.data.weather.LocalWeatherStore(context)
    val weatherRepository: com.swan1127.repland.domain.ports.WeatherRepository = weatherStore
    /** The optional sky: release has no weather any more than it has a chat provider. */
    val weatherSource: com.swan1127.repland.domain.model.WeatherSource = runCatching {
        Class.forName("com.swan1127.repland.data.weather.OpenMeteoWeatherSource")
            .getDeclaredConstructor().newInstance() as com.swan1127.repland.domain.model.WeatherSource
    }.getOrDefault(com.swan1127.repland.domain.model.NoOpWeatherSource)
    val weatherPlaceSearch: com.swan1127.repland.domain.model.WeatherPlaceSearch = runCatching {
        Class.forName("com.swan1127.repland.data.weather.OpenMeteoPlaceSearch")
            .getDeclaredConstructor().newInstance() as com.swan1127.repland.domain.model.WeatherPlaceSearch
    }.getOrDefault(com.swan1127.repland.domain.model.NoOpWeatherPlaceSearch)
    val dataManagementRepository: DataManagementRepository = RoomDataManagementRepository(database) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                personalProfileStore.clear()
                auxiliaryStore.clearLearningMemory()
                weatherStore.clear()
                humanProfileStore.clear()
                personCardStore.clear()
                assistantJournalStore.clear()
                com.swan1127.repland.data.ContentPortraitStore(context).clear()
                com.swan1127.repland.ui.agent.AgentChatSessionStore(context).clear()
            } finally { planningOperationService.clearTeamMemory() }
        }
    }
    val aiSettingsRepository: AiSettingsRepository = RoomAiSettingsRepository(database.aiSettingsDao())
    val usageSummaryRepository = com.swan1127.repland.data.room.RoomUsageSummaryRepository(database, aiProviderConfigRepository)
    val engagementRepository = RoomEngagementRepository(database)
    /** QA/internal share the optional BYOK provider; release stays local-only. */
    val aiAdvisor: AiAdvisor = runCatching {
        Class.forName("com.swan1127.repland.data.ai.CompatibleAiAdvisor")
            .getDeclaredConstructor(AiProviderConfigRepository::class.java)
            .newInstance(aiProviderConfigRepository) as AiAdvisor
    }.getOrDefault(NoOpAiAdvisor)
    val arrangementAssistantAdvisor: ArrangementAssistantAdvisor = runCatching {
        Class.forName("com.swan1127.repland.data.ai.CompatibleArrangementAdvisor")
            .getDeclaredConstructor(AiProviderConfigRepository::class.java)
            .newInstance(aiProviderConfigRepository) as ArrangementAssistantAdvisor
    }.getOrDefault(NoOpArrangementAssistantAdvisor)
    val agentChatModel: com.swan1127.repland.domain.model.AgentChatModel = runCatching {
        Class.forName("com.swan1127.repland.data.ai.CompatibleAgentChat")
            .getDeclaredConstructor(AiProviderConfigRepository::class.java)
            .newInstance(aiProviderConfigRepository) as com.swan1127.repland.domain.model.AgentChatModel
    }.getOrDefault(com.swan1127.repland.domain.model.NoOpAgentChatModel)
    /** 工具调用会话的传输；没有 remote 能力的构建退回 NoOp。 */
    val agentTurnModel: com.swan1127.repland.domain.model.AgentTurnModel = runCatching {
        Class.forName("com.swan1127.repland.data.ai.CompatibleToolChat")
            .getDeclaredConstructor(AiProviderConfigRepository::class.java)
            .newInstance(aiProviderConfigRepository) as com.swan1127.repland.domain.model.AgentTurnModel
    }.getOrDefault(com.swan1127.repland.domain.model.NoOpAgentTurnModel)
    val planDraftGenerator: PlanDraftGenerator = PlanGenerator
    val numericProfileAdvisor: com.swan1127.repland.domain.model.NumericProfileAdvisor = runCatching {
        Class.forName("com.swan1127.repland.data.ai.CompatibleNumericProfileAdvisor")
            .getDeclaredConstructor(AiProviderConfigRepository::class.java)
            .newInstance(aiProviderConfigRepository) as com.swan1127.repland.domain.model.NumericProfileAdvisor
    }.getOrDefault(com.swan1127.repland.domain.model.NoOpNumericProfileAdvisor)
    val usageSummaryAdvisor: com.swan1127.repland.domain.model.UsageSummaryAdvisor = runCatching {
        Class.forName("com.swan1127.repland.data.ai.CompatibleUsageSummaryAdvisor")
            .getDeclaredConstructor(AiProviderConfigRepository::class.java, AiSettingsRepository::class.java)
            .newInstance(aiProviderConfigRepository, aiSettingsRepository) as com.swan1127.repland.domain.model.UsageSummaryAdvisor
    }.getOrDefault(com.swan1127.repland.domain.model.NoOpUsageSummaryAdvisor)
    val planningReadService = com.swan1127.repland.domain.model.PlanningReadService(taskRepository, timeRepository, planRepository, categoryPreferenceRepository)
    val planningOperationService = com.swan1127.repland.domain.model.PlanningOperationService(
        planningReadService, planRepository, planDraftGenerator, executionSessionRepository, numericProfileRepository,
        aiAdvisor = aiAdvisor, aiSettings = aiSettingsRepository, profileEvidence = profileEvidenceRepository,
        memoryReference = auxiliaryStore::memoryReference,
        executionLogs = { date -> taskRepository.observeExecutionLogsForDate(date).first() },
    )
    /** Local bounded workflow; it has no repository write capability. */
    val planningAgentWorkflow = PlanningAgentWorkflow(
        planningAgent = PlanningAgent(aiAdvisor, planDraftGenerator),
    )
    val timetableImporter = PdfTimetableImporter(context)
}
