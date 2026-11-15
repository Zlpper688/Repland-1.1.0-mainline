package com.swan1127.repland

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swan1127.repland.reminders.LocalReminderScheduler
import com.swan1127.repland.ui.ReplandApp
import com.swan1127.repland.ui.theme.ReplandEmphasized
import com.swan1127.repland.ui.theme.ReplandMotion
import com.swan1127.repland.ui.plan.PlanViewModel
import com.swan1127.repland.ui.preferences.CategoryPreferenceViewModel
import com.swan1127.repland.ui.reminders.ReminderSettingsViewModel
import com.swan1127.repland.ui.profile.ProfileEvidenceViewModel
import com.swan1127.repland.ui.data.DataManagementViewModel
import com.swan1127.repland.ui.ai.PlanningAgentViewModel
import com.swan1127.repland.ui.ai.AiProviderConfigViewModel
import com.swan1127.repland.ui.agent.ArrangementAssistantViewModel
import com.swan1127.repland.ui.time.TimeViewModel
import com.swan1127.repland.ui.tasks.TaskViewModel
import com.swan1127.repland.ui.theme.ReplandTheme
import com.swan1127.repland.ui.engagement.EngagementViewModel

class MainActivity : ComponentActivity() {
    /** 每日回顾 deep link：计数器只在「新」intent 带 extra 时自增，旋转重建不会重复打开。 */
    private val dailyReviewRequest = mutableStateOf(0)
    private var handledRequestIntentHash = 0

    override fun onStop() {
        super.onStop()
        com.swan1127.repland.ui.widget.HomeWidgetProvider.requestRefresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDailyReviewRequest()
    }

    private fun handleDailyReviewRequest() {
        val intent = intent ?: return
        if (!intent.getBooleanExtra(LocalReminderScheduler.EXTRA_OPEN_DAILY_REVIEW, false)) return
        if (intent.hashCode() == handledRequestIntentHash) return
        handledRequestIntentHash = intent.hashCode()
        dailyReviewRequest.value = dailyReviewRequest.value + 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDailyReviewRequest()
        val appContainer = (application as ReplandApplication).appContainer
        setContent {
            ReplandTheme {
                // 冷启动：从系统 splash 纸面交叉淡入，品牌进场不硬切；旋转重建不重放。
                val entered = rememberSaveable { mutableStateOf(false) }
                val enterAlpha = remember { Animatable(if (entered.value) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (!entered.value) {
                        enterAlpha.animateTo(1f, tween(durationMillis = ReplandMotion.Ink, easing = ReplandEmphasized))
                        entered.value = true
                    }
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = enterAlpha.value }) {
                val taskViewModel: TaskViewModel = viewModel(
                    factory = TaskViewModel.Factory(
                        appContainer.taskRepository,
                        appContainer.executionSessionRepository,
                        appContainer.teamPersonalBridge,
                    ),
                )
                val taskCaptureViewModel: com.swan1127.repland.ui.tasks.TaskCaptureViewModel = viewModel(
                    factory = com.swan1127.repland.ui.tasks.TaskCaptureViewModel.Factory(appContainer.taskCaptureRepository),
                )
                val timeViewModel: TimeViewModel = viewModel(
                    factory = TimeViewModel.Factory(
                        appContainer.timeRepository,
                        appContainer.timetableImporter,
                    ),
                )
                val planViewModel: PlanViewModel = viewModel(
                    factory = PlanViewModel.Factory(
                        appContainer.planRepository,
                        appContainer.planDraftGenerator,
                        appContainer.timeRepository,
                        appContainer.taskRepository,
                        appContainer.planningOperationService,
                    ),
                )
                val categoryPreferenceViewModel: CategoryPreferenceViewModel = viewModel(
                    factory = CategoryPreferenceViewModel.Factory(appContainer.categoryPreferenceRepository),
                )
                val reminderSettingsViewModel: ReminderSettingsViewModel = viewModel(
                    factory = ReminderSettingsViewModel.Factory(appContainer.reminderSettingsRepository),
                )
                val profileEvidenceViewModel: ProfileEvidenceViewModel = viewModel(
                    factory = ProfileEvidenceViewModel.Factory(appContainer.profileEvidenceRepository),
                )
                val usageSummaryViewModel: com.swan1127.repland.ui.profile.UsageSummaryViewModel = viewModel(
                    factory = com.swan1127.repland.ui.profile.UsageSummaryViewModel.Factory(appContainer.usageSummaryRepository,
                        appContainer.aiSettingsRepository, appContainer.aiProviderConfigRepository, appContainer.usageSummaryAdvisor,
                        appContainer.usageSummaryAdvisor !== com.swan1127.repland.domain.model.NoOpUsageSummaryAdvisor),
                )
                val personalProfileViewModel: com.swan1127.repland.ui.profile.PersonalProfileViewModel = viewModel(
                    factory = com.swan1127.repland.ui.profile.PersonalProfileViewModel.Factory(appContainer.personalProfileStore),
                )
                val dataManagementViewModel: DataManagementViewModel = viewModel(
                    factory = DataManagementViewModel.Factory(appContainer.dataManagementRepository),
                )
                val planningAgentViewModel: PlanningAgentViewModel = viewModel(
                    factory = PlanningAgentViewModel.Factory(
                        appContainer.aiSettingsRepository,
                        appContainer.planningAgentWorkflow,
                    ),
                )
                val aiProviderConfigViewModel: AiProviderConfigViewModel = viewModel(
                    factory = AiProviderConfigViewModel.Factory(
                        appContainer.aiProviderConfigRepository,
                        appContainer.arrangementAssistantAdvisor,
                    ),
                )
                val arrangementAssistantViewModel: ArrangementAssistantViewModel = viewModel(
                    factory = ArrangementAssistantViewModel.Factory(
                        appContainer.aiSettingsRepository,
                        appContainer.arrangementAssistantAdvisor,
                        appContainer.planningReadService,
                    ),
                )
                val engagementViewModel: EngagementViewModel = viewModel(
                    factory = EngagementViewModel.Factory(appContainer.engagementRepository),
                )
                val autonomyViewModel: com.swan1127.repland.ui.agent.AutonomyViewModel = viewModel(
                    factory = com.swan1127.repland.ui.agent.AutonomyViewModel.Factory(
                        appContainer.autonomyRepository, appContainer.planningReadService, appContainer.planningOperationService),
                )
                val numericProfileViewModel: com.swan1127.repland.ui.profile.NumericProfileViewModel = viewModel(
                    factory = com.swan1127.repland.ui.profile.NumericProfileViewModel.Factory(
                        appContainer.numericProfileRepository,
                        appContainer.taskRepository,
                        appContainer.aiSettingsRepository,
                        appContainer.aiProviderConfigRepository,
                        appContainer.numericProfileAdvisor,
                        appContainer.numericProfileAdvisor !== com.swan1127.repland.domain.model.NoOpNumericProfileAdvisor,
                    ),
                )
                val teamViewModel: com.swan1127.repland.ui.team.TeamViewModel = viewModel(
                    factory = com.swan1127.repland.ui.team.TeamViewModel.Factory(
                        appContainer.teamStore, appContainer.backendConfigStore, appContainer.humanProfileStore,
                        {
                            appContainer.backendConfigStore.buildNotifier()
                        },
                        appContainer.teamPersonalBridge,
                        appContainer.planRepository,
                        appContainer.timeRepository,
                        appContainer.agentTurnModel,
                        appContainer.teamRosterStore,
                        appContainer.personCardStore,
                        appContainer.competitionCatalogStore,
                        appContainer.competitionTrackingRepository,
                        appContainer.competitionAwardRepository,
                        appContainer.competitionReminderScheduler,
                        appContainer.competitionWebFetcher,
                        { appContainer.personaDocStore.load() },
                    ),
                )
                val weatherViewModel: com.swan1127.repland.ui.weather.WeatherViewModel = viewModel(
                    factory = com.swan1127.repland.ui.weather.WeatherViewModel.Factory(
                        appContainer.weatherRepository,
                        appContainer.weatherSource,
                        appContainer.weatherPlaceSearch,
                    ),
                )
                ReplandApp(
                    taskViewModel,
                    timeViewModel,
                    planViewModel,
                    categoryPreferenceViewModel,
                    reminderSettingsViewModel,
                    profileEvidenceViewModel,
                    dataManagementViewModel,
                    planningAgentViewModel,
                    aiProviderConfigViewModel,
                    arrangementAssistantViewModel,
                    engagementViewModel,
                    taskCaptureViewModel,
                    personalProfileViewModel = personalProfileViewModel,
                    autonomyViewModel = autonomyViewModel,
                    usageSummaryViewModel = usageSummaryViewModel,
                    numericProfileViewModel = numericProfileViewModel,
                    teamViewModel = teamViewModel,
                    agentChatModel = appContainer.agentChatModel,
                    agentTurnModel = appContainer.agentTurnModel,
                    agentWriteUndo = appContainer.agentWriteUndo,
                    weatherViewModel = weatherViewModel,
                    dailyReviewRequest = dailyReviewRequest,
                )
                }
            }
        }
    }
}
