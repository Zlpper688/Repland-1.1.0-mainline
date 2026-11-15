package com.swan1127.repland.ui
import com.swan1127.repland.domain.model.ManualPlanChange

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import com.swan1127.repland.ui.components.DesignDialog as AlertDialog
import com.swan1127.repland.ui.components.DesignButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import com.swan1127.repland.ui.theme.LocalReplandVisuals
import com.swan1127.repland.ui.theme.MotionPage
import com.swan1127.repland.ui.theme.MotionDisclosure
import com.swan1127.repland.ui.theme.LocalReducedMotion
import com.swan1127.repland.ui.theme.ReplandMotion
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.swan1127.repland.ui.components.DesignIcons
import com.swan1127.repland.ui.components.CapsuleKind
import com.swan1127.repland.ui.components.CapsuleRow
import com.swan1127.repland.ui.components.EmptyScene
import com.swan1127.repland.ui.components.CapsuleSpec
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.LabeledTimeWheel
import com.swan1127.repland.ui.components.rememberTimeWheelHold
import androidx.compose.material3.CenterAlignedTopAppBar
import com.swan1127.repland.ui.components.DesignCheckbox as Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.swan1127.repland.ui.components.DesignFilterChip as FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import com.swan1127.repland.ui.components.DesignNavigationItem
import com.swan1127.repland.ui.components.DesignOutlinedButton as OutlinedButton
import com.swan1127.repland.ui.components.DesignTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import com.swan1127.repland.ui.components.DesignSwitch as Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.swan1127.repland.ui.components.DesignTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.swan1127.repland.R
import com.swan1127.repland.data.AuxiliaryStore
import com.swan1127.repland.ui.components.LandscapeArtwork
import com.swan1127.repland.ui.components.LandscapeStyle
import com.swan1127.repland.ui.components.rememberLandscapeSurface
import com.swan1127.repland.ui.components.LearningMemorySettings
import com.swan1127.repland.ui.plan.PlanningTeamLogPanel
import com.swan1127.repland.ui.plan.PlanningTeamStatusDialog
import com.swan1127.repland.ui.notifications.NotificationAccess
import com.swan1127.repland.ui.notifications.refusalReason
import com.swan1127.repland.ui.notifications.rememberNotificationAccess
import com.swan1127.repland.domain.model.ClassPeriod
import com.swan1127.repland.domain.model.ClassPeriodClock
import com.swan1127.repland.domain.model.AiAdvisorFailureReason
import com.swan1127.repland.domain.model.AiProviderConfig
import com.swan1127.repland.domain.model.AiAdvisorRequest
import com.swan1127.repland.domain.model.AiAdvisorResponse
import com.swan1127.repland.domain.model.AiDailySummaryRequest
import com.swan1127.repland.domain.model.AiDailySummaryAdvice
import com.swan1127.repland.domain.model.AiDifficultyAndDurationAdvice
import com.swan1127.repland.domain.model.AiDifficultyAndDurationRequest
import com.swan1127.repland.domain.model.AiReplanRequest
import com.swan1127.repland.domain.model.AiSortingExplanationAdvice
import com.swan1127.repland.domain.model.AiSortingExplanationRequest
import com.swan1127.repland.domain.model.AiTaskBreakdownAdvice
import com.swan1127.repland.domain.model.AiTaskBreakdownRequest
import com.swan1127.repland.domain.model.AiTaskUnderstandingAdvice
import com.swan1127.repland.domain.model.AiTaskUnderstandingRequest
import com.swan1127.repland.domain.model.ConfirmedPlan
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.DateOverrideDraft
import com.swan1127.repland.domain.model.DateOverrideType
import com.swan1127.repland.domain.model.DailyReviewSummarizer
import com.swan1127.repland.domain.model.ExecutionLogEventType
import com.swan1127.repland.domain.model.ExecutionOutcome
import com.swan1127.repland.domain.model.ExecutionSession
import com.swan1127.repland.domain.model.RecallGrade
import com.swan1127.repland.domain.model.ImportedCourse
import com.swan1127.repland.domain.model.PlanDraft
import com.swan1127.repland.domain.model.PlanDraftEditor
import com.swan1127.repland.domain.model.PlanGenerationInput
import com.swan1127.repland.domain.model.LocalPriorityAssessment
import com.swan1127.repland.domain.model.PriorityReason
import com.swan1127.repland.domain.model.PriorityReasonKind
import com.swan1127.repland.domain.model.RhythmTrack
import com.swan1127.repland.domain.model.PlanningRevision
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.SegmentAuthority
import com.swan1127.repland.domain.model.PlanningAgentState
import com.swan1127.repland.domain.model.PlanningAgentRequestType
import com.swan1127.repland.domain.model.LocalPlanningFallback
import com.swan1127.repland.domain.model.ProfileEvidence
import com.swan1127.repland.domain.model.ProfileEvidenceScope
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskDraft
import com.swan1127.repland.domain.model.TaskDraftValidator
import com.swan1127.repland.domain.model.TaskName
import com.swan1127.repland.domain.model.TaskExecutionLog
import com.swan1127.repland.domain.model.TaskFeedback
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.TimeBlockValidator
import com.swan1127.repland.domain.model.ScheduleTimeline
import com.swan1127.repland.domain.model.PlanningConstraintValidator
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.TodayFocus
import com.swan1127.repland.domain.model.EngagementMode
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.domain.model.WeeklyTimeBlockDraft
import com.swan1127.repland.domain.model.UnscheduledReason
import com.swan1127.repland.domain.model.PlanDraftReview
import com.swan1127.repland.ui.time.TimeViewModel
import com.swan1127.repland.ui.time.TimeMutationKind
import androidx.compose.runtime.key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.swan1127.repland.ui.time.TimetableImportState
import com.swan1127.repland.ui.plan.PlanViewModel
import com.swan1127.repland.ui.preferences.CategoryPreferenceViewModel
import com.swan1127.repland.ui.reminders.ReminderSettingsViewModel
import com.swan1127.repland.ui.profile.ProfileEvidenceViewModel
import com.swan1127.repland.ui.data.DataManagementResult
import com.swan1127.repland.ui.data.DataManagementViewModel
import com.swan1127.repland.ui.ai.PlanningAgentViewModel
import com.swan1127.repland.ui.ai.AiProviderConfigViewModel
import com.swan1127.repland.ui.ai.AiProviderConnectionTest
import com.swan1127.repland.ui.agent.ArrangementAssistantViewModel
import com.swan1127.repland.ui.tasks.TaskViewModel
import com.swan1127.repland.ui.tasks.BatchTaskAction
import com.swan1127.repland.ui.tasks.BatchTasks
import com.swan1127.repland.ui.tasks.TaskMutationKind
import com.swan1127.repland.ui.tasks.TaskSelection
import com.swan1127.repland.ui.tasks.FeedbackFormValidation
import com.swan1127.repland.ui.schedule.MonthScheduleView
import com.swan1127.repland.ui.schedule.ScheduleCreateCard
import com.swan1127.repland.ui.schedule.ScheduleDateSheet
import com.swan1127.repland.ui.schedule.ScheduleStackCard
import com.swan1127.repland.ui.schedule.scheduleDateHeadline
import com.swan1127.repland.ui.schedule.TimelineDashboard
import com.swan1127.repland.ui.schedule.TimelineEventObject
import com.swan1127.repland.ui.schedule.TimelineTimeEditorDialog
import com.swan1127.repland.ui.schedule.WeekScheduleView
import com.swan1127.repland.ui.engagement.EngagementViewModel
import com.swan1127.repland.ui.components.EditorSheet
import com.swan1127.repland.ui.components.AssistantSheet
import com.swan1127.repland.ui.components.TaskCaptureSheet
import com.swan1127.repland.ui.components.TaskDatePickerDialog
import com.swan1127.repland.ui.components.TaskRow
import com.swan1127.repland.ui.components.SelectionDrag
import com.swan1127.repland.ui.components.TaskRowEmphasis
import com.swan1127.repland.ui.components.PlannerIcons
import com.swan1127.repland.ui.components.taskDateLabel
import com.swan1127.repland.reminders.FocusProgressNotifier
import com.swan1127.repland.reminders.LocalReminderScheduler
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

private data class LifecycleLogLookup(
    val logs: List<TaskExecutionLog> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
)

private enum class AppTab(
    @param:StringRes val titleRes: Int,
    @param:StringRes val shortRes: Int,
) {
    TODAY(R.string.today_title, R.string.tab_today),
    TASKS(R.string.tasks_title, R.string.tab_tasks),
    // AGENT 页面已下线（安排助手并入「今天」页的捕获后置流与群聊）。
    // 枚举值必须保留：selectedTab 走 rememberSaveable，进程恢复按名字反序列化，
    // 删值会让旧存档里可能残留的 "AGENT" 在恢复时直接崩溃。所有分支仅服务于兼容，入口已全部移除。
    AGENT(R.string.agent_title, R.string.tab_agent),
    TEAM(R.string.team_title, R.string.tab_team),
    TIME(R.string.time_title, R.string.tab_time),
    MINE(R.string.mine_title, R.string.tab_mine),
}


@Composable
private fun DataReadNotice(message: String, onRetry: () -> Unit, canRetry: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("data-read-notice")) {
        if (LocalDensity.current.fontScale > 1.3f) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onRetry, enabled = canRetry,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("retry-data-read")) { Text("重试读取") }
            }
        } else Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry, enabled = canRetry, modifier = Modifier.testTag("retry-data-read")) { Text("重试读取") }
        }
    }
}

private const val POSTPONEMENT_ADVICE_THRESHOLD = 3

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun ReplandApp(
    taskViewModel: TaskViewModel,
    timeViewModel: TimeViewModel,
    planViewModel: PlanViewModel,
    categoryPreferenceViewModel: CategoryPreferenceViewModel,
    reminderSettingsViewModel: ReminderSettingsViewModel,
    profileEvidenceViewModel: ProfileEvidenceViewModel,
    dataManagementViewModel: DataManagementViewModel,
    planningAgentViewModel: PlanningAgentViewModel,
    aiProviderConfigViewModel: AiProviderConfigViewModel,
    arrangementAssistantViewModel: ArrangementAssistantViewModel,
    engagementViewModel: EngagementViewModel,
    taskCaptureViewModel: com.swan1127.repland.ui.tasks.TaskCaptureViewModel,
    personalProfileViewModel: com.swan1127.repland.ui.profile.PersonalProfileViewModel? = null,
    autonomyViewModel: com.swan1127.repland.ui.agent.AutonomyViewModel? = null,
    usageSummaryViewModel: com.swan1127.repland.ui.profile.UsageSummaryViewModel? = null,
    numericProfileViewModel: com.swan1127.repland.ui.profile.NumericProfileViewModel? = null,
    teamViewModel: com.swan1127.repland.ui.team.TeamViewModel? = null,
    agentChatModel: com.swan1127.repland.domain.model.AgentChatModel? = null,
    agentTurnModel: com.swan1127.repland.domain.model.AgentTurnModel? = null,
    agentWriteUndo: com.swan1127.repland.domain.model.AgentWriteUndo? = null,
    weatherViewModel: com.swan1127.repland.ui.weather.WeatherViewModel? = null,
    /** MainActivity 收到「打开每日回顾」deep link 时自增的计数；>0 且变化时打开回顾决策面。 */
    dailyReviewRequest: androidx.compose.runtime.MutableState<Int>? = null,
) {
    // §13 进入门槛即登录界面：验证码和微信扫码在门上完成。
    val gateVm = teamViewModel
    if (gateVm != null && gateVm.sessionGate.collectAsState().value) {
        com.swan1127.repland.ui.team.SessionGateScreen(
            vm = gateVm,
            onOffline = { gateVm.proceedOffline() },
            onWechatProfile = { login ->
                personalProfileViewModel?.importWechatProfile(login.displayName, login.avatarUrl, gateVm::downloadWechatAvatar)
            },
        )
        return
    }
    val noAutonomy = remember { kotlinx.coroutines.flow.MutableStateFlow(com.swan1127.repland.ui.agent.AutonomyUiState()) }
    val autonomy by (autonomyViewModel?.state ?: noAutonomy).collectAsStateWithLifecycle()
    var showGroupChat by rememberSaveable { mutableStateOf(false) }
    var showTeamChat by rememberSaveable { mutableStateOf(false) }
    val taskCaptureUiState by taskCaptureViewModel.uiState.collectAsStateWithLifecycle()
    val captureLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(captureLifecycle, autonomyViewModel) {
        captureLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // 定时触发只剩跨天这一件事：睡到下一个零点再醒。外部输入变化的即时响应
                // 交给 observeInputRevision 的去抖流；固定轮询只会把引擎自己的写入喂回引擎。
                val now = LocalDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
                delay(java.time.Duration.between(now, nextMidnight).toMillis() + 1_000)
                autonomyViewModel?.onForeground()
            }
        }
    }
    DisposableEffect(captureLifecycle, taskCaptureViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) taskCaptureViewModel.retain()
            if (event == Lifecycle.Event.ON_RESUME) autonomyViewModel?.onForeground()
        }
        captureLifecycle.addObserver(observer)
        onDispose { captureLifecycle.removeObserver(observer) }
    }
    val captureSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val feedbackSnackbarScope = rememberCoroutineScope()
    // 引擎的回执终于有去处：自动调整弹成可撤销的提示条，不再无声改写计划。
    LaunchedEffect(autonomy.responseSerial) {
        if (autonomy.responseSerial == 0L || autonomyViewModel == null) return@LaunchedEffect
        feedbackSnackbarScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val result = captureSnackbar.showSnackbar(
                autonomy.response.orEmpty(),
                actionLabel = if (autonomy.delegation.canUndo) "撤销" else null,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) autonomyViewModel.undo()
        }
    }
    var snackbarHeightPx by remember { mutableStateOf(0) }
    var pageContentBottomPx by remember { mutableStateOf(0f) }
    var pageSnackbarTopPx by remember { mutableStateOf(0f) }
    val snackbarBottomInset = with(LocalDensity.current) { snackbarHeightPx.toDp() }
    val taskSnackbarInset = with(LocalDensity.current) {
        if (captureSnackbar.currentSnackbarData != null && pageSnackbarTopPx > 0f)
            (pageContentBottomPx - pageSnackbarTopPx).coerceAtLeast(0f).toDp()
        else 0.dp
    }
    val uiState by taskViewModel.uiState.collectAsStateWithLifecycle()
    val taskMutation by taskViewModel.mutationState.collectAsStateWithLifecycle()
    val taskActionLocked = taskMutation.busy || taskMutation.receipt != null || !uiState.isTrusted
    val executionReadState by taskViewModel.sessionReadState.collectAsStateWithLifecycle()
    val executionSession = executionReadState.value
    val executionBusy by taskViewModel.sessionBusy.collectAsStateWithLifecycle()
    val executionError by taskViewModel.sessionError.collectAsStateWithLifecycle()
    val executionFinished by taskViewModel.sessionFinished.collectAsStateWithLifecycle()
    val totalFocusedMinutes by taskViewModel.totalFocusedMinutes.collectAsStateWithLifecycle()
    var showExecutionSession by rememberSaveable { mutableStateOf(false) }
    var showDailyReview by rememberSaveable { mutableStateOf(false) }
    // 通知 deep link → 每日回顾：MainActivity 在 onCreate/onNewIntent 里自增计数，这里响应变化打开决策面
    val dailyReviewRequestCount = dailyReviewRequest?.value ?: 0
    LaunchedEffect(dailyReviewRequestCount) { if (dailyReviewRequestCount > 0) showDailyReview = true }
    val dailyReviewLogsFlow = remember(showDailyReview) {
        if (showDailyReview) taskViewModel.observeExecutionLogsForDate(LocalDate.now())
            .catch { emit(emptyList()) }
        else flowOf(emptyList())
    }
    val dailyReviewLogs by dailyReviewLogsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // 同时只允许一轮专注。换任务时要先处理旧的一轮，绝不能把旧的一轮直接当成新的弹出来。
    var focusAfterFinish by remember { mutableStateOf<(() -> Unit)?>(null) }
    var focusConflict by remember { mutableStateOf<(() -> Unit)?>(null) }
    var focusConflictTitle by remember { mutableStateOf<String?>(null) }
    val timeUiState by timeViewModel.uiState.collectAsStateWithLifecycle()
    val timeMutation by timeViewModel.mutationState.collectAsStateWithLifecycle()
    val planUiState by planViewModel.uiState.collectAsStateWithLifecycle()
    val workspaceUiState by planViewModel.workspaceUiState.collectAsStateWithLifecycle()
    val categoryPreferenceUiState by categoryPreferenceViewModel.uiState.collectAsStateWithLifecycle()
    val reminderSettingsUiState by reminderSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val dataManagementUiState by dataManagementViewModel.uiState.collectAsStateWithLifecycle()
    val planningAgentUiState by planningAgentViewModel.uiState.collectAsStateWithLifecycle()
    val aiProviderConfigUiState by aiProviderConfigViewModel.uiState.collectAsStateWithLifecycle()
    val planningReadsReady = uiState.isTrusted && timeUiState.isTrusted && categoryPreferenceUiState.isTrusted &&
        planUiState.isTrusted && workspaceUiState.isTrusted && executionReadState.isTrusted
    val taskReadMessage = if (uiState.isLoading && uiState.hasLoaded) "正在重新读取任务…" else uiState.readError
    val timeReadMessage = if (timeUiState.isLoading && timeUiState.hasLoaded) "正在重新读取时间设置…" else timeUiState.readError
    val planReadMessage = if (planUiState.isLoading && planUiState.hasLoaded) "正在重新读取计划…" else planUiState.readError
    val workspaceReadMessage = if (workspaceUiState.isLoading && workspaceUiState.hasLoaded) "正在重新读取轨道与助手草稿…" else workspaceUiState.readError
    val preferenceReadMessage = if (categoryPreferenceUiState.isLoading && categoryPreferenceUiState.hasLoaded) "正在重新读取类别偏好…" else categoryPreferenceUiState.readError
    val sessionReadMessage = if (executionReadState.isLoading && executionReadState.hasLoaded) "正在重新读取专注记录…" else executionReadState.error
    val readMessage = listOfNotNull(taskReadMessage, timeReadMessage, planReadMessage, workspaceReadMessage, preferenceReadMessage, sessionReadMessage)
        .joinToString("\n").ifBlank { null }
    val canRetryReads = !uiState.isLoading && !timeUiState.isLoading && !planUiState.isLoading &&
        !workspaceUiState.isLoading && !categoryPreferenceUiState.isLoading && !executionReadState.isLoading
    val retryReads: () -> Unit = { taskViewModel.retryRead(); taskViewModel.retrySessionRead(); timeViewModel.retryRead(); planViewModel.retryRead(); categoryPreferenceViewModel.retryRead() }
    val arrangementAssistantAccess by arrangementAssistantViewModel.access.collectAsStateWithLifecycle()
    val engagementMode by engagementViewModel.mode.collectAsStateWithLifecycle()
    var activeDate by remember { mutableStateOf(LocalDate.now()) }
    // 挑过的日子要活过转屏和重建：用户手里还在那一天的日程上，系统只是换了个配置，
    // 把他扔回今天等于抹掉他刚点进去的那一天。按「挑的当天」记账，所以过了零点这笔自动作废——
    // 主页依旧回到今天，不会停在昨天。
    var pickedOnEpochDay by rememberSaveable { mutableStateOf(-1L) }
    var pickedEpochDay by rememberSaveable { mutableStateOf(-1L) }
    val todayEpochDay = activeDate.toEpochDay()
    val scheduleBrowsedDate =
        if (pickedOnEpochDay == todayEpochDay) LocalDate.ofEpochDay(pickedEpochDay) else activeDate
    val selectScheduleDate: (LocalDate) -> Unit = {
        pickedOnEpochDay = todayEpochDay
        pickedEpochDay = it.toEpochDay()
    }
    var showScheduleDateSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            activeDate = LocalDate.now()
        }
    }
    val context = LocalContext.current
    val auxiliaryStore = remember(context) { AuxiliaryStore(context.applicationContext) }
    // 群聊的行事风格存在这里：卡上点一下换一个风格，下一批就按新的排（工具箱每次求解都读一次）。
    val agentProfileStore = remember(context) { com.swan1127.repland.data.AgentProfileStore(context.applicationContext) }
    // 「关于我」自述：用户亲口写的第一人称画像（用户资产）。喂给个人/队伍助手，排在推测记忆前面；
    // 不是排期输入，不进 Room。与 AppContainer.personaDocStore 指向同一份 prefs，读写一致。
    val personaDocStore = remember(context) { com.swan1127.repland.data.PersonaDocStore(context.applicationContext) }
    var personaDoc by remember { mutableStateOf(personaDocStore.load()) }
    var showPersonaEditor by remember { mutableStateOf(false) }
    // 助手日记：群聊主动简报的落盘留痕。与 AppContainer.assistantJournalStore 指向同一份文件。
    val assistantJournalStore = remember(context) { com.swan1127.repland.data.AssistantJournalStore(context.applicationContext) }
    // 内容画像：模型读真实任务与日记后的理解。材料、授权、结论都留在本机；
    // 只有用户点过「同意发送这些内容」之后，下一次生成才会把材料发出去。
    val contentPortraitStore = remember(context) { com.swan1127.repland.data.ContentPortraitStore(context.applicationContext) }
    var contentPortrait by remember { mutableStateOf(com.swan1127.repland.domain.model.ContentPortrait()) }
    var portraitBusy by remember { mutableStateOf(false) }
    var portraitError by remember { mutableStateOf<String?>(null) }
    var portraitJournal by remember { mutableStateOf<List<com.swan1127.repland.data.JournalEntry>>(emptyList()) }
    val portraitScope = rememberCoroutineScope()
    LaunchedEffect(contentPortraitStore) { contentPortrait = contentPortraitStore.load() }
    LaunchedEffect(assistantJournalStore) { portraitJournal = assistantJournalStore.load().entries }

    fun portraitMaterial(): List<com.swan1127.repland.domain.model.PortraitMaterialLine> {
        val zone = java.time.ZoneId.systemDefault()
        val cutoff = LocalDate.now().minusDays(30)
        fun taskDate(task: com.swan1127.repland.domain.model.Task): LocalDate =
            java.time.Instant.ofEpochMilli(task.updatedAtEpochMillis).atZone(zone).toLocalDate()
        val taskLines = uiState.tasks
            .filter { !it.isTeamMirror }
            .filter { it.status.isActive || taskDate(it) >= cutoff }
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(40)
            .map { task ->
                buildString {
                    append("任务「${task.displayName}」")
                    append(" · ${task.category.portraitLabel()} · ${task.status.portraitLabel()}")
                    task.totalDurationMinutes?.let { append(" · 预计 $it 分钟") }
                    task.scheduledForDate?.let { append(" · 计划在 $it") }
                    task.actualDurationMinutes?.let { append(" · 实际 $it 分钟") }
                    task.completionSummary?.takeIf(String::isNotBlank)?.let { append(" · 收尾记录：${it.take(80)}") }
                }
            }
        val journalLines = portraitJournal
            .filter { it.date >= cutoff }
            .takeLast(20)
            .map { entry -> "日记 ${entry.date}「${entry.topic}」：${entry.summary.take(120)}" }
        return com.swan1127.repland.domain.model.ContentPortraitComposer.numbered(taskLines + journalLines)
    }

    fun saveContentPortrait(next: com.swan1127.repland.domain.model.ContentPortrait) {
        contentPortrait = next
        portraitScope.launch { contentPortraitStore.save(next) }
    }

    fun generateContentPortrait() {
        if (portraitBusy) return
        val material = portraitMaterial()
        if (material.size < 3) {
            portraitError = "记录还不够：先有几件任务或几条日记再来"
            return
        }
        val model = agentChatModel ?: com.swan1127.repland.domain.model.NoOpAgentChatModel
        portraitBusy = true
        portraitError = null
        portraitScope.launch {
            try {
                portraitJournal = assistantJournalStore.load().entries
                when (val result = model.say(
                    com.swan1127.repland.domain.model.ContentPortraitComposer.systemPrompt(),
                    com.swan1127.repland.domain.model.ContentPortraitComposer.userPrompt(material, 30),
                )) {
                    is com.swan1127.repland.domain.model.AgentChatResult.Said -> {
                        val points = com.swan1127.repland.domain.model.ContentPortraitComposer.parse(result.text, material)
                        if (points == null) {
                            portraitError = "这次没有读出挂得住依据的结论，原内容保留"
                        } else {
                            saveContentPortrait(
                                contentPortrait.copy(
                                    version = contentPortrait.version + 1,
                                    updatedAtMillis = System.currentTimeMillis(),
                                    consentAtMillis = contentPortrait.consentAtMillis ?: System.currentTimeMillis(),
                                    points = points,
                                    evidence = com.swan1127.repland.domain.model.ContentPortraitComposer.evidenceFor(points, material),
                                ),
                            )
                        }
                    }
                    is com.swan1127.repland.domain.model.AgentChatResult.Failed ->
                        portraitError = "模型没有接上：${result.reason.name}"
                }
            } finally {
                portraitBusy = false
            }
        }
    }
    var memoryRevision by remember { mutableStateOf(0) }
    val learningMemory = remember(auxiliaryStore, memoryRevision, activeDate) {
        auxiliaryStore.learningMemorySnapshot()
    }
    val teamMemory = remember(auxiliaryStore, memoryRevision, activeDate) {
        Triple(
            auxiliaryStore.teamMemoryFacts(),
            auxiliaryStore.memoryProfileSummary(activeDate),
            auxiliaryStore.teamMemoryEpisodes(),
        )
    }
    var finishedRound by remember { mutableStateOf<Pair<String, RecallGrade>?>(null) }

    /** 一轮专注本身就是回忆证据：盖满算想起来，中途收掉算模糊记得。没有要用户打分的入口。 */
    fun recallGradeOf(session: ExecutionSession): RecallGrade {
        val targetMillis = session.targetSeconds * 1_000L
        val covered = targetMillis <= 0L ||
            session.elapsedMillis(System.currentTimeMillis()) * 10 >= targetMillis * 9
        return if (covered) RecallGrade.RECALLED else RecallGrade.FUZZY
    }

    // 本轮执行记录落库后，把里面的完成内容沉淀为记忆，并把这一轮的完成情况当作回忆证据。
    LaunchedEffect(executionFinished, finishedRound) {
        val round = finishedRound
        if (round == null || !executionFinished) return@LaunchedEffect
        val task = uiState.tasks.firstOrNull { it.id == round.first }
        if (task != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val logs = taskViewModel.observeExecutionLogs(task.id).first()
                auxiliaryStore.addReviewPointsFor(task, logs, activeDate)
                auxiliaryStore.recordRecallEvidence(task.id, round.second, activeDate)
            }
            memoryRevision += 1
        }
        finishedRound = null
    }

    // 通知权限不只在设置里要一次：专注、逐事件提醒都是「用户以为会响」的功能，
    // 每一次用到都重新观测一次，缺什么当场要，要不到就如实说。
    val notificationAccess = rememberNotificationAccess()

    /**
     * 一轮只做一件事：点另一项开始专注时，先看旧的一轮怎么处置——
     * 已盖满就直接落账接新的，还在跑就问一句，绝不让旧的一轮顶替新点的那一项。
     */
    fun beginFocus(taskId: String, segmentId: String?, start: () -> Unit) {
        // 专注本身不该被权限挡住，但离开 App 后唯一看得见的就是这条常驻通知——顺手要一次。
        val startRound: () -> Unit = {
            notificationAccess.askIfMissing()
            start()
        }
        val active = executionSession
        when {
            active == null -> {
                startRound()
                showExecutionSession = true
            }

            active.taskId == taskId || (segmentId != null && active.segmentId == segmentId) ->
                showExecutionSession = true

            active.elapsedMillis(System.currentTimeMillis()) >= active.targetSeconds * 1_000L -> {
                focusAfterFinish = startRound
                finishedRound = active.taskId to recallGradeOf(active)
                taskViewModel.finishSession(active.id, ExecutionOutcome.CONTINUE, TaskFeedback())
            }

            else -> {
                focusConflict = startRound
                focusConflictTitle = active.taskTitle
            }
        }
    }

    // 旧一轮落账后，接上用户刚才点的那一项。
    LaunchedEffect(executionSession?.id, focusAfterFinish, executionBusy) {
        val pending = focusAfterFinish ?: return@LaunchedEffect
        if (executionSession == null && !executionBusy) {
            focusAfterFinish = null
            taskViewModel.dismissSessionResult()
            pending()
            showExecutionSession = true
        }
    }
    val notificationPermissionGranted = notificationAccess.access == NotificationAccess.Allowed
    // 「到点会震动并弹出通知」这类话在缺权限时是假话，所以把当下真实的原因挂到每个会说这句话的界面上。
    val notificationAccessNotice = notificationAccess.access.refusalReason().takeIf { it.isNotEmpty() }
    // 关掉永远可以直接写；开启必须先把通知打通，否则写进去的是一个永远不会响的开关。
    val writeEntryReminder: (TimelineEntry, Boolean) -> Unit = { entry, enabled ->
        timeViewModel.resetMutation()
        when {
            entry.id.startsWith("segment:") -> planViewModel.setSegmentReminder(
                entry.id.removePrefix("segment:"), enabled,
            )

            entry.id.startsWith("weekly:") -> timeViewModel.setWeeklyBlockReminder(
                entry.id.removePrefix("weekly:"), enabled,
            )

            entry.id.startsWith("override:") -> timeViewModel.setDateOverrideReminder(
                entry.id.removePrefix("override:"), enabled,
            )
        }
        com.swan1127.repland.ui.widget.HomeWidgetProvider.requestRefresh(context)
    }
    // 专注期间常驻一条进度通知：切到别的应用也能在通知栏看到当前事项和剩余时间，并就地完成或退出。
    // 这里绕开页面上按生命周期取的状态，直接订阅会话流——在通知栏收尾的那一轮要马上收掉这条通知。
    val focusNotifier = remember(context) { FocusProgressNotifier(context.applicationContext) }
    LaunchedEffect(taskViewModel) {
        taskViewModel.sessionReadState.collectLatest { read ->
            val session = read.value.takeIf { !read.isLoading && read.error == null }
            if (session == null) {
                focusNotifier.clear()
                return@collectLatest
            }
            while (true) {
                focusNotifier.publish(session, System.currentTimeMillis())
                delay(30_000)
            }
        }
    }
    val reminderScheduler = remember(context) { LocalReminderScheduler(context.applicationContext) }
    var exactAlarmsGranted by remember { mutableStateOf(context.canScheduleExactAlarmsCompat()) }
    var batteryAskPending by remember { mutableStateOf(false) }
    var batteryExempted by remember {
        mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
    }
    var micGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val voiceServiceAvailable = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    // 电池管家在后台掐精确闹钟是提醒失灵的头号原因：开提醒时申请一次白名单，问过一次就不再纠缠。
    val requestBatteryExemption: () -> Unit = {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val prefs = context.getSharedPreferences("repland-reminders", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("battery_exemption_asked", false) &&
            !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        ) {
            prefs.edit().putBoolean("battery_exemption_asked", true).apply()
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val exactAlarmLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(exactAlarmLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsGranted = context.canScheduleExactAlarmsCompat()
                batteryExempted = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
                micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                // 开提醒那会儿正要跳精确闹钟设置页，电池弹窗等回来再补，两个叠在一起看不见。
                if (batteryAskPending && exactAlarmsGranted) {
                    batteryAskPending = false
                    requestBatteryExemption()
                }
            }
        }
        exactAlarmLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { exactAlarmLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val requestExactAlarmPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmsGranted) {
            batteryAskPending = true
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
    }
    // 货架上用户主动点「去设置」：不再受「问过一次就不纠缠」限制，但已豁免就不再打扰。
    val openBatteryExemption: () -> Unit = {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val localDataExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { destination ->
        destination?.let { uri ->
            dataManagementViewModel.exportTo(
                context.contentResolver,
                uri,
                memorySnapshot = auxiliaryStore.learningMemorySnapshot(),
            )
        }
    }
    var showVoiceComposer by rememberSaveable { mutableStateOf(false) }
    var voiceTranscript by rememberSaveable { mutableStateOf("") }
    var voiceError by rememberSaveable { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull().orEmpty()
            if (words.isNotBlank()) voiceTranscript = words
            else voiceError = true
        }
    }
    val onVoiceCapture: () -> Unit = {
        voiceError = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出想安排的事")
        }
        runCatching { speechLauncher.launch(intent) }.onFailure { voiceError = true }
        Unit
    }
    val tabStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val assistantStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    var showMineSettings by rememberSaveable { mutableStateOf(false) }
    var showShelfAiDialog by rememberSaveable { mutableStateOf(false) }
    var timeReturnTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    // Retain the removed enum value only to recover an older saved navigation state.
    LaunchedEffect(selectedTab, timeReturnTab) {
        // 旧存档恢复时可能带着 AGENT（枚举值因序列化兼容而保留）：落到「今天」，不让死页复活。
        if (selectedTab == AppTab.AGENT) selectedTab = AppTab.TODAY
        if (timeReturnTab == AppTab.AGENT) timeReturnTab = AppTab.TODAY
    }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(usageSummaryViewModel, captureLifecycle, selectedTab, showMineSettings, selectedTaskId) {
        captureLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val profileVisible = selectedTab == AppTab.MINE && !showMineSettings && selectedTaskId == null
            usageSummaryViewModel?.setVisible(profileVisible)
            if (profileVisible) try {
                while (true) {
                    usageSummaryViewModel?.onForeground()
                    delay(30_000)
                }
            } finally {
                usageSummaryViewModel?.setVisible(false)
            }
        }
    }
    var taskEditorTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val taskEditorTarget = taskEditorTargetId?.let { id -> uiState.tasks.firstOrNull { it.id == id } }
    var showTaskEditor by rememberSaveable { mutableStateOf(false) }
    var showQuickAvailability by rememberSaveable { mutableStateOf(false) }
    var pausedPlanDraftIdentity by rememberSaveable { mutableStateOf<String?>(null) }
    var showTaskCapture by rememberSaveable { mutableStateOf(false) }
    // 日程卡 + 直开一页式新增日程（vivo 排布对齐）；事项页的捕获表单保持原样。
    var showScheduleEventSheet by rememberSaveable { mutableStateOf(false) }
    // 捕获后置确认面：捕获落库且未带任何日期时接住它，当面分「排入今天 / 只记入事项库」两组。
    // 会话状态即可，进程重建后回退为只记入（任务本身已落库，不丢）。
    var captureConfirmTask by remember { mutableStateOf<com.swan1127.repland.domain.model.TaskDraft?>(null) }
    var captureConfirmBusy by remember { mutableStateOf(false) }
    // 「自己选时间」分支：就地在确认面上打开手动时间编辑器，写法与排入今天同一条回执通路。
    var captureConfirmPickTime by remember { mutableStateOf(false) }
    var captureConfirmPickError by remember { mutableStateOf<String?>(null) }
    var taskEditorSeed by rememberSaveable { mutableStateOf("") }
    var taskMutationEditorKind by rememberSaveable { mutableStateOf<TaskMutationKind?>(null) }
    var taskMutationEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskMutationLogId by rememberSaveable { mutableStateOf<String?>(null) }
    val taskMutationTarget = uiState.tasks.firstOrNull { it.id == taskMutationEditorId }
    fun openTaskMutation(kind: TaskMutationKind, taskId: String, logId: String? = null) {
        if (!taskActionLocked) {
            taskViewModel.resetMutation()
            taskMutationEditorKind = kind; taskMutationEditorId = taskId; taskMutationLogId = logId
        }
    }
    val closeTaskMutation: () -> Unit = {
        if (!taskMutation.busy) {
            taskMutationEditorKind = null; taskMutationEditorId = null; taskMutationLogId = null
            taskViewModel.resetMutation()
        }
    }
    var weeklyBlockEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    val weeklyBlockEditorTarget = weeklyBlockEditorId?.let { id -> timeUiState.weeklyBlocks.firstOrNull { it.id == id } }
    var showWeeklyBlockEditor by rememberSaveable { mutableStateOf(false) }
    var weeklyBlockInitialKind by rememberSaveable { mutableStateOf(TimeBlockKind.COURSE) }
    var dateOverrideEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    val dateOverrideEditorTarget = dateOverrideEditorId?.let { id -> timeUiState.dateOverrides.firstOrNull { it.id == id } }
    var showDateOverrideEditor by rememberSaveable { mutableStateOf(false) }
    var deletingWeeklyId by rememberSaveable { mutableStateOf<String?>(null) }
    val deletingWeeklyBlock = deletingWeeklyId?.let { id -> timeUiState.weeklyBlocks.firstOrNull { it.id == id } }
    var deletingOverrideId by rememberSaveable { mutableStateOf<String?>(null) }
    val deletingDateOverride = deletingOverrideId?.let { id -> timeUiState.dateOverrides.firstOrNull { it.id == id } }
    var showSemesterStartEditor by rememberSaveable { mutableStateOf(false) }
    var showPlanOverview by rememberSaveable { mutableStateOf(false) }
    var showClearPlanConfirmation by rememberSaveable { mutableStateOf(false) }
    var showClearLocalDataConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAiConsentDialog by rememberSaveable { mutableStateOf(false) }
    var assistantTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAssistant by rememberSaveable { mutableStateOf(false) }
    var lastPlanningAgentTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewingAgentDraft by rememberSaveable { mutableStateOf(false) }
    // 排序撤销不再是常驻按钮：应用草案后，回执条上给一次撤销机会。
    val orderUndoOffer by planViewModel.canOfferOrderUndo.collectAsStateWithLifecycle()
    val selectedTask = selectedTaskId?.let { id -> uiState.tasks.firstOrNull { it.id == id } }
    // Dialog/sheet handlers are composed later and retain their IME/save protection.
    // At page level, native Back has the same destination as the visible arrow.
    BackHandler(enabled = selectedTaskId != null || selectedTab == AppTab.TIME ||
        (selectedTab == AppTab.MINE && showMineSettings)) {
        when {
            selectedTaskId != null -> selectedTaskId = null
            selectedTab == AppTab.TIME -> selectedTab = timeReturnTab
            else -> showMineSettings = false
        }
    }
    val selectedTaskLogs = remember(selectedTaskId) {
        selectedTaskId?.let(taskViewModel::observeExecutionLogs) ?: flowOf(emptyList())
    }
    val executionLogs by selectedTaskLogs.collectAsStateWithLifecycle(initialValue = emptyList())
    val correctionLogsFlow = remember(taskMutationEditorId) {
        taskMutationEditorId?.let { id -> taskViewModel.observeExecutionLogs(id)
            .map { LifecycleLogLookup(logs = it, isLoading = false) }
            .catch { emit(LifecycleLogLookup(isLoading = false, failed = true)) }
        } ?: flowOf(LifecycleLogLookup())
    }
    val correctionLogs by correctionLogsFlow.collectAsStateWithLifecycle(initialValue = LifecycleLogLookup())
    val correctionTarget = correctionLogs.logs.firstOrNull { it.id == taskMutationLogId }
    val planNeedsUpdate = planUiState.currentPlan?.let { plan ->
        uiState.tasks.any { task -> task.updatedAtEpochMillis > plan.createdAtEpochMillis } ||
            timeUiState.weeklyBlocks.any { block -> block.updatedAtEpochMillis > plan.createdAtEpochMillis } ||
            timeUiState.dateOverrides.any { override ->
                override.updatedAtEpochMillis > plan.createdAtEpochMillis
            } || timeUiState.timeConstraintsUpdatedAtEpochMillis > plan.createdAtEpochMillis
    } ?: false
    fun generatePlanDraft(reorder: Boolean = false, orderOnly: Boolean = false, todayOnly: LocalDate? = null) {
        if (!planningReadsReady) {
            feedbackSnackbarScope.launch { captureSnackbar.showSnackbar("任务或时间尚未可靠读取，请先重试；原计划未变。") }
            return
        }
        planViewModel.generateDraft(
            tasks = uiState.tasks,
            weeklyBlocks = timeUiState.weeklyBlocks,
            dateOverrides = timeUiState.dateOverrides,
            semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
            lockedSegments = planUiState.currentPlan?.segments
                ?.filter { segment ->
                    (segment.isLocked || executionSession?.taskId == segment.taskId) && !segment.hasEndedBefore(LocalDateTime.now())
                }
                .orEmpty(),
            categoryPreferences = categoryPreferenceUiState.weights,
            manualTaskOrder = if (reorder) emptyList() else planUiState.taskOrder.ifEmpty { planUiState.currentPlan
                ?.takeIf { it.hasManualTaskOrder }
                ?.orderedTaskIds
                .orEmpty() },
            orderOnly = orderOnly,
            todayOnly = todayOnly,
            reorder = reorder,
        )
    }
    // The arrangement assistant previews against exactly the same projected day
    // that the user sees on the home timeline; it never schedules into a vacuum.
    // 投影写成函数而不是一个值：toolbox 按天 remember，抓走某一天的快照就等于助手自己
    // 写完再读还是旧的一天——同一天的实况会比它点名的别的日子更旧。
    val agentTimelineForDate: (LocalDate) -> List<TimelineEntry> = { date ->
        ScheduleTimeline.entries(
            date = date,
            weeklyBlocks = timeUiState.weeklyBlocks,
            dateOverrides = timeUiState.dateOverrides,
            segments = planUiState.currentPlan?.segments.orEmpty(),
            tasks = uiState.tasks,
            semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
        )
    }
    val agentTimelineEntries = agentTimelineForDate(activeDate)

    // Feedback and future-constraint changes can propose a new plan, but only the user
    // can accept it. The draft itself is deliberately not a key: dismissing a draft
    // must not cause the system to immediately recreate it without a new user change.
    // Changed facts show a review affordance, never an unsolicited modal over execution/editing.

    LaunchedEffect(
        reminderSettingsUiState.preferences.isEnabled,
        notificationPermissionGranted,
        planUiState.currentPlan,
        planUiState.planHistory,
        uiState.tasks,
        timeUiState.weeklyBlocks,
        timeUiState.dateOverrides,
        timeUiState.semesterFirstWeekMonday,
        uiState.isTrusted,
        planUiState.isTrusted,
        timeUiState.isTrusted,
        notificationPermissionGranted,
    ) {
        // Reading failure is not evidence that confirmed tasks disappeared. An
        // explicit reminder switch-off can still cancel reminders safely.
        if ((!uiState.isTrusted || !planUiState.isTrusted || !timeUiState.isTrusted) &&
            reminderSettingsUiState.preferences.isEnabled
        ) return@LaunchedEffect
        reminderScheduler.sync(
            isEnabled = reminderSettingsUiState.preferences.isEnabled && notificationPermissionGranted,
            currentPlan = planUiState.currentPlan,
            planHistory = planUiState.planHistory,
            tasks = uiState.tasks,
            weeklyBlocks = timeUiState.weeklyBlocks,
            dateOverrides = timeUiState.dateOverrides,
            semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
        )
    }
    val onReminderEnabledChange: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> reminderSettingsViewModel.setEnabled(false)
            else -> notificationAccess.ensuring {
                reminderSettingsViewModel.setEnabled(true)
                // 精确闹钟要跳系统设置页；不缺这一步就当场弹电池豁免，缺就等从设置页回来再弹。
                if (exactAlarmsGranted) requestBatteryExemption() else requestExactAlarmPermission()
            }
        }
    }
    val onAiEnabledChange: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> planningAgentViewModel.setEnabled(false)
            planningAgentUiState.preferences.hasExplicitConsent -> planningAgentViewModel.setEnabled(true)
            else -> showAiConsentDialog = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        LandscapeArtwork(Modifier.matchParentSize())
    if (selectedTask != null) {
        // 详情页必须自己讲清「排在什么时候」，并且能自己定时间：只写「计划今天」再把
        // 「几点、多久」留给聊天窗，等于把不想打字的人赶回对话里。
        val detailTask = selectedTask
        val detailPlacement = planUiState.currentPlan?.segments
            ?.filter { it.taskId == detailTask.id && it.date >= LocalDate.now() }
            ?.sortedWith(compareBy({ it.date }, { it.startMinute }))
            ?.firstOrNull()
        MotionPage(targetKey = selectedTask.id, lateral = true) { _ ->
        TaskDetailScreen(
            task = selectedTask,
            executionLogs = executionLogs,
            actionsLocked = taskActionLocked,
            savingRecord = taskMutation.busy,
            snackbarHostState = captureSnackbar,
            snackbarBottomInset = snackbarBottomInset,
            readMessage = readMessage,
            onRetryReads = retryReads,
            onSnackbarSize = { snackbarHeightPx = it },
            onBack = { selectedTaskId = null },
            onStart = { taskId ->
                val segment = agentTimelineEntries.firstOrNull {
                    it.kind == com.swan1127.repland.domain.model.TimelineKind.TASK &&
                        it.taskId == taskId && !it.taskClosed
                }
                if (segment != null) {
                    val focusSegmentId = segment.id.removePrefix("segment:")
                    beginFocus(taskId, focusSegmentId) { taskViewModel.startSession(focusSegmentId) }
                } else {
                    // 没填总时长的任务不替它编一个 30 分钟：一键排入写进时间轴的必须是用户说过的量。
                    val declared = selectedTask.totalDurationMinutes
                    val duration = declared?.let {
                        com.swan1127.repland.domain.model.TaskPlacementPolicy.durationForTask(it)
                    }
                    val clock = java.time.LocalDateTime.now()
                    val floor = if (activeDate == clock.toLocalDate()) {
                        com.swan1127.repland.domain.model.TaskPlacementPolicy.floorMinuteFor(clock)
                    } else {
                        8 * 60
                    }
                    val slot = duration?.let {
                        com.swan1127.repland.domain.model.TaskPlacementPolicy.nextFreeMinute(
                            agentTimelineEntries, floor, it)
                    }
                    if (duration == null || slot == null) {
                        taskViewModel.startTask(taskId)
                        feedbackSnackbarScope.launch {
                            captureSnackbar.showSnackbar(
                                when {
                                    declared == null -> "没填时长 · 已标为进行中"
                                    duration == null -> "时长超出一天 · 已标为进行中"
                                    // 塞不下 ≠ 排满：白天过完了剩下的本来就该睡觉，别把作息说成日程的错。
                                    floor + duration > com.swan1127.repland.domain.model.ChatDayPlanner.SLEEP_START_MINUTE ->
                                        "白天不够 · 已标为进行中"
                                    else -> "这天排不下 · 已标为进行中"
                                },
                            )
                        }
                    } else {
                        planViewModel.placeTask(taskId, activeDate, slot, slot + duration, "focus")
                        // 按钮写的是「开始这件事」，就必须真的开始：只排进时间轴再让用户去点
                        // snackbar，等于把同一个动作拆成两步，还把「进行中」留给了计时器。
                        taskViewModel.startTask(taskId)
                        feedbackSnackbarScope.launch {
                            val result = captureSnackbar.showSnackbar(
                                "已安排到 ${TimeBlockValidator.formatTime(slot)} · 约 $duration 分钟", actionLabel = "开始计时")
                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                beginFocus(taskId, null) { taskViewModel.startSessionForTask(taskId) }
                            }
                        }
                    }
                }
            },
            onEdit = {
                taskViewModel.resetEditorResult()
                taskEditorTargetId = selectedTask.id
                showTaskEditor = true
            },
            placement = detailPlacement,
            defaultDate = activeDate,
            timeWriteBusy = planUiState.isWorking || planUiState.pendingManualChange != null,
            timeWriteError = planUiState.errorMessage,
            onSaveTime = { startMinute, endMinute, date ->
                val targetDate = date ?: detailPlacement?.date ?: activeDate
                val selfId = detailPlacement?.id?.let { "segment:$it" }
                // 判据与日视图调时间完全同一条：任务可以嵌进别的任务，但不能压到课、休息、固定事项。
                val conflicts = agentTimelineForDate(targetDate).any { other ->
                    other.id != selfId &&
                        other.kind != com.swan1127.repland.domain.model.TimelineKind.TASK &&
                        startMinute < other.endMinute && endMinute > other.startMinute
                }
                val validRange = startMinute in 0 until 1_440 && endMinute in 1..1_440 && startMinute < endMinute
                when {
                    !validRange || conflicts -> false
                    detailPlacement != null -> {
                        planViewModel.movePlacement(
                            detailPlacement.id, startMinute, endMinute, detailPlacement.trackId, date = date,
                        )
                        true
                    }
                    else -> {
                        planViewModel.placeTask(detailTask.id, targetDate, startMinute, endMinute, "focus")
                        true
                    }
                }
            },
            onPostpone = { taskViewModel.postponeTask(selectedTask.id, null) },
            onCancel = { openTaskMutation(TaskMutationKind.CANCEL, selectedTask.id) },
            onComplete = { taskViewModel.completeTask(selectedTask.id) },
            onFeedback = { openTaskMutation(TaskMutationKind.FEEDBACK, selectedTask.id) },
            onOpenAssistant = { assistantTaskId = selectedTask.id; showAssistant = true },
            onReplace = { openTaskMutation(TaskMutationKind.REPLACE, selectedTask.id) },
            onRestore = { taskViewModel.restoreTask(selectedTask.id) },
            onCorrectLog = { openTaskMutation(TaskMutationKind.CORRECT, it.taskId, it.id) },
            onReviewAdjustmentDraft = {
                selectedTaskId = null
                selectedTab = AppTab.TODAY
                if (planUiState.draft == null) generatePlanDraft()
            },
            isAiEnabled = planningReadsReady && planningAgentUiState.preferences.isEnabled,
            planningAgentState = if (lastPlanningAgentTaskId == selectedTask.id) {
                planningAgentUiState.workflow
            } else {
                PlanningAgentState.Idle
            },
            onReviewAgentDraft = { draft ->
                planViewModel.showAgentDraft(draft)
                reviewingAgentDraft = planUiState.draft == null
            },
        )
        }
    } else {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { androidx.compose.material3.SnackbarHost(captureSnackbar,
                modifier = Modifier.testTag("page-save-snackbar")
                    .onSizeChanged { snackbarHeightPx = it.height }
                    .onGloballyPositioned { pageSnackbarTopPx = it.boundsInRoot().top }) },
            topBar = {
                if (selectedTab == AppTab.TASKS) TaskPageAppBar(
                    title = stringResource(selectedTab.titleRes),
                ) else if (selectedTab == AppTab.TODAY) {
                    val browsedDate = scheduleBrowsedDate
                    Surface(color = Color.Transparent) {
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)
                            .padding(horizontal = 20.dp, vertical = 12.dp)) {
                            DesignClickableSurface(
                                onClick = { showScheduleDateSheet = true },
                                modifier = Modifier.testTag("schedule-selected-date"),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp),
                                ) {
                                    Text(
                                        scheduleDateHeadline(browsedDate, activeDate),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(
                                        DesignIcons.ChevronDown,
                                        contentDescription = "更改日期",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (showScheduleDateSheet) {
                        ScheduleDateSheet(
                            date = browsedDate,
                            weeklyBlocks = timeUiState.weeklyBlocks,
                            dateOverrides = timeUiState.dateOverrides,
                            planSegments = planUiState.currentPlan?.segments.orEmpty(),
                            tasks = uiState.tasks,
                            semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
                            onDismiss = { showScheduleDateSheet = false },
                            onSelectDate = selectScheduleDate,
                        )
                    }
                } else TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (selectedTab == AppTab.TIME) "时间设置" else if (selectedTab == AppTab.MINE && showMineSettings) "设置" else stringResource(selectedTab.titleRes),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (selectedTab == AppTab.TODAY) {
                                Text(
                                    text = activeDate.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (selectedTab == AppTab.TIME || (selectedTab == AppTab.MINE && showMineSettings)) {
                            IconButton(
                                onClick = { if (selectedTab == AppTab.TIME) selectedTab = timeReturnTab else showMineSettings = false },
                                modifier = Modifier.testTag(if (selectedTab == AppTab.TIME) "timetable-back" else "settings-back"),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                // 和日程那块同一张纸：入夜一起压暖，别一半在灯下、一半在日光灯下。
                Surface(color = rememberLandscapeSurface(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column {
                        val teamLoaded = teamViewModel?.state?.collectAsState()?.value?.snapshot != null
                        ComposerBar(
                            placeholder = when (selectedTab) {
                                AppTab.TODAY -> "说句话，安排今天…"
                                AppTab.TASKS -> "拆任务、算时间…"
                                AppTab.TEAM -> "排竞赛、分任务…"
                                AppTab.MINE -> "想调整什么，说一声…"
                                else -> "告诉助手你想做什么…"
                            },
                            enabled = selectedTab != AppTab.TEAM || teamLoaded,
                            onClick = { if (selectedTab == AppTab.TEAM) showTeamChat = true else showGroupChat = true },
                        )
                        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                            AppTab.entries.filter { it != AppTab.TIME && it != AppTab.AGENT }.forEach { tab ->
                                DesignNavigationItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab; if (tab == AppTab.MINE) showMineSettings = false },
                                    modifier = Modifier.testTag("navigation-${tab.name.lowercase()}"),
                                    icon = { TabIcon(tab) },
                                    label = { Text(if (tab == AppTab.TODAY) "日程" else stringResource(tab.shortRes)) },
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (selectedTab == AppTab.TASKS) {
                    TaskCaptureButton(
                        onClick = {
                            showTaskCapture = true
                        },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .onGloballyPositioned { pageContentBottomPx = it.boundsInRoot().bottom },
            ) {
                readMessage?.let { DataReadNotice(it, retryReads, canRetry = canRetryReads) }
                if (planUiState.draft != null && pausedPlanDraftIdentity == planUiState.draft?.generatedAt.toString()) {
                    OutlinedButton(onClick = { pausedPlanDraftIdentity = null }, modifier = Modifier.fillMaxWidth().testTag("plan-resume-preview")) {
                        Text("继续查看保留的计划预览")
                    }
                }
                // Scaffold places the snackbar above its FAB. Its height alone does not
                // describe the covered list area, so reserve the actual vertical overlap.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                MotionPage(targetKey = selectedTab, modifier = Modifier.fillMaxSize()
                    .padding(bottom = if (selectedTab != AppTab.TIME) taskSnackbarInset else 0.dp)
                    .wrapContentSize(Alignment.TopCenter)
                    .widthIn(max = 720.dp).fillMaxWidth()) { pageKey ->
                tabStateHolder.SaveableStateProvider(pageKey) {
                when (pageKey as AppTab) {
                    AppTab.TODAY -> TodayScreen(
                        executionSession = executionSession,
                        executionError = executionError,
                        executionReadsReady = executionReadState.isTrusted && uiState.isTrusted && planUiState.isTrusted,
                        executionFinished = executionFinished,
                        automaticPlanning = autonomyViewModel != null && autonomy.delegation.enabled,
                        onReplanRemaining = { taskViewModel.dismissSessionResult(); generatePlanDraft() },
                        onDismissExecutionResult = taskViewModel::dismissSessionResult,
                        planNeedsUpdate = planNeedsUpdate && !(autonomyViewModel != null && autonomy.delegation.enabled),
                        onReviewPlanChanges = {
                            if (planUiState.draft == null) generatePlanDraft()
                            else pausedPlanDraftIdentity = null
                        },
                        date = scheduleBrowsedDate,
                        onDateSelected = selectScheduleDate,
                        tasks = uiState.tasks.filter(Task::isTodayRelevant),
                        allTasks = uiState.tasks,
                        weeklyBlocks = timeUiState.weeklyBlocks,
                        dateOverrides = timeUiState.dateOverrides,
                        semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
                        engagementMode = engagementMode,
                        tracks = workspaceUiState.tracks,
                        onOpenTimelineEntry = engagementViewModel::recordTimelineOpened,
                        onEditTimelineEntry = { entry ->
                            when {
                                entry.id.startsWith("segment:") -> entry.taskId?.let { taskId ->
                                    taskViewModel.resetEditorResult()
                                    taskEditorTargetId = taskId.takeIf { id -> uiState.tasks.any { it.id == id } }
                                    showTaskEditor = taskEditorTarget != null
                                }

                                entry.id.startsWith("weekly:") -> {
                                    timeViewModel.resetMutation()
                                    weeklyBlockEditorId = timeUiState.weeklyBlocks.firstOrNull {
                                        it.id == entry.id.removePrefix("weekly:")
                                    }?.id
                                    showWeeklyBlockEditor = weeklyBlockEditorId != null
                                }

                                entry.id.startsWith("override:") -> {
                                    timeViewModel.resetMutation()
                                    dateOverrideEditorId = timeUiState.dateOverrides.firstOrNull {
                                        it.id == entry.id.removePrefix("override:")
                                    }?.id
                                    showDateOverrideEditor = dateOverrideEditorId != null
                                }
                            }
                        },
                        planSegments = planUiState.currentPlan?.segments
                            ?.filter { it.date == activeDate }
                            .orEmpty(),
                        allPlanSegments = planUiState.currentPlan?.segments.orEmpty(),
                        hasConfirmedPlan = planUiState.currentPlan != null,
                        pendingConfirmationSegments = planUiState.currentPlan?.segments
                            ?.filter { segment ->
                                segment.hasEndedBefore(LocalDateTime.now()) &&
                                    uiState.tasks.firstOrNull { it.id == segment.taskId }?.status?.isActive == true
                            }
                            ?.sortedWith(compareBy(PlannedSegment::date, PlannedSegment::startMinute))
                            .orEmpty(),
                        onOpenDailyReview = { showDailyReview = true },
                        isLoading = uiState.isLoading || (!uiState.hasLoaded && uiState.readError != null),
                        onOpen = { selectedTaskId = it.id },
                        onStart = taskViewModel::startTask,
                        onAdd = {
                            showScheduleEventSheet = true
                        },
                        onOpenTaskLibrary = { selectedTab = AppTab.TASKS },
                        onPlaceEvent = planViewModel::placeTask,
                        onFocusStarted = { segmentId ->
                            if (executionReadState.isTrusted && uiState.isTrusted && planUiState.isTrusted) {
                                val focusedTaskId = planUiState.currentPlan?.segments
                                    ?.firstOrNull { it.id == segmentId }?.taskId
                                if (focusedTaskId != null) {
                                    beginFocus(focusedTaskId, segmentId) { taskViewModel.startSession(segmentId) }
                                }
                            }
                        },
                        timelineWriteBusy = planUiState.isWorking || timeMutation.busy || planUiState.pendingManualChange != null,
                        timelineWriteError = planUiState.errorMessage ?: timeMutation.error,
                        reminderNotice = notificationAccessNotice,
                        onRemovePlacement = planViewModel::removePlacement,
                        onDropEntry = { entry, minute, nestIntoId ->
                            timeViewModel.resetMutation()
                            val duration = entry.endMinute - entry.startMinute
                            when {
                                // 拖进别的事件框架 → 下属事件（免确认）；拖到空白 → 自动后延插入。
                                entry.id.startsWith("segment:") -> planViewModel.movePlacement(
                                    entry.id.removePrefix("segment:"), minute, minute + duration, entry.trackId, {},
                                    delayFollowing = nestIntoId == null,
                                )

                                entry.id.startsWith("weekly:") -> timeUiState.weeklyBlocks
                                    .firstOrNull { it.id == entry.id.removePrefix("weekly:") }
                                    ?.let { block ->
                                        timeViewModel.saveWeeklyBlock(
                                            WeeklyTimeBlockDraft(
                                                id = block.id,
                                                title = block.title,
                                                kind = block.kind,
                                                dayOfWeek = block.dayOfWeek,
                                                startMinute = minute,
                                                endMinute = minute + duration,
                                                weekPattern = block.weekPattern,
                                                trackId = block.trackId,
                                                note = block.note,
                                            ), {},
                                        )
                                    }

                                entry.id.startsWith("override:") -> timeUiState.dateOverrides
                                    .firstOrNull { it.id == entry.id.removePrefix("override:") }
                                    ?.let { override ->
                                        timeViewModel.saveDateOverride(
                                            DateOverrideDraft(
                                                id = override.id,
                                                title = override.title,
                                                type = override.type,
                                                date = override.date,
                                                startMinute = minute,
                                                endMinute = minute + duration,
                                                note = override.note,
                                            ), {},
                                        )
                                    }
                            }
                        },
                        onToggleEntryReminder = { entry, enabled ->
                            if (!enabled) writeEntryReminder(entry, false)
                            else notificationAccess.ensuring { writeEntryReminder(entry, true) }
                        },
                        onUpdateEntryTime = { entry, startMinute, endMinute, date, saved ->
                            timeViewModel.resetMutation()
                            when {
                                entry.id.startsWith("segment:") -> planViewModel.movePlacement(
                                    entry.id.removePrefix("segment:"), startMinute, endMinute, entry.trackId, saved, date = date,
                                )

                                entry.id.startsWith("weekly:") -> timeUiState.weeklyBlocks
                                    .firstOrNull { it.id == entry.id.removePrefix("weekly:") }
                                    ?.let { block ->
                                        timeViewModel.saveWeeklyBlock(
                                            WeeklyTimeBlockDraft(
                                                id = block.id,
                                                title = block.title,
                                                kind = block.kind,
                                                dayOfWeek = block.dayOfWeek,
                                                startMinute = startMinute,
                                                endMinute = endMinute,
                                                weekPattern = block.weekPattern,
                                                trackId = block.trackId,
                                                note = block.note,
                                            ), saved,
                                        )
                                    }

                                entry.id.startsWith("override:") -> timeUiState.dateOverrides
                                    .firstOrNull { it.id == entry.id.removePrefix("override:") }
                                    ?.let { override ->
                                        timeViewModel.saveDateOverride(
                                            DateOverrideDraft(
                                                id = override.id,
                                                title = override.title,
                                                type = override.type,
                                                date = override.date,
                                                startMinute = startMinute,
                                                endMinute = endMinute,
                                                note = override.note,
                                            ), saved,
                                        )
                                    }
                            }
                        },
                    )

                    AppTab.TASKS -> TasksScreen(
                        confirmedSegments = planUiState.currentPlan?.segments.orEmpty(),
                        tasks = uiState.tasks.sortedBy { task -> planUiState.taskOrder.indexOf(task.id).takeIf { it >= 0 } ?: Int.MAX_VALUE },
                        isLoading = uiState.isLoading || (!uiState.hasLoaded && uiState.readError != null),
                        onOpen = { selectedTaskId = it.id },
                        onStart = taskViewModel::startTask,
                        onAdd = {
                            showTaskCapture = true
                        },
                        onBatchAction = { action, ids -> taskViewModel.confirmStatuses(action, TaskSelection(ids)) },
                    )

                    // 同上：AGENT 页已下线，分支只为 when 穷尽保留。
                    AppTab.AGENT -> Unit

                    AppTab.TEAM -> teamViewModel?.let { vm -> com.swan1127.repland.ui.team.TeamScreen(vm = vm) }
                        ?: DataReadNotice(message = "团队模块初始化中，请稍后重试。", onRetry = {}, canRetry = false)

                    AppTab.TIME -> TimeScreen(
                        snackbarBottomInset = snackbarBottomInset,
                        state = timeUiState,
                        busy = timeMutation.busy || !timeUiState.isTrusted,
                        onAddWeekly = { timeViewModel.resetMutation(); weeklyBlockEditorId = null; weeklyBlockInitialKind = TimeBlockKind.COURSE; showWeeklyBlockEditor = true },
                        onEditWeekly = { timeViewModel.resetMutation(); weeklyBlockEditorId = it.id; showWeeklyBlockEditor = true },
                        onDeleteWeekly = { timeViewModel.resetMutation(); deletingWeeklyId = it.id },
                        onAddOverride = { timeViewModel.resetMutation(); dateOverrideEditorId = null; showDateOverrideEditor = true },
                        onEditOverride = { timeViewModel.resetMutation(); dateOverrideEditorId = it.id; showDateOverrideEditor = true },
                        onDeleteOverride = { timeViewModel.resetMutation(); deletingOverrideId = it.id },
                        onEditSemester = { timeViewModel.resetMutation(); showSemesterStartEditor = true },
                        timetableImport = timeUiState.timetableImport,
                        onImportPdf = timeViewModel::readTimetable,
                        onClearTimetableImport = timeViewModel::clearTimetableImport,
                    )
                    AppTab.MINE -> MineScreen(
                        focusedFloorCount = (totalFocusedMinutes / 5).coerceAtLeast(0),
                        showSettings = showMineSettings,
                        onOpenSettings = { showMineSettings = true },
                        memoryReferenceEnabled = learningMemory.referenceEnabled,
                        memoryProfile = teamMemory.second,
                        teamMemoryFactCount = teamMemory.first.size,
                        onMemoryReferenceChange = {
                            auxiliaryStore.memoryReferenceEnabled = it
                            memoryRevision += 1
                        },
                        personaDoc = personaDoc,
                        onEditPersona = { showPersonaEditor = true },
                        personalProfileContent = {
                            personalProfileViewModel?.let { com.swan1127.repland.ui.profile.PersonalProfileSection(it) }
                            com.swan1127.repland.ui.profile.ContentPortraitSection(
                                portrait = contentPortrait,
                                material = portraitMaterial(),
                                busy = portraitBusy,
                                canGenerate = aiProviderConfigUiState.supportsRemote && aiProviderConfigUiState.config.hasApiKey,
                                error = portraitError,
                                onConsentAndGenerate = {
                                    saveContentPortrait(
                                        contentPortrait.copy(consentAtMillis = System.currentTimeMillis()),
                                    )
                                    generateContentPortrait()
                                },
                                onGenerate = ::generateContentPortrait,
                                onRevokeConsent = {
                                    saveContentPortrait(
                                        contentPortrait.copy(
                                            consentAtMillis = null,
                                            points = emptyList(),
                                            evidence = emptyList(),
                                        ),
                                    )
                                },
                                onAddCorrection = { text ->
                                    saveContentPortrait(
                                        contentPortrait.copy(corrections = contentPortrait.corrections + text),
                                    )
                                },
                                onRemoveCorrection = { index ->
                                    saveContentPortrait(
                                        contentPortrait.copy(
                                            corrections = contentPortrait.corrections.filterIndexed { at, _ -> at != index },
                                        ),
                                    )
                                },
                            )
                            numericProfileViewModel?.let { vm ->
                                com.swan1127.repland.ui.profile.NumericProfileSection(vm) { selectedTaskId = it }
                            }
                        },
                        onOpenTimeSettings = { timeReturnTab = AppTab.MINE; selectedTab = AppTab.TIME },
                        onOpenPlanHistory = { showPlanOverview = true },
                        weights = categoryPreferenceUiState.weights,
                        preferenceState = categoryPreferenceUiState,
                        isLoading = (categoryPreferenceUiState.isLoading && !categoryPreferenceUiState.hasLoaded) ||
                            reminderSettingsUiState.isLoading ||
                            planningAgentUiState.isLoading || aiProviderConfigUiState.isLoading,
                        onSave = categoryPreferenceViewModel::save,
                        remindersEnabled = reminderSettingsUiState.preferences.isEnabled,
                        notificationsAllowed = notificationPermissionGranted,
                        onRemindersEnabledChange = onReminderEnabledChange,
                        onExportLocalData = {
                            localDataExportLauncher.launch("repland-local-export-${LocalDate.now()}.json")
                        },
                        onClearLocalData = { showClearLocalDataConfirmation = true },
                        isDataWorking = dataManagementUiState.isWorking,
                        dataResult = dataManagementUiState.result,
                        automaticPlanningEnabled = autonomy.delegation.enabled.takeIf { autonomyViewModel != null && autonomy.ready },
                        onAutomaticPlanningChange = { autonomyViewModel?.setEnabled(it) },
                        aiEnabled = planningAgentUiState.preferences.isEnabled,
                        aiConsented = planningAgentUiState.preferences.hasExplicitConsent,
                        onAiEnabledChange = onAiEnabledChange,
                        aiProviderConfig = aiProviderConfigUiState.config,
                        remoteAiSupported = aiProviderConfigUiState.supportsRemote,
                        aiProviderConfigError = aiProviderConfigUiState.errorMessage,
                        aiProviderConnectionTest = aiProviderConfigUiState.connectionTest,
                        onSaveAiProviderConfig = aiProviderConfigViewModel::save,
                        onClearAiProviderKey = aiProviderConfigViewModel::clearApiKey,
                        onTestAiProviderConnection = aiProviderConfigViewModel::testConnection,
                        engagementMode = engagementMode,
                        onEngagementModeChange = engagementViewModel::setMode,
                        summaryContent = {
                            if (usageSummaryViewModel != null) com.swan1127.repland.ui.profile.UsageSummarySection(usageSummaryViewModel)
                            else com.swan1127.repland.ui.profile.UsageSummaryCard(com.swan1127.repland.ui.profile.UsageSummaryUiState(
                                text = "暂无使用记录", loading = false))
                        },
                        weatherContent = { weatherViewModel?.let { com.swan1127.repland.ui.weather.WeatherSettingsSection(it) } },
                        capabilityShelfContent = {
                            com.swan1127.repland.ui.mine.CapabilityShelf(
                                notificationAccess = notificationAccess.access,
                                exactAlarmsGranted = exactAlarmsGranted,
                                batteryExempted = batteryExempted,
                                micGranted = micGranted,
                                voiceServiceAvailable = voiceServiceAvailable,
                                aiEnabled = planningAgentUiState.preferences.isEnabled,
                                aiConsented = planningAgentUiState.preferences.hasExplicitConsent,
                                hasApiKey = aiProviderConfigUiState.config.hasApiKey,
                                remoteAiSupported = aiProviderConfigUiState.supportsRemote,
                                teamSyncConnected = teamViewModel?.backendConfigured == true,
                                onOpenNotifications = { notificationAccess.askIfMissing() },
                                onOpenExactAlarm = requestExactAlarmPermission,
                                onOpenBattery = openBatteryExemption,
                                onOpenMic = { micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                                onOpenAiSettings = { showShelfAiDialog = true },
                                onOpenTeamSync = { selectedTab = AppTab.TEAM },
                            )
                        },
                    )
                }
                }
                }
                    // 中途离开盖楼页时，本轮不占首页版面，只挂在导航栏上方一条半透明浮条。
                    // 有悬浮按钮的页面给按钮让出一列，避免浮条右端的倒计时被压住。
                    // 日程页让列的条件不看助手浮标在不在：卡片右下角那枚「+」一直在那儿，
                    // 浮条一旦铺满整宽就把它连点击一起吃掉了。
                    val fabWidth = when {
                        selectedTab == AppTab.TASKS || selectedTab == AppTab.TODAY -> 56.dp
                        else -> 0.dp
                    }
                    executionSession?.takeIf { !showExecutionSession }?.let { active ->
                        FocusPill(
                            session = active,
                            onClick = { showExecutionSession = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(end = if (fabWidth > 0.dp) fabWidth + 22.dp else 0.dp),
                        )
                    }
            }
        }
    }
    }

    if (showShelfAiDialog) {
        AiProviderSettingsDialog(
            config = aiProviderConfigUiState.config,
            errorMessage = aiProviderConfigUiState.errorMessage,
            connectionTest = aiProviderConfigUiState.connectionTest,
            onDismiss = { showShelfAiDialog = false },
            onSave = aiProviderConfigViewModel::save,
            onClearKey = aiProviderConfigViewModel::clearApiKey,
            onTestConnection = aiProviderConfigViewModel::testConnection,
        )
    }

    if (showGroupChat) {
        // The toolbox (and its conversation ledger of created tasks) lives for as long as the
        // sheet is open, keyed only by the day. Every seam into app state is therefore a
        // live-reading lambda: the assistant's own writes change the day underneath the sheet,
        // and a captured snapshot would make the next read contradict the receipt it just gave.
        val agentToolbox = remember(activeDate) {
            com.swan1127.repland.ui.agent.AgentAppToolbox(
                activeDate = activeDate,
                tasksProvider = { uiState.tasks },
                occupiedProvider = { agentTimelineForDate(activeDate) },
                onSaveTasks = taskViewModel::saveAgentTasks,
                onStartTask = taskViewModel::startTask,
                onCompleteTask = { taskId, content, actualMinutes, progress ->
                    taskViewModel.completeTask(taskId, completedContent = content, actualDurationMinutes = actualMinutes)
                },
                onCancelTask = taskViewModel::cancelTask,
                onPostponeTask = taskViewModel::postponeTask,
                onRecordFeedback = taskViewModel::recordFeedback,
                onPlaceTask = { taskId, date, startMinute, endMinute, trackId, reminderEnabled, leadMinutes ->
                    planViewModel.placeTaskForAgent(taskId, date, startMinute, endMinute, trackId, reminderEnabled, leadMinutes)
                },
                onMovePlacement = { segmentId, date, startMinute, endMinute, trackId ->
                    planViewModel.movePlacementForAgent(segmentId, date, startMinute, endMinute, trackId)
                },
                onRemovePlacement = { segmentId ->
                    planViewModel.removePlacement(segmentId)
                    planViewModel.confirmManualChange()
                },
                onSetSegmentReminder = planViewModel::setSegmentReminder,
                onStartFocus = { taskId -> beginFocus(taskId, null) { taskViewModel.startSessionForTask(taskId) } },
                schedulingStyle = { agentProfileStore.loadSchedulingStyle() },
                onUpdateTask = taskViewModel::updateAgentTask,
                // 换天排期前得先看到那一天的课。60 天之外不承诺看得见：课程周期、考试周都在更远的将来失真。
                timelineForDate = { date ->
                    if (date.isBefore(LocalDate.now()) || date.isAfter(LocalDate.now().plusDays(60))) null
                    else agentTimelineForDate(date)
                },
                // 容量账读的是登记下来的空闲模板和当日例外；没登记时助手要能说「没课不等于有空」。
                availableRangesForDate = { date ->
                    PlanningConstraintValidator.availableRanges(
                        date = date,
                        weeklyBlocks = timeUiState.weeklyBlocks,
                        dateOverrides = timeUiState.dateOverrides,
                        semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
                    )
                },
                memoryFacts = auxiliaryStore::teamMemoryFacts,
                memoryProfile = { auxiliaryStore.memoryProfileSummary(activeDate) },
                memoryEpisodes = auxiliaryStore::teamMemoryEpisodes,
                onSaveAvailability = timeViewModel::registerAgentAvailability,
                // 固定安排读的是时间设置里的周模板；AVAILABLE 由工具自己滤掉，不算日常事项。
                weeklyBlocksProvider = { timeUiState.weeklyBlocks },
                onSaveRoutine = timeViewModel::saveAgentRoutine,
                segmentsProvider = { planUiState.currentPlan?.segments.orEmpty() },
            )
        }
        com.swan1127.repland.ui.agent.GroupChatSheet(
            activeDate = activeDate,
            occupiedEntries = agentTimelineEntries,
            cloudEnabled = planningReadsReady && arrangementAssistantAccess.isEnabled && arrangementAssistantAccess.hasExplicitConsent &&
                aiProviderConfigUiState.config.hasApiKey && aiProviderConfigUiState.supportsRemote,
            // 授权已经给了、只是密钥没配好或云端不通：这时本地规则仍然可以接住那句话。
            // 授权没开时不接——离线解析不是绕过同意的后门。
            offlineWritesEnabled = planningReadsReady && arrangementAssistantAccess.isEnabled && arrangementAssistantAccess.hasExplicitConsent,
            blockedReason = when {
                !planningReadsReady -> com.swan1127.repland.domain.model.AiAdvisorFailureReason.CONTEXT_UNAVAILABLE
                !arrangementAssistantAccess.isEnabled || !arrangementAssistantAccess.hasExplicitConsent ->
                    com.swan1127.repland.domain.model.AiAdvisorFailureReason.DISABLED
                else -> com.swan1127.repland.domain.model.AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED
            },
            turnModel = agentTurnModel ?: com.swan1127.repland.domain.model.NoOpAgentTurnModel,
            toolbox = agentToolbox,
            writeUndo = agentWriteUndo,
            rememberedFacts = teamMemory.first,
            memoryProfile = teamMemory.second,
            memoryEpisodes = teamMemory.third,
            personaDoc = personaDoc,
            portraitNote = contentPortrait.briefing(),
            journalStore = assistantJournalStore,
            onWriteExecuted = { action, ok, _ ->
                if (ok && action.toolName in setOf("create_task", "plan_tasks")) {
                    val names = runCatching {
                        val args = org.json.JSONObject(action.argumentsJson)
                        val items = args.optJSONArray("items")?.let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                array.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)
                            }
                        }.orEmpty()
                        items.ifEmpty { listOfNotNull(args.optString("name").takeIf(String::isNotBlank)) }
                    }.getOrDefault(emptyList()).ifEmpty { listOf(action.summary) }
                    names.take(4).forEach { name ->
                        // 键按事项名归一（去空格、截断）：同一件事反复排期只留最新一条，
                        // 否则「背单词 30 分钟」和「背单词30分钟」会各占一格，把记忆挤满。
                        auxiliaryStore.noteTeamMemoryFact("草案·" + name.filterNot(Char::isWhitespace).take(12), action.summary)
                    }
                    auxiliaryStore.appendTeamMemoryEpisode(
                        "$activeDate 接受了草案：" + names.take(4).joinToString("、") { "「$it」" },
                    )
                    memoryRevision += 1
                }
            },
            onReplyFeedback = { liked, snippet ->
                auxiliaryStore.appendTeamMemoryEpisode(
                    "$activeDate ${if (liked) "喜欢" else "不喜欢"}了助手关于「$snippet」的回答",
                )
                auxiliaryStore.noteTeamMemoryFact(
                    if (liked) "偏好·认可的回答" else "偏好·不认可的回答",
                    snippet,
                )
                memoryRevision += 1
            },
            onDismiss = { showGroupChat = false },
        )
    }

    if (showTeamChat) {
        teamViewModel?.let { vm ->
            com.swan1127.repland.ui.team.TeamChatSheet(
                vm = vm,
                onReplyFeedback = { liked, snippet ->
                    auxiliaryStore.appendTeamMemoryEpisode(
                        "$activeDate ${if (liked) "喜欢" else "不喜欢"}了队伍助手关于「$snippet」的回答",
                    )
                    auxiliaryStore.noteTeamMemoryFact(
                        if (liked) "偏好·认可的回答" else "偏好·不认可的回答",
                        snippet,
                    )
                    memoryRevision += 1
                },
                onDismiss = { showTeamChat = false },
            )
        }
    }

    if (showPersonaEditor) {
        PersonaDocEditorDialog(
            initial = personaDoc,
            onDismiss = { showPersonaEditor = false },
            onSave = { text ->
                personaDocStore.save(text)
                personaDoc = personaDocStore.load()
                showPersonaEditor = false
            },
        )
    }

    focusConflict?.let { pending ->
        AlertDialog(
            onDismissRequest = { focusConflict = null; focusConflictTitle = null },
            title = { Text("还有一轮在计时") },
            text = { Text("「${focusConflictTitle.orEmpty()}」还没结束。要收掉它、开始新的一轮吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val active = executionSession
                    focusConflict = null
                    focusConflictTitle = null
                    if (active == null) {
                        pending()
                        showExecutionSession = true
                    } else {
                        focusAfterFinish = pending
                        finishedRound = active.taskId to recallGradeOf(active)
                        taskViewModel.finishSession(active.id, ExecutionOutcome.CONTINUE, TaskFeedback())
                    }
                }) { Text("结束并开始") }
            },
            dismissButton = {
                TextButton(onClick = {
                    focusConflict = null
                    focusConflictTitle = null
                    showExecutionSession = true
                }) { Text("继续当前") }
            },
        )
    }
    if (showExecutionSession && executionSession != null) {
        com.swan1127.repland.ui.schedule.ExecutionSessionDialog(
            session = executionSession!!,
            busy = executionBusy,
            error = executionError,
            canOperate = executionReadState.isTrusted && uiState.isTrusted && planUiState.isTrusted,
            readNotice = { readMessage?.let { DataReadNotice(it, retryReads, canRetryReads) } },
            onDismiss = { showExecutionSession = false },
            onFinish = {
                finishedRound = executionSession!!.let { it.taskId to recallGradeOf(it) }
                taskViewModel.finishSession(executionSession!!.id, ExecutionOutcome.CONTINUE, TaskFeedback())
                showExecutionSession = false
            },
            onPause = { taskViewModel.pauseSession(executionSession!!.id) },
            onResume = { taskViewModel.resumeSession(executionSession!!.id) },
        )
    }
    LaunchedEffect(taskCaptureUiState.receipt) {
        taskCaptureUiState.receipt?.let { receipt ->
            showTaskCapture = false
            taskEditorSeed = ""
            captureSnackbar.showSnackbar(receipt)
            // Consuming the effect key earlier would cancel the visible Snackbar.
            taskCaptureViewModel.dismissReceipt()
            // 捕获后置流：只记入「待安排」的任务进确认面；保存时已带日期/截止的不多问一次。
            taskCaptureUiState.savedTask
                ?.takeIf { it.scheduledForDate == null && it.dueDate == null }
                ?.let { captureConfirmTask = it }
        }
    }
    if (showTaskCapture && !taskCaptureUiState.loading) {
        TaskCaptureSheet(
            initialText = taskEditorSeed,
            draft = taskCaptureUiState.draft,
            onDraftChange = taskCaptureViewModel::update,
            saving = taskCaptureUiState.saving,
            saveError = taskCaptureUiState.error,
            loadFailed = taskCaptureUiState.loadFailed,
            onRetryLoad = taskCaptureViewModel::retryLoad,
            onDiscard = taskCaptureViewModel::discard,
            onVoice = { taskCaptureViewModel.retain(); taskEditorSeed = ""; voiceTranscript = taskCaptureUiState.draft.text; showTaskCapture = false; showVoiceComposer = true; voiceError = false },
            // 退出即放弃（面板内已确认），这里不再偷偷保留草稿。
            onDismiss = { showTaskCapture = false; taskEditorSeed = "" },
            onSave = taskCaptureViewModel::save,
        )
    }
    captureConfirmTask?.let { confirmTask ->
        // 建议时段按今天实况现算：course/override/已有安排都算占用，floor 从现在起 15 分钟对齐。
        val confirmNow = java.time.LocalDateTime.now()
        val confirmToday = confirmNow.toLocalDate()
        val confirmDuration = com.swan1127.repland.domain.model.TaskPlacementPolicy.durationForTask(confirmTask.totalDurationMinutes)
        val confirmSlot = confirmDuration?.let {
            com.swan1127.repland.domain.model.TaskPlacementPolicy.nextFreeMinute(
                agentTimelineForDate(confirmToday),
                com.swan1127.repland.domain.model.TaskPlacementPolicy.floorMinuteFor(confirmNow), it)
        }
        val confirmSlotReason = when {
            confirmTask.totalDurationMinutes == null -> "这件任务还没填预计时长；补上时长后才能排进时段。"
            confirmDuration == null -> "这件事的时长超出一天，排不进单日时段。"
            confirmSlot == null -> "今天剩下的空档排不下这件任务；可以先记在事项库里，之后在日程里安排。"
            else -> null
        }
        com.swan1127.repland.ui.tasks.TaskCaptureConfirmationPanel(
            task = confirmTask,
            suggestedSlot = confirmSlot?.let { start -> start to (start + requireNotNull(confirmDuration)) },
            slotUnavailableReason = confirmSlotReason,
            busy = captureConfirmBusy,
            onScheduleToday = {
                val taskId = confirmTask.id ?: return@TaskCaptureConfirmationPanel
                val start = confirmSlot ?: return@TaskCaptureConfirmationPanel
                val end = start + requireNotNull(confirmDuration)
                captureConfirmBusy = true
                feedbackSnackbarScope.launch {
                    // 先写时段再标记任务行：时段写不进去时任务保持「只记入」原状，不出现半排上的中间态。
                    val failure = planViewModel.placeTaskForAgent(taskId, confirmToday, start, end, "focus")
                    if (failure == null) {
                        val marked = taskViewModel.scheduleTaskForToday(taskId, confirmToday)
                        captureConfirmTask = null
                        captureConfirmBusy = false
                        captureSnackbar.showSnackbar(
                            if (marked) "已排入今天 ${com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(start)}–" +
                                com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(end)
                            else "时段已排入，但任务来源标记没有更新；可在任务详情里确认。")
                    } else {
                        captureConfirmBusy = false
                        captureSnackbar.showSnackbar("排入今天没有成功：$failure")
                    }
                }
            },
            onPickTime = {
                if (!captureConfirmBusy) {
                    captureConfirmPickError = null
                    captureConfirmPickTime = true
                }
            },
            onLibraryOnly = {
                if (!captureConfirmBusy) {
                    // 只记入事项库：捕获保存时已是 USER_INPUT、scheduledForDate 为空，无需再写。
                    captureConfirmTask = null
                }
            },
        )
        if (captureConfirmPickTime) {
            val pickDuration = confirmDuration ?: 60
            val pickStart = confirmSlot
                ?: com.swan1127.repland.domain.model.TaskPlacementPolicy.floorMinuteFor(confirmNow)
            com.swan1127.repland.ui.schedule.TimelineTimeEditorDialog(
                title = "给「${confirmTask.displayName}」选时间",
                initialStartMinute = pickStart,
                initialEndMinute = pickStart + pickDuration,
                tagKey = "capture-confirm",
                onDismiss = { captureConfirmPickTime = false },
                busy = captureConfirmBusy,
                saveError = captureConfirmPickError,
                initialDate = confirmToday,
                onSave = { start, end, date, saved ->
                    val taskId = confirmTask.id
                    if (taskId != null) {
                        captureConfirmBusy = true
                        captureConfirmPickError = null
                        val targetDate = date ?: confirmToday
                        feedbackSnackbarScope.launch {
                            // 与「排入今天」同一通路：先写时段，写不进去任务保持「只记入」原状。
                            val failure = planViewModel.placeTaskForAgent(taskId, targetDate, start, end, "focus")
                            if (failure == null) {
                                val marked = taskViewModel.scheduleTaskForToday(taskId, targetDate)
                                captureConfirmPickTime = false
                                captureConfirmTask = null
                                captureConfirmBusy = false
                                saved()
                                captureSnackbar.showSnackbar(
                                    if (marked) "已排入 ${taskDateLabel(targetDate)} " +
                                        com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(start) + "–" +
                                        com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(end)
                                    else "时段已排入，但任务来源标记没有更新；可在任务详情里确认。")
                            } else {
                                captureConfirmBusy = false
                                captureConfirmPickError = failure
                            }
                        }
                        true
                    } else {
                        captureConfirmPickError = "这件任务缺少身份，无法排入；请重新捕获。"
                        false
                    }
                },
            )
        }
    }

    if (showScheduleEventSheet) {
        com.swan1127.repland.ui.schedule.ScheduleEventSheet(
            saving = planUiState.isWorking || timeMutation.busy,
            saveError = planUiState.errorMessage ?: timeMutation.error,
            onDismiss = { showScheduleEventSheet = false },
            onSaveOnce = { title, date, start, end, reminderEnabled, lead ->
                val taskId = java.util.UUID.randomUUID().toString()
                planViewModel.saveTasksAndPlace(
                    listOf(com.swan1127.repland.domain.model.TaskDraft(
                        id = taskId, displayName = title,
                        totalDurationMinutes = end - start, scheduledForDate = date)),
                    listOf(com.swan1127.repland.domain.model.PlannedSegment(
                        taskId = taskId, date = date, startMinute = start, endMinute = end,
                        trackId = "focus", reminderEnabled = reminderEnabled, leadMinutes = lead)),
                ) {
                    showScheduleEventSheet = false
                    feedbackSnackbarScope.launch {
                        captureSnackbar.showSnackbar(
                            "已排入 ${taskDateLabel(date)} " +
                                com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(start) + "–" +
                                com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(end))
                    }
                }
            },
            onSaveRepeat = { title, weekdays, start, end, reminderEnabled, lead ->
                timeViewModel.resetMutation()
                timeViewModel.saveWeeklyBlocks(weekdays.sorted().map { day ->
                    WeeklyTimeBlockDraft(
                        title = title, kind = TimeBlockKind.OTHER, dayOfWeek = day,
                        startMinute = start, endMinute = end, trackId = "fixed",
                        reminderEnabled = reminderEnabled, leadMinutes = lead)
                }) {
                    showScheduleEventSheet = false
                    val repeatText = if (weekdays.size == 7) "每天" else
                        "每周" + weekdays.sorted().joinToString("、") {
                            "周" + listOf("一", "二", "三", "四", "五", "六", "日")[it.value - 1]
                        }
                    feedbackSnackbarScope.launch {
                        captureSnackbar.showSnackbar(
                            "已加入$repeatText " +
                                com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(start) + "–" +
                                com.swan1127.repland.domain.model.TimeBlockValidator.formatTime(end))
                    }
                }
            },
        )
    }

    val editorSaveState by taskViewModel.editorSaveState.collectAsStateWithLifecycle()
    LaunchedEffect(editorSaveState.receipt) {
        editorSaveState.receipt?.let {
            showTaskEditor = false
            taskEditorSeed = ""
            selectedTaskId = null
            selectedTab = AppTab.TASKS
            captureSnackbar.showSnackbar(it)
            taskViewModel.resetEditorResult()
        }
    }
    if (showTaskEditor && taskEditorTargetId != null && uiState.isTrusted && taskEditorTarget == null) {
        AlertDialog(onDismissRequest = { showTaskEditor = false }, title = { Text("这件任务已不在任务库") },
            text = { Text("不会把这次编辑当成新任务保存。请返回任务页检查。") },
            confirmButton = { TextButton(onClick = { showTaskEditor = false; taskViewModel.resetEditorResult() }) { Text("返回任务页") } })
    }
    if (showTaskEditor && (taskEditorTargetId == null || taskEditorTarget != null)) {
        TaskEditorDialog(
            task = taskEditorTarget,
            initialText = taskEditorSeed,
            saving = editorSaveState.saving,
            saveError = editorSaveState.error,
            canSave = uiState.isTrusted,
            readNotice = { taskReadMessage?.let { DataReadNotice(it, taskViewModel::retryRead, !uiState.isLoading) } },
            onDismiss = { if (!editorSaveState.saving) { showTaskEditor = false; taskEditorSeed = ""; taskViewModel.resetEditorResult() } },
            onSave = taskViewModel::saveTask,
        )
    }

    if (showVoiceComposer) {
        AlertDialog(
            onDismissRequest = { showVoiceComposer = false },
            title = { Text("说出你的安排") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("语音由系统识别服务处理，可能联网", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = voiceTranscript,
                        onValueChange = { voiceTranscript = it },
                        modifier = Modifier.fillMaxWidth().testTag("voice-transcript"),
                        label = { Text("识别文字（可编辑）") },
                        minLines = 3,
                    )
                    if (voiceError) Text("语音服务不可用或没有识别出内容；你仍可在这里输入。",
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onVoiceCapture) { Text("开始语音识别") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        taskEditorTargetId = null
                        taskEditorSeed = voiceTranscript.trim()
                        showVoiceComposer = false
                        showTaskCapture = true
                    },
                    enabled = voiceTranscript.isNotBlank(),
                    modifier = Modifier.testTag("voice-to-task"),
                ) { Text("转成任务草稿") }
            },
            dismissButton = { TextButton(onClick = { showVoiceComposer = false }) { Text("取消") } },
        )
    }

    if (showAssistant) {
        val task = assistantTaskId?.let { id -> uiState.tasks.firstOrNull { it.id == id } }
            ?: selectedTask
        task?.let {
            AssistantSheet(
                taskTitle = it.displayName,
                onDismiss = { assistantTaskId = null; showAssistant = false },
                onSubmit = { prompt ->
                    assistantTaskId = null
                    showAssistant = false
                    val type = when {
                        prompt.contains("时间") || prompt.contains("多久") -> PlanningAgentRequestType.DIFFICULTY_AND_DURATION
                        prompt.contains("拆") || prompt.contains("步骤") -> PlanningAgentRequestType.TASK_BREAKDOWN
                        prompt.contains("调整") || prompt.contains("安排") -> PlanningAgentRequestType.REPLAN
                        else -> PlanningAgentRequestType.TASK_UNDERSTANDING
                    }
                    lastPlanningAgentTaskId = it.id
                    when (type) {
                        PlanningAgentRequestType.TASK_UNDERSTANDING -> planningAgentViewModel.beginTaskUnderstanding(it, executionLogs, planUiState.currentPlan)
                        PlanningAgentRequestType.DIFFICULTY_AND_DURATION -> planningAgentViewModel.beginDifficultyAndDuration(it, executionLogs, planUiState.currentPlan)
                        PlanningAgentRequestType.TASK_BREAKDOWN -> planningAgentViewModel.beginTaskBreakdown(it, executionLogs, planUiState.currentPlan)
                        PlanningAgentRequestType.REPLAN -> planningAgentViewModel.beginReplan(
                            affectedTasks = listOf(it), executionLogs = executionLogs,
                            currentPlan = planUiState.currentPlan,
                            localPlanInput = PlanGenerationInput(
                                tasks = uiState.tasks, weeklyBlocks = timeUiState.weeklyBlocks,
                                dateOverrides = timeUiState.dateOverrides,
                                semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
                                lockedSegments = planUiState.currentPlan?.segments?.filter { it.isLocked && !it.hasEndedBefore(LocalDateTime.now()) }.orEmpty(),
                                categoryPreferences = categoryPreferenceUiState.weights,
                                manualTaskOrder = planUiState.currentPlan?.takeIf { it.hasManualTaskOrder }?.orderedTaskIds.orEmpty(),
                            ),
                            constraintSummary = listOf("只处理未来安排，结果需要你确认。"),
                        )
                        else -> Unit
                    }
                },
            )
        }
    }

    LaunchedEffect(taskMutation.receipt) {
        taskMutation.receipt?.let { receipt ->
            if (taskMutationEditorKind == taskMutation.kind && taskMutationEditorId == taskMutation.taskId &&
                taskMutationLogId == taskMutation.logId) {
                taskMutationEditorKind = null; taskMutationEditorId = null; taskMutationLogId = null
            }
            // Enqueue the actual receipt before consuming it, without locking every
            // task action for the duration of a transient Snackbar. Batch receipts carry
            // an undo action; the view model still holds the reverse step at this point.
            feedbackSnackbarScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val result = captureSnackbar.showSnackbar(
                    receipt,
                    actionLabel = if (taskMutation.undoable) "撤销" else null,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    taskViewModel.undoLastBatch()
                }
            }
            taskViewModel.resetMutation()
        }
    }
    val missingFeedbackTarget = taskMutationEditorKind != null && uiState.isTrusted &&
        (taskMutationTarget == null || (taskMutationEditorKind == TaskMutationKind.CORRECT &&
            !correctionLogs.isLoading && !correctionLogs.failed && correctionTarget == null))
    if (missingFeedbackTarget && !taskMutation.busy && taskMutation.receipt == null) {
        AlertDialog(onDismissRequest = closeTaskMutation, title = { Text("原任务或记录已不存在") },
            text = { Text("请关闭后重新选择。本次输入不会保存到其他事项，也不会复活原任务。") },
            confirmButton = { TextButton(onClick = closeTaskMutation) { Text("关闭") } })
    }
    if (taskMutationEditorKind == TaskMutationKind.CORRECT && correctionLogs.failed) {
        AlertDialog(onDismissRequest = closeTaskMutation, title = { Text("原记录读取失败") },
            text = { Text("未修改记录，请关闭后重新打开。") },
            confirmButton = { TextButton(onClick = closeTaskMutation) { Text("关闭") } })
    }
    if (taskMutation.error != null && taskMutationEditorKind == null) {
        AlertDialog(onDismissRequest = taskViewModel::resetMutation, title = { Text("操作未保存") },
            text = { Text(taskMutation.error!!) },
            confirmButton = { TextButton(onClick = taskViewModel::retryMutation) { Text("重试") } },
            dismissButton = { TextButton(onClick = taskViewModel::resetMutation) { Text("取消") } })
    }
    taskMutationTarget?.takeIf { taskMutationEditorKind == TaskMutationKind.PARTIAL && !missingFeedbackTarget }?.let { task ->
        PartialCompletionDialog(
            task = task,
            busy = taskMutation.busy, error = taskMutation.error,
            canConfirm = uiState.isTrusted,
            readNotice = { taskReadMessage?.let { DataReadNotice(it, taskViewModel::retryRead, !uiState.isLoading) } },
            onDismiss = closeTaskMutation,
            onConfirm = { feedback ->
                taskViewModel.recordPartialCompletion(task.id, feedback)
            },
            onComplete = { taskViewModel.completeTask(task.id) },
        )
    }

    taskMutationTarget?.takeIf { taskMutationEditorKind == TaskMutationKind.FEEDBACK && !missingFeedbackTarget }?.let { task ->
        ExecutionFeedbackDialog(
            task = task,
            busy = taskMutation.busy, error = taskMutation.error,
            canConfirm = uiState.isTrusted,
            readNotice = { taskReadMessage?.let { DataReadNotice(it, taskViewModel::retryRead, !uiState.isLoading) } },
            onDismiss = closeTaskMutation,
            onConfirm = { feedback ->
                taskViewModel.recordFeedback(task.id, feedback)
            },
        )
    }

    taskMutationTarget?.takeIf { taskMutationEditorKind == TaskMutationKind.CANCEL && !missingFeedbackTarget }?.let { task ->
        ConfirmTaskStatusDialog(
            title = stringResource(R.string.task_cancel),
            message = stringResource(R.string.cancel_task_message),
            confirmLabel = stringResource(R.string.task_cancel),
            busy = taskMutation.busy, error = taskMutation.error,
            canConfirm = uiState.isTrusted,
            readNotice = { taskReadMessage?.let { DataReadNotice(it, taskViewModel::retryRead, !uiState.isLoading) } },
            onDismiss = closeTaskMutation,
            onConfirm = {
                taskViewModel.cancelTask(task.id)
            },
        )
    }

    taskMutationTarget?.takeIf { taskMutationEditorKind == TaskMutationKind.REPLACE && !missingFeedbackTarget }?.let { task ->
        TaskEditorDialog(
            task = task,
            dialogTitle = R.string.replace_task,
            confirmLabel = R.string.replace_task,
            allowPriorityChange = true,
            saving = taskMutation.busy, saveError = taskMutation.error,
            canSave = uiState.isTrusted,
            readNotice = { taskReadMessage?.let { DataReadNotice(it, taskViewModel::retryRead, !uiState.isLoading) } },
            onDismiss = closeTaskMutation,
            onSave = { replacement ->
                taskViewModel.replaceTask(task.id, replacement.copy(id = null))
            },
        )
    }

    correctionTarget?.takeIf { taskMutationEditorKind == TaskMutationKind.CORRECT && !missingFeedbackTarget }?.let { log ->
        CorrectExecutionLogDialog(
            log = log,
            busy = taskMutation.busy, error = taskMutation.error,
            canConfirm = uiState.isTrusted,
            readNotice = { taskReadMessage?.let { DataReadNotice(it, taskViewModel::retryRead, !uiState.isLoading) } },
            onDismiss = closeTaskMutation,
            onConfirm = { feedback ->
                taskViewModel.correctExecutionLog(log.taskId, log.id, feedback)
            },
        )
    }

    // 每日回顾决策面：完成 / 部分完成 / 顺延 三选一，全部真实落库；跳过与关闭零写入
    if (showDailyReview && uiState.isTrusted && planUiState.isTrusted) {
        val activeDateNow = LocalDate.now()
        val reviewSummary = remember(activeDateNow, uiState.tasks, planUiState.currentPlan, dailyReviewLogs) {
            DailyReviewSummarizer.summarize(activeDateNow, uiState.tasks, planUiState.currentPlan, dailyReviewLogs)
        }
        val pendingReviewItems = remember(activeDateNow, uiState.tasks, planUiState.currentPlan) {
            com.swan1127.repland.domain.model.DailyReviewActions.pendingItems(
                activeDateNow, LocalDateTime.now(), uiState.tasks, planUiState.currentPlan?.segments.orEmpty())
        }
        com.swan1127.repland.ui.review.DailyReviewDialog(
            date = activeDateNow,
            summary = reviewSummary,
            pendingItems = pendingReviewItems,
            busy = taskMutation.busy,
            canAct = uiState.isTrusted && !taskActionLocked,
            nextSuggestedTaskName = reviewSummary.nextSuggestedTaskId?.let { id -> uiState.tasks.firstOrNull { it.id == id }?.displayName },
            onComplete = { taskViewModel.completeTask(it) },
            onPartial = { openTaskMutation(TaskMutationKind.PARTIAL, it) },
            onPostpone = { taskViewModel.postponeTask(it, null) },
            onOpenTask = { showDailyReview = false; selectedTaskId = it },
            onDismiss = { if (!taskMutation.busy) showDailyReview = false },
        )
    }

    (timeUiState.timetableImport as? TimetableImportState.Review)?.let { review ->
        TimetableImportReviewDialog(
            review = review,
            clock = timeUiState.classPeriodClock,
            existingCourseBlocks = timeUiState.weeklyBlocks.filter { it.kind == TimeBlockKind.COURSE },
            canConfirm = timeUiState.isTrusted && !timeMutation.busy,
            clockBusy = timeMutation.busy,
            readNotice = { timeReadMessage?.let { DataReadNotice(it, timeViewModel::retryRead, !timeUiState.isLoading) } },
            onDismiss = timeViewModel::clearTimetableImport,
            onConfirm = timeViewModel::confirmTimetableImport,
            onSelected = timeViewModel::selectImportedCourse,
            onEditCourse = timeViewModel::updateImportedCourse,
            onClockAcknowledged = timeViewModel::acknowledgeImportClock,
            onSaveClock = timeViewModel::saveClassPeriodClock,
        )
    }

    LaunchedEffect(timeMutation.receipt) {
        timeMutation.receipt?.let { receipt ->
            when (timeMutation.kind) {
                TimeMutationKind.WEEKLY_SAVE -> if (weeklyBlockEditorId == timeMutation.targetId) showWeeklyBlockEditor = false
                TimeMutationKind.OVERRIDE_SAVE -> if (dateOverrideEditorId == timeMutation.targetId) showDateOverrideEditor = false
                TimeMutationKind.SEMESTER_SAVE -> showSemesterStartEditor = false
                TimeMutationKind.CLOCK_SAVE -> Unit
                TimeMutationKind.WEEKLY_DELETE -> if (deletingWeeklyId == timeMutation.targetId) deletingWeeklyId = null
                TimeMutationKind.OVERRIDE_DELETE -> if (deletingOverrideId == timeMutation.targetId) deletingOverrideId = null
                null -> Unit
            }
            captureSnackbar.showSnackbar(receipt)
            timeViewModel.resetMutation()
        }
    }
    if (timeUiState.isTrusted && !timeMutation.busy && timeMutation.receipt == null && ((showWeeklyBlockEditor && weeklyBlockEditorId != null && weeklyBlockEditorTarget == null) ||
        (showDateOverrideEditor && dateOverrideEditorId != null && dateOverrideEditorTarget == null) ||
        (deletingWeeklyId != null && deletingWeeklyBlock == null) || (deletingOverrideId != null && deletingDateOverride == null))) {
        AlertDialog(onDismissRequest = {}, title = { Text("原时间设置已不存在") },
            text = { Text("请关闭并重新选择，不会把本次编辑保存为新的时间设置。") },
            confirmButton = { TextButton(onClick = { showWeeklyBlockEditor = false; showDateOverrideEditor = false;
                deletingWeeklyId = null; deletingOverrideId = null; timeViewModel.resetMutation() }) { Text("关闭") } })
    }
    if (showWeeklyBlockEditor && timeUiState.hasLoaded && (weeklyBlockEditorId == null || weeklyBlockEditorTarget != null)) {
        WeeklyTimeBlockEditorDialog(
            block = weeklyBlockEditorTarget,
            initialKind = weeklyBlockInitialKind,
            busy = timeMutation.busy,
            error = timeMutation.error,
            canSave = timeUiState.isTrusted,
            readNotice = { timeReadMessage?.let { DataReadNotice(it, timeViewModel::retryRead, !timeUiState.isLoading) } },
            onDismiss = { if (!timeMutation.busy) { showWeeklyBlockEditor = false; timeViewModel.resetMutation() } },
            onSave = timeViewModel::saveWeeklyBlock,
        )
    }

    if (showDateOverrideEditor && timeUiState.hasLoaded && (dateOverrideEditorId == null || dateOverrideEditorTarget != null)) {
        DateOverrideEditorDialog(
            dateOverride = dateOverrideEditorTarget,
            busy = timeMutation.busy,
            error = timeMutation.error,
            canSave = timeUiState.isTrusted,
            readNotice = { timeReadMessage?.let { DataReadNotice(it, timeViewModel::retryRead, !timeUiState.isLoading) } },
            onDismiss = { if (!timeMutation.busy) { showDateOverrideEditor = false; timeViewModel.resetMutation() } },
            onSave = timeViewModel::saveDateOverride,
        )
    }

    if (showSemesterStartEditor && timeUiState.hasLoaded) {
        SemesterStartEditorDialog(
            semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
            busy = timeMutation.busy,
            error = timeMutation.error,
            canSave = timeUiState.isTrusted,
            readNotice = { timeReadMessage?.let { DataReadNotice(it, timeViewModel::retryRead, !timeUiState.isLoading) } },
            onDismiss = { if (!timeMutation.busy) { showSemesterStartEditor = false; timeViewModel.resetMutation() } },
            onSave = timeViewModel::saveSemesterFirstWeekMonday,
        )
    }

    deletingWeeklyBlock?.let { block ->
        DeleteTimeEntryDialog(
            title = block.title,
            busy = timeMutation.busy,
            error = timeMutation.error,
            canSave = timeUiState.isTrusted,
            readNotice = { timeReadMessage?.let { DataReadNotice(it, timeViewModel::retryRead, !timeUiState.isLoading) } },
            onDismiss = { if (!timeMutation.busy) { deletingWeeklyId = null; timeViewModel.resetMutation() } },
            onConfirm = { timeViewModel.deleteWeeklyBlock(block.id) },
        )
    }

    deletingDateOverride?.let { dateOverride ->
        DeleteTimeEntryDialog(
            title = dateOverride.title,
            busy = timeMutation.busy,
            error = timeMutation.error,
            canSave = timeUiState.isTrusted,
            readNotice = { timeReadMessage?.let { DataReadNotice(it, timeViewModel::retryRead, !timeUiState.isLoading) } },
            onDismiss = { if (!timeMutation.busy) { deletingOverrideId = null; timeViewModel.resetMutation() } },
            onConfirm = { timeViewModel.deleteDateOverride(dateOverride.id) },
        )
    }

    if (timeMutation.error != null && !showWeeklyBlockEditor && !showDateOverrideEditor && !showSemesterStartEditor &&
        deletingWeeklyId == null && deletingOverrideId == null) {
        AlertDialog(onDismissRequest = timeViewModel::resetMutation, title = { Text("时间设置未保存") },
            text = { Text(timeMutation.error.orEmpty()) },
            confirmButton = { TextButton(onClick = timeViewModel::retryMutation) { Text("重试") } },
            dismissButton = { TextButton(onClick = timeViewModel::resetMutation) { Text("取消") } })
    }

    planUiState.pendingManualChange?.let { pending ->
        val change = pending.change
        val targetId = when (change) {
            is ManualPlanChange.Place -> change.taskId
            is ManualPlanChange.Move -> planUiState.currentPlan?.segments?.find { it.id == change.segmentId }?.taskId
            is ManualPlanChange.Remove -> planUiState.currentPlan?.segments?.find { it.id == change.segmentId }?.taskId
            ManualPlanChange.Clear -> null
        }
        val taskName = uiState.tasks.find { it.id == targetId }?.displayName.orEmpty()
        val details = when (change) {
            is ManualPlanChange.Place -> "${change.date} ${TimeBlockValidator.formatTime(change.start)}–${TimeBlockValidator.formatTime(change.end)} · ${change.track}"
            is ManualPlanChange.Move -> "${TimeBlockValidator.formatTime(change.start)}–${TimeBlockValidator.formatTime(change.end)} · ${change.track}"
            is ManualPlanChange.Remove -> "移出安排，保留任务与历史"
            ManualPlanChange.Clear -> "清空当前安排，保留任务与历史"
        }
        AlertDialog(onDismissRequest = planViewModel::cancelManualChange,
            title = { Text("确认调整安排") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(taskName); Text(details); Text(pending.message)
                planUiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (planUiState.errorMessage != null) {
                    CapsuleRow(
                        capsules = listOf(CapsuleSpec("next_free", "排进下一个空闲", CapsuleKind.VERB)),
                        onCapsuleClick = { planViewModel.confirmManualChangeWithDelay() },
                    )
                }
            } },
            confirmButton = { TextButton(onClick = planViewModel::confirmManualChange, enabled = !planUiState.isWorking && planningReadsReady,
                modifier = Modifier.testTag("manual-plan-confirm")) { Text("确认调整") } },
            dismissButton = { TextButton(onClick = planViewModel::cancelManualChange, enabled = !planUiState.isWorking,
                modifier = Modifier.testTag("manual-plan-cancel")) { Text("取消，保留原安排") } })
    }
    planUiState.errorMessage?.takeIf { planUiState.pendingManualChange == null && !showPlanOverview }?.let { message ->
        val failed = planUiState.failedChange?.takeIf { it is ManualPlanChange.Place || it is ManualPlanChange.Move }
        AlertDialog(
            onDismissRequest = { if (failed == null) planViewModel.dismissError() else planViewModel.dismissFailedChange() },
            title = { Text("安排未保存") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message)
                    if (failed != null) {
                        CapsuleRow(
                            capsules = listOf(
                                CapsuleSpec("postpone", "搁置", CapsuleKind.VERB),
                                CapsuleSpec("next_free", "排进下一个空闲", CapsuleKind.VERB),
                            ),
                            onCapsuleClick = { spec ->
                                when (spec.key) {
                                    "postpone" -> planViewModel.dismissFailedChange()
                                    "next_free" -> planViewModel.retryFailedChangeIntoNextFree()
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = { if (failed == null) TextButton(onClick = planViewModel::dismissError) { Text("知道了") } },
        )
    }
    if (showQuickAvailability) {
        com.swan1127.repland.ui.plan.QuickAvailabilityDialog(
            busy = planUiState.isWorking, error = planUiState.errorMessage,
            canSave = planningReadsReady,
            readNotice = { readMessage?.let { DataReadNotice(it, retryReads, canRetryReads) } },
            onDismiss = { showQuickAvailability = false },
            onSave = { value -> planViewModel.saveAvailabilityAndGenerate(value, categoryPreferenceUiState.weights) { showQuickAvailability = false } },
        )
    }
    if (planUiState.isGenerating) {
        PlanningTeamStatusDialog(
            isGenerating = true,
            events = planUiState.teamEvents,
            error = null,
            onCancel = planViewModel::cancelGeneration,
            onRetry = { generatePlanDraft() },
        )
    }
    planUiState.draft?.takeIf { !planUiState.isGenerating && it.generatedAt.toString() != pausedPlanDraftIdentity && !showQuickAvailability && !(showExecutionSession && executionSession != null) }?.let { draft ->
        PlanDraftDialog(
            draft = draft,
            currentPlan = planUiState.currentPlan,
            currentTaskOrder = planUiState.taskOrder,
            isSaving = planUiState.isWorking,
            canAccept = planningReadsReady,
            canEdit = planningReadsReady,
            retainOnDismiss = !planUiState.isTrusted || !workspaceUiState.isTrusted,
            readNotice = { readMessage?.let { DataReadNotice(it, retryReads, canRetryReads) } },
            planningTeamContent = { PlanningTeamLogPanel(planUiState.teamEvents, planUiState.teamResult) },
            tracks = workspaceUiState.tracks,
            onCompleteTaskDetails = { task -> planViewModel.discardDraft(); taskViewModel.resetEditorResult(); taskEditorTargetId = task.id; showTaskEditor = true },
            onConfigureAvailability = { planViewModel.dismissError(); showQuickAvailability = true },
            tasks = uiState.tasks,
            weeklyBlocks = timeUiState.weeklyBlocks,
            dateOverrides = timeUiState.dateOverrides,
            semesterFirstWeekMonday = timeUiState.semesterFirstWeekMonday,
            onDismiss = {
                if (!planUiState.isTrusted || !workspaceUiState.isTrusted) {
                    pausedPlanDraftIdentity = draft.generatedAt.toString()
                } else {
                    planViewModel.discardDraft()
                    if (reviewingAgentDraft) {
                        planningAgentViewModel.dismiss()
                        reviewingAgentDraft = false
                    }
                }
            },
            onUpdateDraft = planViewModel::updateDraft,
            onCommitSegmentDraft = planViewModel::saveDraftEdit,
            segmentSaveReceipt = planUiState.draftEditReceipt,
            segmentSaveError = planUiState.draftEditError,
            onClearSegmentSaveFeedback = planViewModel::clearDraftEditFeedback,
            onAccept = {
                if (!planningReadsReady) {
                    feedbackSnackbarScope.launch { captureSnackbar.showSnackbar("请先可靠读取任务和时间；原计划与草案仍保留。") }
                } else if (reviewingAgentDraft) {
                    planViewModel.acceptDraft {
                        planningAgentViewModel.markAccepted()
                        reviewingAgentDraft = false
                    }
                } else {
                    planViewModel.acceptDraft()
                }
            },
        )
    }

    LaunchedEffect(orderUndoOffer) {
        if (!orderUndoOffer) return@LaunchedEffect
        val result = captureSnackbar.showSnackbar("任务顺序已更新", actionLabel = "撤销排序",
            duration = androidx.compose.material3.SnackbarDuration.Long)
        planViewModel.clearOrderUndoOffer()
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) planViewModel.undoTaskOrder()
    }

    if (showPlanOverview) {
        PlanOverviewDialog(
            plans = planUiState.planHistory,
            tasks = uiState.tasks,
            busy = planUiState.isWorking || !planningReadsReady,
            error = planUiState.errorMessage,
            onDismiss = { showPlanOverview = false },
            onRestore = { planId ->
                planViewModel.restore(planId)
            },
            onClearCurrent = {
                showPlanOverview = false
                showClearPlanConfirmation = true
            },
            onToggleSegmentLock = planViewModel::setSegmentLocked,
        )
    }

    if (showClearPlanConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearPlanConfirmation = false },
            title = { Text(stringResource(R.string.clear_current_plan_title)) },
            text = { Text(stringResource(R.string.clear_current_plan_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        planViewModel.clearCurrentPlan()
                        showClearPlanConfirmation = false
                    },
                ) { Text(stringResource(R.string.clear_current_plan), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearPlanConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showClearLocalDataConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearLocalDataConfirmation = false },
            title = { Text(stringResource(R.string.clear_local_data_title)) },
            text = { Text(stringResource(R.string.clear_local_data_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The old plan IDs are still available here, so their local alarms
                        // are cancelled before the explicit database reset removes them.
                        reminderScheduler.sync(
                            isEnabled = false,
                            currentPlan = null,
                            planHistory = planUiState.planHistory,
                            tasks = uiState.tasks,
                            weeklyBlocks = timeUiState.weeklyBlocks,
                            dateOverrides = timeUiState.dateOverrides,
                        )
                        dataManagementViewModel.clearAllLocalData(
                            beforeClear = taskCaptureViewModel::prepareForDataClear,
                            afterClear = { succeeded ->
                                taskCaptureViewModel.finishDataClear(succeeded)
                                if (succeeded) memoryRevision += 1
                            },
                        )
                        showClearLocalDataConfirmation = false
                    },
                ) {
                    Text(stringResource(R.string.clear_local_data_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLocalDataConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showAiConsentDialog || planningAgentUiState.workflow is PlanningAgentState.AwaitingConsent) {
        AiConsentDialog(
            onDismiss = {
                showAiConsentDialog = false
                if (planningAgentUiState.workflow is PlanningAgentState.AwaitingConsent) {
                    planningAgentViewModel.dismiss()
                }
            },
            onConfirm = {
                planningAgentViewModel.grantConsentAndEnable()
                showAiConsentDialog = false
            },
        )
    }

    (planningAgentUiState.workflow as? PlanningAgentState.PreviewingRequest)?.let { preview ->
        AiRequestPreviewDialog(
            request = preview.request,
            onDismiss = planningAgentViewModel::dismiss,
            onConfirm = planningAgentViewModel::confirmPreview,
        )
    }
    }
}

/** Translate reserved timer outcomes for display; retain stored evidence and other user text. */
@Composable
private fun completionResultText(result: String): String {
    val label = when (result) {
        ExecutionOutcome.CONTINUE.name -> R.string.execution_result_continue
        ExecutionOutcome.PARTIAL.name -> R.string.execution_result_partial
        ExecutionOutcome.COMPLETED.name -> R.string.execution_result_completed
        ExecutionOutcome.SKIPPED.name -> R.string.execution_result_skipped
        else -> return result
    }
    return stringResource(label)
}

/** 跨页常驻的对话入口：占位文案随页换话术，点开对应聊天面（入口即对话种子）。 */
@Composable
private fun ComposerBar(
    placeholder: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = 48.dp)
            .testTag("composer-bar"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                DesignIcons.Assistant,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabIcon(tab: AppTab) {
    val icon = when (tab) {
        AppTab.TODAY -> DesignIcons.Calendar
        AppTab.TASKS -> DesignIcons.Tasks
        AppTab.AGENT -> DesignIcons.Assistant
        AppTab.TEAM -> DesignIcons.Team
        AppTab.TIME -> DesignIcons.Calendar
        AppTab.MINE -> DesignIcons.Profile
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
}

/** 中途退出后，本轮专注化作导航栏上方的一条半透明浮条；轻触回到盖楼页。 */
@Composable
private fun FocusPill(
    session: com.swan1127.repland.domain.model.ExecutionSession,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMillis by produceState(initialValue = System.currentTimeMillis(), session.id) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = session.elapsedMillis(nowMillis)
    val secondsLeft = ((session.targetSeconds * 1_000L - elapsed).coerceAtLeast(0L) + 999L) / 1_000L
    val toppedOut = session.targetSeconds > 0 && secondsLeft == 0L
    val status = when {
        session.isPaused -> "已暂停"
        toppedOut -> "已盖满"
        else -> "专注中"
    }
    val accent = when {
        toppedOut -> MaterialTheme.colorScheme.tertiary
        session.isPaused -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val clock = remember(secondsLeft) {
        String.format(java.util.Locale.US, "%02d:%02d", secondsLeft / 60, secondsLeft % 60)
    }
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp)
            .testTag("focus-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        Text(status, style = MaterialTheme.typography.labelMedium, color = accent)
        Text(
            session.taskTitle,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            clock,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Serif,
                fontFeatureSettings = "tnum",
            ),
        )
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TodayScreen(
    executionSession: com.swan1127.repland.domain.model.ExecutionSession?,
    executionError: String?,
    executionReadsReady: Boolean,
    executionFinished: Boolean,
    onReplanRemaining: () -> Unit,
    onDismissExecutionResult: () -> Unit,
    planNeedsUpdate: Boolean,
    onReviewPlanChanges: () -> Unit,
    date: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    tasks: List<Task>,
    allTasks: List<Task>,
    weeklyBlocks: List<WeeklyTimeBlock>,
    dateOverrides: List<DateOverride>,
    semesterFirstWeekMonday: LocalDate?,
    engagementMode: EngagementMode,
    tracks: List<RhythmTrack>,
    onOpenTimelineEntry: (String) -> Unit,
    onEditTimelineEntry: (TimelineEntry) -> Unit,
    planSegments: List<PlannedSegment>,
    allPlanSegments: List<PlannedSegment>,
    hasConfirmedPlan: Boolean,
    pendingConfirmationSegments: List<PlannedSegment>,
    onOpenDailyReview: () -> Unit = {},
    isLoading: Boolean,
    onOpen: (Task) -> Unit,
    onStart: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenTaskLibrary: () -> Unit,
    onPlaceEvent: (String, LocalDate, Int, Int, String) -> Unit,
    onFocusStarted: (String) -> Unit,
    onRemovePlacement: (String) -> Unit,
    onUpdateEntryTime: (TimelineEntry, Int, Int, LocalDate?, () -> Unit) -> Unit,
    onDropEntry: (TimelineEntry, Int, String?) -> Unit,
    onToggleEntryReminder: (TimelineEntry, Boolean) -> Unit,
    timelineWriteBusy: Boolean,
    timelineWriteError: String?,
    reminderNotice: String? = null,
    automaticPlanning: Boolean = false,
) {
    if (isLoading) return
val scheduleDate = date
    var showPending by rememberSaveable { mutableStateOf(false) }
    var scheduleScope by rememberSaveable { mutableStateOf("day") }
    var focusNow by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { focusNow = LocalDateTime.now(); delay(30_000) }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 本轮结果条插在列表最前；结束后主动回到顶部，不让用户自己往回滚才发现它。
    LaunchedEffect(executionFinished) {
        if (executionFinished) listState.animateScrollToItem(0)
    }
    val taskById = allTasks.associateBy(Task::id)
    val timelineEntries = ScheduleTimeline.entries(scheduleDate, weeklyBlocks, dateOverrides, allPlanSegments, allTasks, semesterFirstWeekMonday)
    val nowMinute = focusNow.hour * 60 + focusNow.minute
    val pendingEntries = TodayFocus.pending(timelineEntries, focusNow, executionSession?.taskId)
    if (showPending) {
        AlertDialog(
            onDismissRequest = { showPending = false },
            title = { Text("确认任务结果") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("时段已结束，结果未确认")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(pendingEntries, key = { it.taskId!! }) { entry ->
                            TextButton(onClick = { taskById[entry.taskId]?.let(onOpen); showPending = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(entry.title)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPending = false }) { Text("稍后确认") } },
        )
    }
    val currentOrNext = planSegments
        .filter { it.endMinute > nowMinute }
        .minByOrNull { it.startMinute }
    val currentTask = currentOrNext?.let { taskById[it.taskId] }
    val plannedTaskIds = planSegments.map(PlannedSegment::taskId).toSet()
    val placedTaskIdsForScheduleDate = allPlanSegments.filter { it.date == scheduleDate }.map(PlannedSegment::taskId).toSet()
    // 这一列只按「今天有没有排过」过滤，昨天排掉一半的事在这儿看着仍像一件全新事项，
    // 点一下就悄悄多出第二段。把别处的排期一起带下去，界面才分得清自己是在加还是在挪。
    val placementsOnOtherDays = allPlanSegments.filter { it.date != scheduleDate }.groupBy(PlannedSegment::taskId)
    val scheduledMinutes = planSegments.sumOf { (it.endMinute - it.startMinute).coerceAtLeast(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("today-page-scroll"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp,
            bottom = 16.dp + if (executionSession != null) 56.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (executionError != null) {
            item(key = "execution-error") { Text(executionError, color = MaterialTheme.colorScheme.error) }
        }
        if (pendingConfirmationSegments.isNotEmpty()) {
            // 轻量条目，不再占首屏整卡：教学口径住在每日回顾的目的地里。
            item(key = "daily-review-entry") {
                Surface(
                    onClick = onOpenDailyReview,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().testTag("open-daily-review"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.pending_confirmation_segments) + " · ${pendingConfirmationSegments.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.open_daily_review),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (executionFinished) {
            item(key = "execution-result") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (automaticPlanning) "本轮记录已保存" else "本轮记录已保存 · 原计划不变")
                    if (!automaticPlanning) OutlinedButton(onClick = onReplanRemaining, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("execution-replan")) {
                        Text("预览剩余任务的新安排")
                    }
                    TextButton(onClick = onDismissExecutionResult, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (automaticPlanning) "知道了" else "保留原计划") }
                }
            }
        }
        if (planNeedsUpdate && executionSession == null && !executionFinished) {
            item(key = "plan-needs-update") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("信息已变化 · 当前计划不变")
                    OutlinedButton(onClick = onReviewPlanChanges, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-changed-plan")) {
                        Text("预览更新后的安排")
                    }
                }
            }
        }
        if (!planNeedsUpdate && executionSession == null && !executionFinished &&
            allTasks.any { it.status.isActive } && (!hasConfirmedPlan || !automaticPlanning)) {
            item(key = "preview-task-plan") {
                OutlinedButton(
                    onClick = onReviewPlanChanges,
                    enabled = executionReadsReady,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preview-task-plan"),
                ) { Text(stringResource(R.string.preview_task_plan)) }
            }
        }
        item(key = "schedule-create") {
            if (timelineEntries.isEmpty()) {
                ScheduleCreateCard(onAdd = onAdd)
            } else {
                ScheduleStackCard(
                    entries = timelineEntries,
                    now = focusNow,
                    onOpenEntry = { onOpenTimelineEntry(it.id) },
                    onAdd = onAdd,
                )
            }
        }
        // Stable identity keeps display mode and detail state when banners
        // above the schedule appear or disappear after plan changes.
        item(key = "schedule-day") {
            when (scheduleScope) {
                "week" -> WeekScheduleView(
                    date = scheduleDate,
                    weeklyBlocks = weeklyBlocks,
                    dateOverrides = dateOverrides,
                    planSegments = allPlanSegments,
                    tasks = allTasks,
                    semesterFirstWeekMonday = semesterFirstWeekMonday,
                    mode = engagementMode,
                    onOpenEntry = onOpenTimelineEntry,
                    onSelectDate = onDateSelected,
                    tracks = tracks,
                    scope = scheduleScope,
                    onScopeChange = { scheduleScope = it },
                )
                "month" -> MonthScheduleView(
                    date = scheduleDate,
                    weeklyBlocks = weeklyBlocks,
                    dateOverrides = dateOverrides,
                    planSegments = allPlanSegments,
                    tasks = allTasks,
                    semesterFirstWeekMonday = semesterFirstWeekMonday,
                    onSelectDate = onDateSelected,
                    onOpenEntry = onOpenTimelineEntry,
                    tracks = tracks,
                    scope = scheduleScope,
                    onScopeChange = { scheduleScope = it },
                )
                else -> TimelineDashboard(
                    entries = timelineEntries,
                    mode = engagementMode,
                    onOpenEntry = onOpenTimelineEntry,
                    onEditEntry = onEditTimelineEntry,
                    eventObjects = allTasks
                        .filter { it.id !in placedTaskIdsForScheduleDate && it.status.isActive }
                        .map { task ->
                            val placed = placementsOnOtherDays[task.id].orEmpty()
                            TimelineEventObject(
                                task.id, task.displayName, task.totalDurationMinutes, task.category,
                                placed.map(PlannedSegment::date).distinct().sorted(),
                                placed.sumOf { (it.endMinute - it.startMinute).coerceAtLeast(0) },
                            )
                        },
                    onPlaceEvent = { event, trackId, startMinute, endMinute ->
                        onPlaceEvent(event.id, scheduleDate, startMinute, endMinute, trackId)
                    },
                    scheduleDate = scheduleDate,
                    writeBusy = timelineWriteBusy,
                    writeError = timelineWriteError,
                    onFocusStarted = onFocusStarted,
                    onRemovePlacement = onRemovePlacement,
                    onUpdateEntryTime = onUpdateEntryTime,
                    onDropEntry = onDropEntry,
                    onToggleEntryReminder = onToggleEntryReminder,
                    reminderNotice = reminderNotice,
                    scope = scheduleScope,
                    onScopeChange = { scheduleScope = it },
                )
            }
        }
    }
}

@Composable
private fun CompactSectionHeader(title: String, count: Int, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("  $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TasksScreen(
    tasks: List<Task>,
    isLoading: Boolean,
    onOpen: (Task) -> Unit,
    onStart: (String) -> Unit,
    onAdd: () -> Unit,
    confirmedSegments: List<PlannedSegment> = emptyList(),
    onBatchAction: (BatchTaskAction, Set<String>) -> Unit = { _, _ -> },
) {
    var view by rememberSaveable { mutableStateOf(TaskCenterView.OVERVIEW) }
    // null = not in selection mode; otherwise the ticked ids. `remember` on purpose: a tick set is
    // a transient gesture, and D:\repland's original made the same trade.
    var taskSelection by remember { mutableStateOf<TaskSelection?>(null) }
    var pendingBatch by remember { mutableStateOf<BatchTaskAction?>(null) }
    val activeTasks = tasks.filter { it.status.isActive }
    val today = LocalDate.now()
    val activeIds = activeTasks.map { it.id }.toSet()
    val placementLabels = confirmedSegments.filter { it.date >= today && it.taskId in activeIds }.sortedWith(compareBy({ it.date }, { it.startMinute }))
        .groupBy { it.taskId }.mapValues { (_, placements) ->
            val slot = placements.first()
            fun time(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
            "已安排 · ${slot.date.monthValue}月${slot.date.dayOfMonth}日 ${time(slot.startMinute)}–${time(slot.endMinute)}"
        }
    val visibleTasks = when (view) {
        TaskCenterView.OVERVIEW -> activeTasks
        TaskCenterView.INBOX -> activeTasks.filter { com.swan1127.repland.domain.model.TaskPlanMembership.isInbox(it, confirmedSegments, today) }
        TaskCenterView.TODAY -> activeTasks.filter { com.swan1127.repland.domain.model.TaskPlanMembership.isToday(it, confirmedSegments, today) }
        TaskCenterView.COMPLETED -> tasks.filter { !it.status.isActive }
    }
    // Switching filters must not leave ticks on rows the user can no longer see: the bar's count
    // stays a statement about the rows actually on screen.
    LaunchedEffect(taskSelection, visibleTasks) {
        taskSelection?.let { current ->
            val kept = current.retained(visibleTasks)
            if (kept != current) taskSelection = kept
        }
    }
    // 长按进多选，按住拖动继续勾；手指停在列表上下边缘就自己滚，不用腾出手再滑一次。
    val listState = rememberLazyListState()
    var listTopPx by remember { mutableFloatStateOf(0f) }
    var listHeightPx by remember { mutableFloatStateOf(0f) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var dragFromY by remember { mutableFloatStateOf(Float.NaN) }
    var dragEdge by remember { mutableIntStateOf(0) }
    val knownIds = remember(tasks) { tasks.mapTo(mutableSetOf(), Task::id) }
    // 勾的是「手指扫过的那一段」，不是只有落点那一行：快速拖动时两次采样之间隔着一整行也不能漏。
    fun paintBetween(fromRootY: Float, toRootY: Float) {
        val top = minOf(fromRootY, toRootY) - listTopPx
        val bottom = maxOf(fromRootY, toRootY) - listTopPx
        val swept = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val key = item.key as? String ?: return@mapNotNull null
            if (key in knownIds && item.offset < bottom && item.offset + item.size > top) key else null
        }
        if (swept.isEmpty()) return
        taskSelection = swept.fold(taskSelection ?: TaskSelection.EMPTY) { acc, id -> acc.added(id) }
    }
    LaunchedEffect(dragEdge) {
        while (dragEdge != 0) {
            if (listState.scrollBy(dragEdge * 28f) == 0f) break
            // 勾到视口边缘，而不是只勾手指底下那一行：列表底部有留白，
            // 滚到底时最后几行永远停在留白上方，只认落点就会漏掉它们。
            paintBetween(dragPointerY, if (dragEdge > 0) listTopPx + listHeightPx else listTopPx)
            delay(16)
        }
        dragEdge = 0
    }
    val selectionDrag = SelectionDrag(
        onStart = { id, y ->
            dragFromY = y
            dragPointerY = y
            taskSelection = (taskSelection ?: TaskSelection.EMPTY).added(id)
        },
        onDrag = { y ->
            paintBetween(if (dragFromY.isNaN()) y else dragFromY, y)
            dragPointerY = y
            dragFromY = y
            val local = y - listTopPx
            dragEdge = when {
                listHeightPx <= 0f -> 0
                local > listHeightPx - 150f -> 1
                local < 150f -> -1
                else -> 0
            }
        },
        onEnd = { dragEdge = 0; dragFromY = Float.NaN },
    )
    val listBounds = Modifier.onGloballyPositioned {
        listTopPx = it.boundsInRoot().top
        listHeightPx = it.size.height.toFloat()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        val selection = taskSelection
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskCenterView.entries.forEach { option ->
                FilterChip(selected = view == option, onClick = { view = option }, label = { Text(option.label) }, modifier = Modifier.testTag("task-filter-${option.name}"))
            }
        }
        TaskSelectionIconRow(
            selection = selection,
            onEnter = { taskSelection = TaskSelection.EMPTY },
            onExit = { taskSelection = null },
            onSelectAll = { taskSelection = (selection ?: TaskSelection.EMPTY).allOf(visibleTasks) },
            onInvert = { taskSelection = (selection ?: TaskSelection.EMPTY).inverted(visibleTasks) },
        )
        if (!isLoading && visibleTasks.isEmpty()) {
            EmptyState(
                title = when (view) {
                    TaskCenterView.INBOX -> "收件箱是空的"
                    TaskCenterView.TODAY -> "今天还没有任务"
                    TaskCenterView.COMPLETED -> "还没有完成记录"
                    TaskCenterView.OVERVIEW -> "还没有需要处理的任务"
                },
                actionLabel = if (view == TaskCenterView.COMPLETED) null else "添加任务",
                onAction = onAdd,
                artwork = R.drawable.empty_tasks,
            )
        } else if (!isLoading) {
            val rowSelection: (Task) -> Boolean? = { task -> taskSelection?.contains(task.id) }
            val onToggle: (Task) -> Unit = { task -> taskSelection = taskSelection?.toggle(task.id) }
            Box(modifier = Modifier.weight(1f)) {
                if (view == TaskCenterView.OVERVIEW) {
                    TaskControlCenter(
                        tasks = activeTasks,
                        selected = rowSelection,
                        onToggleSelect = onToggle,
                        onOpen = onOpen,
                        onStart = onStart,
                        confirmedSegments = confirmedSegments,
                        placementLabels = placementLabels,
                        listState = listState,
                        selectionDrag = selectionDrag,
                        modifier = listBounds,
                    )
                } else {
                    TaskList(
                        tasks = visibleTasks,
                        selected = rowSelection,
                        onToggleSelect = onToggle,
                        onOpen = onOpen,
                        onStart = onStart,
                        placementLabels = placementLabels,
                        listState = listState,
                        selectionDrag = selectionDrag,
                        modifier = listBounds,
                    )
                }
            }
        }
        if (selection != null) {
            BatchActionBar(
                actions = BatchTasks.actionsFor(selection, visibleTasks),
                enabled = !selection.isEmpty,
                onAction = { pendingBatch = it },
            )
        }
    }
    pendingBatch?.let { action ->
        val selection = taskSelection ?: TaskSelection.EMPTY
        val targets = BatchTasks.targets(action, selection, visibleTasks)
        BatchConfirmDialog(
            action = action,
            targetCount = targets.size,
            skippedCount = selection.size - targets.size,
            onConfirm = {
                onBatchAction(action, selection.ids)
                pendingBatch = null
                taskSelection = null
            },
            onDismiss = { pendingBatch = null },
        )
    }
}

/** 多选/全选/反选收在同一行图标里，不再各占一个文字按钮。 */
@Composable
private fun TaskSelectionIconRow(
    selection: TaskSelection?,
    onEnter: () -> Unit,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection == null) {
            SelectionIcon("batch-enter", DesignIcons.Tasks, "多选", onEnter)
            SelectionIcon("batch-select-all", DesignIcons.SelectAll, "全选", onSelectAll)
        } else {
            SelectionIcon("batch-exit", Icons.Outlined.Close, "退出多选", onExit)
            Text(
                text = stringResource(R.string.batch_selected_count, selection.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
                    .testTag("batch-count"),
            )
            SelectionIcon("batch-select-all", DesignIcons.SelectAll, "全选", onSelectAll)
            SelectionIcon("batch-invert", DesignIcons.InvertSelection, "反选", onInvert)
        }
    }
}

@Composable
private fun SelectionIcon(tag: String, icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp).testTag(tag)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(21.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BatchActionBar(
    actions: List<BatchTaskAction>,
    enabled: Boolean,
    onAction: (BatchTaskAction) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (actions.size == 1) {
                val action = actions.first()
                Button(
                    onClick = { onAction(action) },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("batch-action-${action.name.lowercase()}"),
                ) { Text(stringResource(action.labelRes)) }
            } else {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Button(
                        onClick = { menuOpen = true },
                        enabled = enabled && actions.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("batch-action-menu"),
                    ) { Text("批量执行") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        actions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(stringResource(action.labelRes)) },
                                onClick = { menuOpen = false; onAction(action) },
                                modifier = Modifier.testTag("batch-action-${action.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchConfirmDialog(
    action: BatchTaskAction,
    targetCount: Int,
    skippedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.batch_confirm_body, targetCount, stringResource(action.labelRes)))
                if (skippedCount > 0) {
                    Text(
                        text = stringResource(R.string.batch_confirm_skipped, skippedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = targetCount > 0, modifier = Modifier.testTag("batch-confirm")) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("batch-cancel")) { Text("再想想") } },
    )
}

private enum class TaskCenterView(val label: String) {
    OVERVIEW("总览"), INBOX("待安排"), TODAY("今天"), COMPLETED("已完成"),
}

@Composable
private fun TaskControlCenter(
    tasks: List<Task>,
    selected: (Task) -> Boolean? = { null },
    onToggleSelect: (Task) -> Unit = {},
    onOpen: (Task) -> Unit,
    onStart: (String) -> Unit,
    confirmedSegments: List<PlannedSegment> = emptyList(),
    placementLabels: Map<String, String> = emptyMap(),
    listState: LazyListState = rememberLazyListState(),
    selectionDrag: SelectionDrag? = null,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val attention = tasks.filter {
        it.dueDate?.isBefore(today) == true ||
            it.scheduledForDate?.isBefore(today) == true ||
            it.status == TaskStatus.POSTPONED
    }
    val todayTasks = tasks.filter { com.swan1127.repland.domain.model.TaskPlanMembership.isToday(it, confirmedSegments, today) && it !in attention }
    val inbox = tasks.filter { com.swan1127.repland.domain.model.TaskPlanMembership.isInbox(it, confirmedSegments, today) && it !in attention && it !in todayTasks }
    val later = tasks.filter { it !in attention && it !in todayTasks && it !in inbox }
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("task-groups-scroll"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("${tasks.size} 件未完成 · ${tasks.count { it.status == TaskStatus.IN_PROGRESS }} 件进行中" +
                if (attention.isNotEmpty()) " · ${attention.size} 件需处理" else "",
                modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listOf("需要处理" to attention, "今天" to todayTasks, "待安排" to inbox, "更晚" to later).forEach { (title, group) ->
            if (group.isNotEmpty()) {
                item(key = "heading-$title") {
                    CompactSectionHeader(title, group.size,
                        action = if (group.size > 3) { if (expandedGroup == title) "收起" else "查看全部" } else null,
                        onAction = { expandedGroup = if (expandedGroup == title) null else title },
                        modifier = Modifier.testTag("task-group-$title"))
                }
                items(if (expandedGroup == title) group else group.take(3), key = Task::id) { task ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        TaskRow(task, { onOpen(task) }, { onStart(task.id) }, emphasis = if (title == "需要处理") TaskRowEmphasis.ATTENTION else TaskRowEmphasis.NORMAL,
                            planningSummary = placementLabels[task.id],
                            selected = selected(task),
                            onToggleSelect = { onToggleSelect(task) },
                            selectionDrag = selectionDrag)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    selected: (Task) -> Boolean? = { null },
    onToggleSelect: (Task) -> Unit = {},
    onOpen: (Task) -> Unit,
    onStart: (String) -> Unit,
    placementLabels: Map<String, String> = emptyMap(),
    listState: LazyListState = rememberLazyListState(),
    selectionDrag: SelectionDrag? = null,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("task-list-scroll"),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tasks, key = Task::id) { task ->
            TaskRow(
                task = task,
                onOpen = { onOpen(task) },
                onStart = { onStart(task.id) },
                planningSummary = placementLabels[task.id],
                selected = selected(task),
                onToggleSelect = { onToggleSelect(task) },
                selectionDrag = selectionDrag,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TaskDetailScreen(
    task: Task,
    executionLogs: List<TaskExecutionLog>,
    actionsLocked: Boolean,
    savingRecord: Boolean,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    snackbarBottomInset: androidx.compose.ui.unit.Dp,
    onSnackbarSize: (Int) -> Unit,
    readMessage: String?,
    onRetryReads: () -> Unit,
    onBack: () -> Unit,
    onStart: (String) -> Unit,
    placement: PlannedSegment?,
    defaultDate: LocalDate,
    timeWriteBusy: Boolean,
    timeWriteError: String?,
    onSaveTime: (Int, Int, LocalDate?) -> Boolean,
    onEdit: () -> Unit,
    onPostpone: () -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    onFeedback: () -> Unit,
    onOpenAssistant: () -> Unit,
    onReplace: () -> Unit,
    onRestore: () -> Unit,
    onCorrectLog: (TaskExecutionLog) -> Unit,
    onReviewAdjustmentDraft: () -> Unit,
    isAiEnabled: Boolean,
    planningAgentState: PlanningAgentState,
    onReviewAgentDraft: (PlanDraft) -> Unit,
) {
    var showTimeEditor by rememberSaveable(task.id) { mutableStateOf(false) }
    // 没排过的事默认从「现在之后第一个 15 分钟格」起算，但轮盘上的数字是用户自己定的，
    // 所以不像一键排入那样替他把 23 点以后也当成可选时段。
    val editorDuration = placement?.let { it.endMinute - it.startMinute }
        ?: task.totalDurationMinutes?.let { com.swan1127.repland.domain.model.TaskPlacementPolicy.durationForTask(it) }
        ?: 30
    val editorStart = placement?.startMinute ?: run {
        val clock = java.time.LocalDateTime.now()
        val floor = if (clock.toLocalDate() == LocalDate.now()) {
            com.swan1127.repland.domain.model.TaskPlacementPolicy.floorMinuteFor(clock)
        } else {
            8 * 60
        }
        // 默认值也不能停在 23 点以后：那是工具替人挑了一个压在睡眠上的钟点，
        // 而轮盘上的数字看起来就像用户自己选的。
        floor.coerceAtMost(com.swan1127.repland.domain.model.ChatDayPlanner.SLEEP_START_MINUTE - editorDuration)
            .coerceIn(0, 1_439)
    }
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState,
            modifier = Modifier.onSizeChanged { onSnackbarSize(it.height) }) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = task.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("task-detail-back"),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    var actionsMenuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { actionsMenuOpen = true }, modifier = Modifier.testTag("task-detail-more")) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = actionsMenuOpen, onDismissRequest = { actionsMenuOpen = false }) {
                        // 团队镜像的名称/期限/去留由团队域决定；这里只做「我做完了」回传。
                        if (task.status != TaskStatus.REPLACED && !task.isTeamMirror) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_task)) },
                                onClick = { actionsMenuOpen = false; onEdit() },
                                modifier = Modifier.testTag("menu-edit-task"),
                            )
                        }
                        if (task.status.isActive && !task.isTeamMirror) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.postpone)) },
                                onClick = { actionsMenuOpen = false; onPostpone() },
                                modifier = Modifier.testTag("menu-postpone-task"),
                            )
                        }
                        if (task.status.isActive && !task.isTeamMirror) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.replace_task)) },
                                onClick = { actionsMenuOpen = false; onReplace() },
                                modifier = Modifier.testTag("menu-replace-task"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.task_cancel), color = MaterialTheme.colorScheme.error) },
                                onClick = { actionsMenuOpen = false; onCancel() },
                                modifier = Modifier.testTag("menu-cancel-task"),
                            )
                        }
                        if (isAiEnabled && task.status != TaskStatus.REPLACED) {
                            DropdownMenuItem(
                                text = { Text("AI 协助") },
                                onClick = { actionsMenuOpen = false; onOpenAssistant() },
                                modifier = Modifier.testTag("menu-ai-assist"),
                            )
                        }
                        if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.restore_task)) },
                                onClick = { actionsMenuOpen = false; onRestore() },
                                modifier = Modifier.testTag("menu-restore-task"),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = snackbarBottomInset)
                .testTag("task-detail-scroll"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            readMessage?.let { item { DataReadNotice(it, onRetryReads) } }
            if (savingRecord) item { Text("正在保存本次记录…", modifier = Modifier.semantics {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            }) }
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = stringResource(task.status.labelRes()),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = task.displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        task.description.takeIf { it.isNotBlank() && it != task.displayName }?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (task.isTeamMirror) DetailMetaPill("第二课堂 · ${task.originLabel ?: "任务"}")
                            if (task.category != TaskCategory.UNSPECIFIED) DetailMetaPill(stringResource(task.category.labelRes()))
                            if (task.userPriority != TaskPriority.UNSPECIFIED) DetailMetaPill(stringResource(task.userPriority.labelRes()))
                            task.totalDurationMinutes?.let {
                                DetailMetaPill(stringResource(R.string.duration_format, it))
                            }
                            task.dueDate?.let { DetailMetaPill("截止 ${taskDateLabel(it)}") }
                            task.scheduledForDate?.let { DetailMetaPill("计划 ${taskDateLabel(it)}") }
                        }
                        if (task.status.isActive) {
                            Spacer(Modifier.height(16.dp))
                            // 排到哪一天说不清「什么时候做」：这一行把时段本身讲出来，并且点一下就改。
                            DesignClickableSurface(
                                onClick = { showTimeEditor = true },
                                enabled = !actionsLocked && !timeWriteBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("task-detail-set-time"),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            placement?.let {
                                                "已安排 · ${taskDateLabel(it.date)} " +
                                                    "${TimeBlockValidator.formatTime(it.startMinute)}–" +
                                                    TimeBlockValidator.formatTime(it.endMinute)
                                            } ?: "还没定时间",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                    Text(
                                        if (placement == null) "定时间" else "改时间",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                        if (task.status == TaskStatus.NOT_STARTED || task.status == TaskStatus.POSTPONED) {
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { onStart(task.id) }, enabled = !actionsLocked,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start-task")) {
                                Text("开始这件事")
                            }
                        }
                    }
                }
            }
            item {
                val hasFeedback = task.completionSummary != null || task.completionResult != null ||
                    task.actualDurationMinutes != null || task.progressPercent != null
                DetailSection(title = "执行记录") {
                    if (hasFeedback) {
                        task.completionSummary?.let { content ->
                            DetailLine(stringResource(R.string.completed_content), content)
                        }
                        task.completionResult?.let { result ->
                            DetailLine(stringResource(R.string.completion_result), completionResultText(result))
                        }
                        task.actualDurationMinutes?.let { minutes ->
                            DetailLine(
                                stringResource(R.string.actual_duration_optional),
                                stringResource(R.string.duration_format, minutes),
                            )
                        }
                        task.progressPercent?.let { progress ->
                            DetailLine(
                                stringResource(R.string.progress_optional),
                                stringResource(R.string.progress_format, progress),
                            )
                        }
                        if (executionLogs.isNotEmpty()) Spacer(Modifier.height(10.dp))
                    }
                    if (executionLogs.isEmpty() && !hasFeedback) {
                        Text(
                            stringResource(R.string.no_execution_logs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        executionLogs.forEach { log ->
                            ExecutionLogCard(log = log, actionsLocked = actionsLocked, onCorrect = { onCorrectLog(log) })
                        }
                    }
                }
            }
            if (task.status.isActive && task.postponeCount >= POSTPONEMENT_ADVICE_THRESHOLD) {
                item {
                    PostponementGuidance(
                        postponeCount = task.postponeCount,
                        actionsLocked = actionsLocked,
                        onReviewAdjustmentDraft = onReviewAdjustmentDraft,
                        onEdit = onEdit,
                    )
                }
            }
            item {
                DetailActions(
                    task = task,
                    actionsLocked = actionsLocked,
                    onComplete = onComplete,
                    onFeedback = onFeedback,
                )
            }
            if (planningAgentState !is PlanningAgentState.Idle) {
                item {
                    PlanningAgentOutcomeCard(
                        state = planningAgentState,
                        onReviewDraft = onReviewAgentDraft,
                    )
                }
            }
        }
    }
    if (showTimeEditor) {
        TimelineTimeEditorDialog(
            title = if (placement == null) "给这件事定时间" else "调整这件事的时间",
            initialStartMinute = editorStart,
            initialEndMinute = (editorStart + editorDuration).coerceAtMost(1_440),
            tagKey = task.id,
            onDismiss = { showTimeEditor = false },
            busy = timeWriteBusy,
            saveError = timeWriteError,
            initialDate = placement?.date ?: defaultDate,
            onSave = { startMinute, endMinute, date, saved ->
                if (!onSaveTime(startMinute, endMinute, date)) false
                else {
                    showTimeEditor = false
                    saved()
                    true
                }
            },
        )
    }
}

@Composable
private fun DetailSection(title: String, eyebrow: String? = null, content: @Composable () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
    Column(modifier = Modifier.animateContentSize(
        tween(if (reducedMotion) 0 else ReplandMotion.Expand, easing = FastOutSlowInEasing),
    ).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        eyebrow?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
    }
}

@Composable
private fun DetailMetaPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun PostponementGuidance(
    postponeCount: Int,
    actionsLocked: Boolean,
    onReviewAdjustmentDraft: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.postponement_guidance_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.postponement_guidance_message, postponeCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(onClick = onReviewAdjustmentDraft, enabled = !actionsLocked,
                modifier = Modifier.fillMaxWidth().testTag("postpone-review-plan")) {
                Text(stringResource(R.string.review_adjustment_draft))
            }
            OutlinedButton(onClick = onEdit, enabled = !actionsLocked, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.adjust_task_scope_or_date))
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(16.dp))
        Text(value, modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExecutionLogCard(log: TaskExecutionLog, onCorrect: () -> Unit, actionsLocked: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(log.eventType.labelRes()),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    formatExecutionLogTime(log.createdAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.confirmed_status_format, stringResource(log.confirmedStatus.labelRes())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            log.feedback.completedContent?.let {
                DetailLine(stringResource(R.string.completed_content), it)
            }
            log.feedback.completionResult?.let {
                DetailLine(stringResource(R.string.completion_result), completionResultText(it))
            }
            log.feedback.actualDurationMinutes?.let {
                DetailLine(stringResource(R.string.actual_duration_optional), stringResource(R.string.duration_format, it))
            }
            log.feedback.progressPercent?.let {
                DetailLine(stringResource(R.string.progress_optional), stringResource(R.string.progress_format, it))
            }
            log.feedback.postponeReason?.let {
                DetailLine(stringResource(R.string.postpone_reason), it)
            }
            log.correctedLogId?.let {
                Text(
                    stringResource(R.string.corrects_log_format, it.take(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            log.replacementTaskId?.let {
                Text(
                    stringResource(R.string.replacement_task_format, it.take(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onCorrect, enabled = !actionsLocked,
                modifier = Modifier.testTag("execution-log-correct-${log.id}")) { Text(stringResource(R.string.correct_log)) }
        }
    }
}

@Composable
private fun DetailActions(
    task: Task,
    actionsLocked: Boolean,
    onComplete: () -> Unit,
    onFeedback: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (task.status.isActive) {
            Button(
                onClick = onComplete,
                enabled = !actionsLocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("complete-task"),
            ) {
                Text(stringResource(R.string.complete))
            }
        } else if (task.status != TaskStatus.REPLACED) {
            OutlinedButton(
                onClick = onFeedback,
                enabled = !actionsLocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("record-feedback"),
            ) {
                Text(stringResource(R.string.record_feedback))
            }
        }
    }
}

@Composable
private fun PlanningAgentOutcomeCard(
    state: PlanningAgentState,
    onReviewDraft: (PlanDraft) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("planning-agent-outcome"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.ai_advice_result_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            when (state) {
                PlanningAgentState.Idle -> Unit
                is PlanningAgentState.PreparingContext,
                is PlanningAgentState.RequestingAdvice,
                is PlanningAgentState.ValidatingAdvice -> Text(stringResource(R.string.ai_requesting))

                is PlanningAgentState.AwaitingConsent,
                is PlanningAgentState.PreviewingRequest -> Text(stringResource(R.string.ai_request_preview_notice))

                is PlanningAgentState.ShowingAdvice -> {
                    EditableAiAdvice(response = state.response)
                }

                is PlanningAgentState.ShowingDraft -> AgentDraftCardContent(
                    explanation = state.explanation,
                    draft = state.draft,
                    onReviewDraft = onReviewDraft,
                )

                is PlanningAgentState.FailedFallback -> {
                    Text(
                        stringResource(state.reason.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    when (val fallback = state.fallback) {
                        is LocalPlanningFallback.Advice -> Text(
                            fallback.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )

                        is LocalPlanningFallback.Draft -> AgentDraftCardContent(
                            explanation = fallback.explanation,
                            draft = fallback.draft,
                            onReviewDraft = onReviewDraft,
                        )
                    }
                }

                is PlanningAgentState.UserAccepted -> Text(stringResource(R.string.agent_draft_accepted))
                is PlanningAgentState.UserDismissed -> Text(stringResource(R.string.agent_draft_dismissed))
            }
        }
    }
}

@Composable
private fun EditableAiAdvice(response: AiAdvisorResponse) {
    val initialAdvice = when (response) {
        is AiTaskUnderstandingAdvice -> response.summary
        is AiDifficultyAndDurationAdvice -> response.explanation
        is AiTaskBreakdownAdvice -> response.steps.joinToString(separator = "\n") + "\n" + response.explanation
        is AiSortingExplanationAdvice -> response.explanation
        is AiDailySummaryAdvice -> listOfNotNull(response.summary, response.suggestedNextStep)
            .joinToString(separator = "\n")
        else -> ""
    }
    var editedAdvice by remember(response) { mutableStateOf(initialAdvice) }
    OutlinedTextField(
        value = editedAdvice,
        onValueChange = { editedAdvice = it.take(2_000) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-advice-editor"),
        label = { Text(stringResource(R.string.editable_agent_advice)) },
        minLines = 3,
    )
    Text(
        stringResource(R.string.ai_advice_needs_review),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

@Composable
private fun AgentDraftCardContent(
    explanation: String,
    draft: PlanDraft,
    onReviewDraft: (PlanDraft) -> Unit,
) {
    Text(explanation, style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.agent_draft_review_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    CapsuleRow(
        capsules = listOf(
            CapsuleSpec("review", stringResource(R.string.review_agent_draft), CapsuleKind.VARIABLE, tag = "review-agent-draft"),
        ),
        onCapsuleClick = { onReviewDraft(draft) },
    )
}

@Composable
private fun AiConsentDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_consent_title)) },
        text = {
            Text(stringResource(R.string.ai_consent_message), modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("ai-consent-confirm"),
            ) { Text(stringResource(R.string.ai_consent_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AiRequestPreviewDialog(
    request: AiAdvisorRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tasks = when (request) {
        is AiTaskUnderstandingRequest -> listOf(request.task)
        is AiDifficultyAndDurationRequest -> listOf(request.task)
        is AiTaskBreakdownRequest -> listOf(request.task)
        is AiSortingExplanationRequest -> listOf(request.task)
        is AiReplanRequest -> request.affectedTasks
        is AiDailySummaryRequest -> emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_request_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.ai_request_preview_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (tasks.isEmpty()) {
                    val feedbackCount = (request as AiDailySummaryRequest).confirmedFeedback.size
                    Text(
                        stringResource(R.string.ai_request_preview_daily_feedback, feedbackCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    if (tasks.size > 1) {
                        Text(
                            stringResource(R.string.ai_request_preview_task_count, tasks.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    tasks.forEach { task ->
                        DetailLine(stringResource(R.string.task_name), task.title)
                        DetailLine(
                            stringResource(R.string.task_description),
                            task.description.ifBlank { stringResource(R.string.no_introduction) },
                        )
                        DetailLine(stringResource(R.string.category), stringResource(task.category.labelRes()))
                        DetailLine(stringResource(R.string.priority), stringResource(task.initialPriority.labelRes()))
                        DetailLine(stringResource(R.string.current_status), stringResource(task.currentStatus.labelRes()))
                        Text(
                            stringResource(R.string.ai_request_preview_feedback_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (task.recentFeedback.isEmpty()) {
                            Text(stringResource(R.string.ai_request_preview_no_feedback), style = MaterialTheme.typography.bodySmall)
                        } else {
                            task.recentFeedback.forEachIndexed { index, feedback ->
                                Text(
                                    text = stringResource(
                                        R.string.ai_request_preview_feedback_format,
                                        index + 1,
                                        feedback.actualDurationMinutes?.let { "$it 分钟" } ?: "—",
                                        feedback.progressPercent?.let { "$it%" } ?: "—",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.ai_request_preview_segments_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (task.confirmedSegments.isEmpty()) {
                            Text(stringResource(R.string.ai_request_preview_no_segments), style = MaterialTheme.typography.bodySmall)
                        } else {
                            task.confirmedSegments.forEach { segment ->
                                Text(
                                    "${segment.date} · ${TimeBlockValidator.formatTime(segment.startMinute)}–" +
                                        TimeBlockValidator.formatTime(segment.endMinute),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.ai_request_preview_excluded_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("ai-request-confirm"),
            ) { Text(stringResource(R.string.ai_request_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun TimeScreen(
    snackbarBottomInset: androidx.compose.ui.unit.Dp,
    state: com.swan1127.repland.ui.time.TimeUiState,
    busy: Boolean,
    onAddWeekly: () -> Unit,
    onEditWeekly: (WeeklyTimeBlock) -> Unit,
    onDeleteWeekly: (WeeklyTimeBlock) -> Unit,
    onAddOverride: () -> Unit,
    onEditOverride: (DateOverride) -> Unit,
    onDeleteOverride: (DateOverride) -> Unit,
    onEditSemester: () -> Unit,
    timetableImport: TimetableImportState,
    onImportPdf: (Uri) -> Unit,
    onClearTimetableImport: () -> Unit,
) {
    val timetablePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(onImportPdf) },
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = snackbarBottomInset).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("导入固定课程", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("课表 PDF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.import_timetable_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(
                    onClick = { timetablePicker.launch(arrayOf("application/pdf")) },
                    enabled = !busy && timetableImport != TimetableImportState.Reading &&
                        (timetableImport as? TimetableImportState.Review)?.saving != true,
                    modifier = Modifier.fillMaxWidth().testTag("import-timetable-pdf"),
                ) { Text(stringResource(R.string.import_timetable_pdf)) }
            }
        }
        when (timetableImport) {
            TimetableImportState.Idle,
            is TimetableImportState.Review -> Unit

            TimetableImportState.Reading -> {
                Text("正在识别课表，尚未写入课程…", color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onClearTimetableImport) { Text("取消识别") }
            }

            is TimetableImportState.Completed -> {
                Card(modifier = Modifier.fillMaxWidth().testTag("timetable-import-receipt")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已新增 ${timetableImport.addedCount} 门课程 · 跳过 ${timetableImport.skippedCount} 项重复",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text("固定课程已保存")
                        TextButton(onClick = onClearTimetableImport) { Text("知道了") }
                    }
                }
            }

            is TimetableImportState.Failed -> {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            timetableImport.message,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onClearTimetableImport) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
        Text("规划时间约束", style = MaterialTheme.typography.titleLarge)
        if (!state.hasLoaded) {
            Text(if (state.readError != null) "时间设置暂不可用，请重试读取；未删除现有记录。" else "正在读取时间设置…")
        } else {
            SemesterWeekCard(state.semesterFirstWeekMonday, onEditSemester, enabled = !busy)
            TimeListHeading("每周常用时间", "添加", onAddWeekly, enabled = !busy, tag = "time-add-weekly")
            if (state.weeklyBlocks.isEmpty()) Text("还没有每周时间")
            state.weeklyBlocks.forEach { block ->
                key(block.id) { WeeklyBlockCard(block, { onEditWeekly(block) }, { onDeleteWeekly(block) }, enabled = !busy) }
            }
            TimeListHeading("单日例外", "添加", onAddOverride, enabled = !busy, tag = "time-add-override")
            if (state.dateOverrides.isEmpty()) Text("还没有单日例外")
            state.dateOverrides.forEach { item ->
                key(item.id) { DateOverrideCard(item, { onEditOverride(item) }, { onDeleteOverride(item) }, enabled = !busy) }
            }
        }
    }
}

@Composable
private fun SemesterWeekCard(
    semesterFirstWeekMonday: LocalDate?,
    onEdit: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.semester_week_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = if (semesterFirstWeekMonday == null) {
                        stringResource(R.string.semester_week_unset)
                    } else {
                        stringResource(
                            R.string.semester_week_set,
                            semesterFirstWeekMonday.toString(),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("time-edit-semester")) {
                Text(
                    stringResource(
                        if (semesterFirstWeekMonday == null) R.string.set_semester_week
                        else R.string.edit_semester_week,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SemesterStartEditorDialog(
    semesterFirstWeekMonday: LocalDate?,
    onDismiss: () -> Unit,
    onSave: (LocalDate) -> Unit,
    busy: Boolean = false,
    error: String? = null,
    canSave: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
) {
    var dateEpochDay by rememberSaveable { mutableStateOf(semesterFirstWeekMonday?.toEpochDay()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    EditorSheet(
        saving = busy,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.semester_week_editor_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                readNotice?.invoke()
                Text(stringResource(R.string.semester_week_date), style = MaterialTheme.typography.labelLarge)
                DesignClickableSurface(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("semester-start-date"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    enabled = !busy,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dateEpochDay?.let { taskDateLabel(LocalDate.ofEpochDay(it)) }
                                ?: stringResource(R.string.semester_week_editor_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.override_date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("time-mutation-error").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { dateEpochDay?.let { onSave(LocalDate.ofEpochDay(it)) } },
                enabled = !busy && canSave && dateEpochDay != null,
                modifier = Modifier.testTag("semester-start-save"),
            ) { Text(if (busy) "保存中…" else stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (showDatePicker) {
        TaskDatePickerDialog(
            initialDate = dateEpochDay?.let { LocalDate.ofEpochDay(it) },
            onDismiss = { showDatePicker = false },
            onDateSelected = { dateEpochDay = it.toEpochDay(); showDatePicker = false },
            mondaysOnly = true,
        )
    }
}

@Composable
private fun TimeListHeading(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    tag: String = "time-add",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(actionLabel) }
    }
}

@Composable
private fun WeeklyBlockCard(
    block: WeeklyTimeBlock,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("weekly-block-${block.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(block.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(block.dayOfWeek.labelRes()) + " · " +
                        TimeBlockValidator.formatTime(block.startMinute) + "–" +
                        TimeBlockValidator.formatTime(block.endMinute),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(block.kind.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                block.weekPattern?.let { pattern ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pattern,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("weekly-edit-${block.id}")) { Text("编辑") }
            TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("weekly-delete-${block.id}")) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private data class PeriodTimeDraft(val startText: String, val endText: String)

private fun periodTimeDraft(period: ClassPeriod) = PeriodTimeDraft(
    startText = TimeBlockValidator.formatTime(period.startMinute),
    endText = TimeBlockValidator.formatTime(period.endMinute),
)

@Composable
private fun TimetableImportReviewDialog(
    review: TimetableImportState.Review,
    clock: ClassPeriodClock,
    existingCourseBlocks: List<WeeklyTimeBlock> = emptyList(),
    canConfirm: Boolean = true,
    clockBusy: Boolean = false,
    readNotice: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onSelected: (String, Boolean) -> Unit,
    onEditCourse: (ImportedCourse) -> Unit,
    onClockAcknowledged: (Boolean) -> Unit,
    onSaveClock: (ClassPeriodClock, Boolean) -> Unit,
) {
    var editingCourseId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingClock by rememberSaveable { mutableStateOf(false) }
    var retimeCourses by rememberSaveable { mutableStateOf(true) }
    var clockDrafts by remember(clock.periods, editingClock) {
        mutableStateOf(clock.periods.map(::periodTimeDraft))
    }
    var clockInvalid by remember(clock.periods, editingClock) { mutableStateOf(false) }
    val selectedCourses = review.courses.filter { it.id in review.selectedIds }
    val overflowCount = review.courses.count { clock.period(it.endPeriod) == null }
    val retimableCourses = existingCourseBlocks.count { clock.periodSpan(it.startMinute, it.endMinute) != null }
    val minPeriods = (review.courses.maxOfOrNull(ImportedCourse::endPeriod) ?: 1)
        .coerceIn(1, ClassPeriodClock.MAX_PERIODS)
    val enabled = !review.saving && !clockBusy

    fun editedClock(): ClassPeriodClock? {
        val periods = clockDrafts.mapIndexed { index, draft ->
            val start = TimeBlockValidator.parseTime(draft.startText) ?: return null
            val end = TimeBlockValidator.parseEndTime(draft.endText) ?: return null
            if (start >= end) return null
            ClassPeriod(index + 1, start, end)
        }
        return ClassPeriodClock(periods).takeIf { ClassPeriodClock.isValid(it) }
    }

    AlertDialog(
        modifier = Modifier.testTag("timetable-import-review"),
        onDismissRequest = { if (enabled) { if (editingClock) editingClock = false else onDismiss() } },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = enabled, dismissOnClickOutside = enabled,
        ),
        title = {
            Text(stringResource(if (editingClock) R.string.class_period_clock_title else R.string.timetable_import_review))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (editingClock) {
                    Text(
                        stringResource(R.string.class_period_clock_editor_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    clockDrafts.forEachIndexed { index, draft ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.class_period_label, index + 1),
                                modifier = Modifier.width(52.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            OutlinedTextField(
                                value = draft.startText,
                                onValueChange = { value ->
                                    clockDrafts = clockDrafts.toMutableList().also {
                                        it[index] = it[index].copy(startText = value.take(5))
                                    }
                                    clockInvalid = false
                                },
                                modifier = Modifier.weight(1f).testTag("class-period-start-${index + 1}"),
                                label = { Text(stringResource(R.string.class_period_start_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Text("–")
                            OutlinedTextField(
                                value = draft.endText,
                                onValueChange = { value ->
                                    clockDrafts = clockDrafts.toMutableList().also {
                                        it[index] = it[index].copy(endText = value.take(5))
                                    }
                                    clockInvalid = false
                                },
                                modifier = Modifier.weight(1f).testTag("class-period-end-${index + 1}"),
                                label = { Text(stringResource(R.string.class_period_end_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (clockDrafts.size < ClassPeriodClock.MAX_PERIODS) {
                            TextButton(
                                onClick = {
                                    val lastEnd = TimeBlockValidator.parseEndTime(clockDrafts.last().endText)
                                    val start = lastEnd?.plus(10)?.coerceAtMost(24 * 60 - 45) ?: (8 * 60)
                                    clockDrafts = clockDrafts + PeriodTimeDraft(
                                        startText = TimeBlockValidator.formatTime(start),
                                        endText = TimeBlockValidator.formatTime(start + 45),
                                    )
                                },
                                enabled = enabled,
                                modifier = Modifier.testTag("class-period-add"),
                            ) { Text(stringResource(R.string.class_period_add)) }
                        }
                        if (clockDrafts.size > minPeriods) {
                            TextButton(
                                onClick = { clockDrafts = clockDrafts.dropLast(1) },
                                enabled = enabled,
                                modifier = Modifier.testTag("class-period-remove"),
                            ) { Text(stringResource(R.string.class_period_remove)) }
                        }
                    }
                    if (retimableCourses > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = retimeCourses,
                                onCheckedChange = { retimeCourses = it },
                                enabled = enabled,
                                modifier = Modifier.testTag("class-period-retime-courses"),
                            )
                            Text(
                                stringResource(R.string.class_period_retime_courses, retimableCourses),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (clockInvalid) {
                        Text(
                            stringResource(R.string.class_period_invalid),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("class-period-error").semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                } else {
                    readNotice?.invoke()
                    Text(
                        stringResource(R.string.import_default_period_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ClassPeriodClockCard(clock = clock, enabled = enabled, onEdit = { editingClock = true })
                    review.courses.forEach { course ->
                        val draft = clock.toWeeklyTimeBlockDraft(course)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Checkbox(
                                checked = course.id in review.selectedIds,
                                onCheckedChange = { onSelected(course.id, it) },
                                enabled = enabled,
                                modifier = Modifier.testTag("import-course-select-${course.id}").semantics {
                                    contentDescription = "选择课程：${course.title}"
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(course.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(
                                        R.string.course_period_format,
                                        stringResource(course.dayOfWeek.labelRes()),
                                        course.startPeriod,
                                        course.endPeriod,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                draft?.let {
                                    Text(
                                        TimeBlockValidator.formatTime(it.startMinute) + "–" +
                                            TimeBlockValidator.formatTime(it.endMinute),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                course.weekPattern?.let { pattern ->
                                    Text(
                                        pattern,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { editingCourseId = course.id }, enabled = enabled,
                                    modifier = Modifier.testTag("import-course-edit-${course.id}")) {
                                    Text(stringResource(R.string.edit_imported_course))
                                }
                            }
                        }
                    }
                    if (overflowCount > 0) {
                        Text(
                            stringResource(R.string.class_period_overflow, overflowCount),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = review.clockAcknowledged, onCheckedChange = onClockAcknowledged,
                            enabled = enabled, modifier = Modifier.testTag("import-clock-acknowledge").semantics {
                                contentDescription = "已核对所选课程的节次时间"
                            })
                        Text("已核对所选课程的节次时间", style = MaterialTheme.typography.bodyMedium)
                    }
                    review.error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("timetable-import-error").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        },
        confirmButton = {
            if (editingClock) {
                TextButton(
                    onClick = {
                        val edited = editedClock()
                        if (edited == null) {
                            clockInvalid = true
                        } else {
                            clockInvalid = false
                            onSaveClock(edited, retimeCourses && retimableCourses > 0)
                            editingClock = false
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("class-period-save"),
                ) { Text(stringResource(R.string.class_period_save)) }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = canConfirm && !review.saving && selectedCourses.isNotEmpty() && review.clockAcknowledged,
                    modifier = Modifier.testTag("timetable-import-confirm"),
                ) {
                    Text(if (review.saving) "正在保存…" else stringResource(R.string.import_selected_format, selectedCourses.size))
                }
            }
        },
        dismissButton = {
            if (editingClock) {
                TextButton(onClick = { editingClock = false }, enabled = enabled,
                    modifier = Modifier.testTag("class-period-back")) { Text(stringResource(R.string.class_period_back)) }
            } else {
                TextButton(onClick = onDismiss, enabled = enabled,
                    modifier = Modifier.testTag("timetable-import-cancel")) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    review.courses.firstOrNull { it.id == editingCourseId }?.let { course ->
        ImportedCourseEditorDialog(
            course = course,
            clock = clock,
            onDismiss = { editingCourseId = null },
            onSave = { updated ->
                onEditCourse(updated)
                editingCourseId = null
            },
        )
    }
}

@Composable
private fun ClassPeriodClockCard(
    clock: ClassPeriodClock,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.class_period_clock_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    clock.periods.joinToString(" · ") {
                        "${it.number} ${TimeBlockValidator.formatTime(it.startMinute)}–" +
                            TimeBlockValidator.formatTime(it.endMinute)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("import-edit-clock")) {
                Text(stringResource(R.string.class_period_clock_edit))
            }
        }
    }
}

@Composable
private fun ImportedCourseEditorDialog(
    course: ImportedCourse,
    clock: ClassPeriodClock,
    onDismiss: () -> Unit,
    onSave: (ImportedCourse) -> Unit,
) {
    var title by rememberSaveable(course.id) { mutableStateOf(course.title) }
    var dayOfWeek by rememberSaveable(course.id) { mutableStateOf(course.dayOfWeek) }
    var startPeriodText by rememberSaveable(course.id) { mutableStateOf(course.startPeriod.toString()) }
    var endPeriodText by rememberSaveable(course.id) { mutableStateOf(course.endPeriod.toString()) }
    var weekPattern by rememberSaveable(course.id) { mutableStateOf(course.weekPattern.orEmpty()) }
    var showValidationError by rememberSaveable { mutableStateOf(false) }
    EditorSheet(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_imported_course)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth().testTag("import-course-name"),
                    label = { Text(stringResource(R.string.time_block_name)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.day_of_week), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    values = DayOfWeek.entries.toList(),
                    selected = dayOfWeek,
                    label = { stringResource(it.labelRes()) },
                    onSelected = { dayOfWeek = it },
                )
                OutlinedTextField(
                    value = startPeriodText,
                    onValueChange = { startPeriodText = it },
                    modifier = Modifier.fillMaxWidth().testTag("import-course-start-period"),
                    label = { Text(stringResource(R.string.start_period)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endPeriodText,
                    onValueChange = { endPeriodText = it },
                    modifier = Modifier.fillMaxWidth().testTag("import-course-end-period"),
                    label = { Text(stringResource(R.string.end_period)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                val previewStart = com.swan1127.repland.domain.model.UserNumericInput.integerIn(startPeriodText, 1..12)
                val previewEnd = com.swan1127.repland.domain.model.UserNumericInput.integerIn(endPeriodText, 1..12)
                val previewClock = if (previewStart != null && previewEnd != null && previewEnd >= previewStart) {
                    clock.period(previewStart)?.startMinute?.let { start ->
                        clock.period(previewEnd)?.endMinute?.takeIf { it > start }?.let { end -> start to end }
                    }
                } else null
                Text(
                    previewClock?.let {
                        "按节次时间：${TimeBlockValidator.formatTime(it.first)}–${TimeBlockValidator.formatTime(it.second)}"
                    } ?: "节次超出当前节次表（共 ${clock.periods.size} 节），请先调整节次时间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (previewClock != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("import-course-time-preview"),
                )
                OutlinedTextField(
                    value = weekPattern,
                    onValueChange = { weekPattern = it },
                    modifier = Modifier.fillMaxWidth().testTag("import-course-week-pattern"),
                    label = { Text(stringResource(R.string.week_pattern_optional)) },
                    singleLine = true,
                )
                if (showValidationError) {
                    Text(stringResource(R.string.imported_course_error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val startPeriod = com.swan1127.repland.domain.model.UserNumericInput.integerIn(startPeriodText, 1..12)
                    val endPeriod = com.swan1127.repland.domain.model.UserNumericInput.integerIn(endPeriodText, 1..12)
                    if (title.isNotBlank() && startPeriod != null && endPeriod != null &&
                        endPeriod >= startPeriod && endPeriod <= clock.periods.size) {
                        onSave(
                            course.copy(
                                title = title.trim(),
                                dayOfWeek = dayOfWeek,
                                startPeriod = startPeriod,
                                endPeriod = endPeriod,
                                weekPattern = weekPattern.trim().takeIf(String::isNotBlank),
                            ),
                        )
                    } else {
                        showValidationError = true
                    }
                },
                modifier = Modifier.testTag("import-course-save"),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun PlanDraftDialog(
    draft: PlanDraft,
    tasks: List<Task>,
    weeklyBlocks: List<WeeklyTimeBlock>,
    dateOverrides: List<DateOverride>,
    semesterFirstWeekMonday: LocalDate?,
    onDismiss: () -> Unit,
    onUpdateDraft: (PlanDraft) -> Unit,
    onAccept: () -> Unit,
    tracks: List<RhythmTrack> = emptyList(),
    onCompleteTaskDetails: (Task) -> Unit = {},
    onConfigureAvailability: () -> Unit = {},
    currentPlan: ConfirmedPlan? = null,
    currentTaskOrder: List<String> = emptyList(),
    isSaving: Boolean = false,
    canAccept: Boolean = true,
    canEdit: Boolean = true,
    retainOnDismiss: Boolean = false,
    readNotice: (@Composable () -> Unit)? = null,
    onCommitSegmentDraft: ((PlanDraft, PlanDraft, String) -> Unit)? = null,
    segmentSaveReceipt: String? = null,
    segmentSaveError: String? = null,
    onClearSegmentSaveFeedback: () -> Unit = {},
    planningTeamContent: @Composable () -> Unit = {},
) {
    val tasksById = tasks.associateBy(Task::id)
    val unknownTaskLabel = stringResource(R.string.unknown_task)
    val orderedTaskIds = draft.orderedTaskIds
        .distinct()
        .filter(tasksById::containsKey)
        .ifEmpty { (draft.segments.map(PlannedSegment::taskId) + draft.pendingTaskIds).distinct() }
    val priorityAssessmentsByTask = draft.priorityAssessments.associateBy(LocalPriorityAssessment::taskId)
    var editingSegmentId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSegmentRequest by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(segmentSaveReceipt) {
        if (pendingSegmentRequest != null && pendingSegmentRequest == segmentSaveReceipt) {
            editingSegmentId = null
            pendingSegmentRequest = null
        }
    }
    var showTaskOrder by rememberSaveable { mutableStateOf(draft.orderOnly) }
    var showChanges by rememberSaveable { mutableStateOf(false) }
    val changes = PlanDraftReview.changes(draft, currentPlan, currentTaskOrder)
    val now = java.time.LocalDateTime.now()
    val protectedCurrent = currentPlan?.segments.orEmpty().filter {
        it.isLocked || tasksById[it.taskId]?.userPriority == TaskPriority.REQUIRED || it.date < now.toLocalDate() || (it.date == now.toLocalDate() && it.startMinute <= now.hour * 60 + now.minute)
    }
    val protectedIds = (protectedCurrent.map { it.id } + draft.segments.filter { candidate -> protectedCurrent.any { it.taskId == candidate.taskId } }.map { it.id }).toSet()
    EditorSheet(
        onDismissRequest = onDismiss,
        saving = isSaving,
        title = { Text(if (draft.orderOnly) "排序预览" else stringResource(R.string.plan_draft_title)) },
        text = {
            Column(
                modifier = Modifier
                    .height(500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                readNotice?.invoke()
                planningTeamContent()
                Text(
                    if (draft.orderOnly) "只调整任务列表顺序，不改变日程时段。" else stringResource(R.string.plan_draft_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("确认后：${changes.schedules.size} 项日程变化 · ${changes.order.size} 项顺序变化",
                    modifier = Modifier.testTag("draft-change-summary"), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { showChanges = !showChanges }, modifier = Modifier.testTag("draft-show-changes")) {
                    Text(if (showChanges) "收起变化" else "查看具体变化")
                }
                if (showChanges) {
                    changes.schedules.forEach { change ->
                        fun placementLabel(segments: List<PlannedSegment>) = if (segments.isEmpty()) "未安排" else segments.joinToString("；") {
                            "${it.date} ${TimeBlockValidator.formatTime(it.startMinute)}–${TimeBlockValidator.formatTime(it.endMinute)} · ${draftTrackLabel(it.trackId, tracks)}${if (it.isLocked) "（锁定）" else ""}"
                        }
                        Text(tasksById[change.taskId]?.displayName ?: unknownTaskLabel, style = MaterialTheme.typography.titleSmall)
                        Text("原：${placementLabel(change.before)}\n新：${placementLabel(change.after)}", style = MaterialTheme.typography.bodySmall)
                    }
                    changes.order.forEach { change ->
                        Text("${tasksById[change.taskId]?.displayName ?: unknownTaskLabel}：${change.before?.let { "第 $it 位" } ?: "未入排序"} → 第 ${change.after} 位", style = MaterialTheme.typography.bodySmall)
                    }
                    if (changes.schedules.isEmpty() && changes.order.isEmpty()) Text("与当前计划和顺序一致。")
                }
                draft.numericProfileVersion?.let { version ->
                    Text("画像依据 ${version.take(12)} · ${draft.numericParameters.joinToString()}", modifier = Modifier.testTag("draft-numeric-version"), style = MaterialTheme.typography.bodySmall)
                    draft.numericAdvice.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!draft.orderOnly && weeklyBlocks.none { it.kind == TimeBlockKind.AVAILABLE } && dateOverrides.none { it.type == DateOverrideType.AVAILABLE }) {
                    Text("还没有可用时间", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onConfigureAvailability, enabled = canEdit && !isSaving, modifier = Modifier.testTag("draft-configure-availability")) { Text("补充时间后重新生成") }
                }
                if (!draft.orderOnly) PlanDraftTimelinePreview(
                    draft = draft,
                    tasks = tasks,
                    weeklyBlocks = weeklyBlocks,
                    dateOverrides = dateOverrides,
                    semesterFirstWeekMonday = semesterFirstWeekMonday,
                    onEditSegment = { pendingSegmentRequest = null; onClearSegmentSaveFeedback(); editingSegmentId = it },
                    canEdit = canEdit && !isSaving,
                    tracks = tracks,
                )
                draft.pendingTaskIds.takeIf { !draft.orderOnly && it.isNotEmpty() }?.let { pendingIds ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${stringResource(R.string.plan_pending_tasks)} · ${pendingIds.size}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                pendingIds.joinToString("、") { taskId ->
                                    tasksById[taskId]?.displayName ?: unknownTaskLabel
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            pendingIds.mapNotNull(tasksById::get).filter { it.totalDurationMinutes == null }.forEach { task ->
                                TextButton(onClick = { onCompleteTaskDetails(task) }, enabled = canEdit && !isSaving, modifier = Modifier.testTag("draft-complete-details-${task.id}")) { Text("补充 ${task.displayName} 的时长后重新生成") }
                            }
                        }
                    }
                }
                draft.unscheduledTasks.takeIf(List<*>::isNotEmpty)?.let { unscheduledTasks ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "未排入 / 暂不安排",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            unscheduledTasks.forEach { unscheduled ->
                                // This is unassigned workload, not a promise of one continuous free slot.
                                Text(
                                    if (unscheduled.reason == UnscheduledReason.USER_DEFERRED && unscheduled.remainingMinutes == 0) "${tasksById[unscheduled.taskId]?.displayName ?: unknownTaskLabel}：本次暂不安排，时长仍待补充。" else stringResource(
                                        R.string.plan_unassigned_task,
                                        tasksById[unscheduled.taskId]?.displayName
                                            ?: stringResource(R.string.unknown_task),
                                        unscheduled.remainingMinutes,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(when (unscheduled.reason) {
                                    UnscheduledReason.USER_DEFERRED -> "你选择本次暂不安排；任务不会被删除或标记完成。"
                                    UnscheduledReason.CAPACITY_BEFORE_DUE_DATE -> "截止日期前的可用时间不足。"
                                    else -> "本次规划窗口内的可用时间不足。"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                if (unscheduled.reason != UnscheduledReason.USER_DEFERRED) {
                                    tasksById[unscheduled.taskId]?.let { task ->
                                        TextButton(onClick = { onCompleteTaskDetails(task) }, enabled = canEdit && !isSaving) { Text("调整 ${task.displayName} 的时长或日期后重新生成") }
                                    }
                                }
                            }
                            val capacityMissing = unscheduledTasks.filter { it.reason != UnscheduledReason.USER_DEFERRED }.sumOf { it.remainingMinutes }
                            if (capacityMissing > 0) Text("未排入 $capacityMissing 分钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = onConfigureAvailability, enabled = canEdit && !isSaving, modifier = Modifier.testTag("draft-capacity-add-time")) { Text("补充可用时间后重新生成") }
                        }
                    }
                }
                if (orderedTaskIds.isNotEmpty()) {
                    Surface(
                        onClick = { showTaskOrder = !showTaskOrder },
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("候选事项顺序", style = MaterialTheme.typography.titleSmall)
                                Text(if (draft.orderOnly) "确认后同步任务列表" else "只影响下次自动安排", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (showTaskOrder) "收起" else "调整", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (showTaskOrder) {
                        orderedTaskIds.forEachIndexed { index, taskId ->
                            tasksById[taskId]?.let { task ->
                                DraftTaskOrderRow(
                                    task = task,
                                    assessment = priorityAssessmentsByTask[taskId],
                                    index = index,
                                    total = orderedTaskIds.size,
                                    enabled = canEdit && !isSaving,
                                    onMove = { offset ->
                                        onUpdateDraft(PlanDraftEditor.moveTask(draft, taskId, offset))
                                    },
                                )
                                if (!draft.orderOnly) {
                                    val deferred = draft.unscheduledTasks.any { it.taskId == taskId && it.reason == UnscheduledReason.USER_DEFERRED }
                                    val protected = draft.segments.any { it.taskId == taskId && (it.isLocked || it.id in protectedIds) }
                                    TextButton(
                                        enabled = canEdit && !isSaving && !deferred && !protected,
                                        onClick = { onUpdateDraft(PlanDraftReview.defer(draft, taskId, protectedIds)) },
                                        modifier = Modifier.testTag("draft-defer-$taskId"),
                                    ) { Text(if (deferred) "本次已暂不安排" else if (protected) "包含锁定或已开始安排" else "本次暂不安排 ${task.displayName}") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("accept-plan-draft"),
                enabled = canAccept && !isSaving && (draft.segments.isNotEmpty() || draft.pendingTaskIds.isNotEmpty() || (draft.orderOnly && orderedTaskIds.isNotEmpty())),
            ) {
                Text(if (draft.orderOnly) "应用排序" else stringResource(R.string.accept_plan_draft))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text(if (retainOnDismiss) "保留草案并关闭" else stringResource(R.string.discard_plan_draft)) }
        },
    )
    editingSegmentId?.let { segmentId ->
        draft.segments.firstOrNull { it.id == segmentId }?.let { segment ->
            PlanSegmentEditorDialog(
                segment = segment,
                availableTrackIds = (tracks.map { it.id } + listOf("focus", "parallel-2", "parallel-3") + draft.segments.map(PlannedSegment::trackId)).distinct(),
                tracks = tracks,
                hasConflict = { candidate -> PlanDraftEditor.overlapsAnotherSegment(draft, candidate) },
                canSave = canEdit && !isSaving,
                isSaving = isSaving,
                saveError = segmentSaveError,
                readNotice = readNotice,
                onDismiss = { editingSegmentId = null; pendingSegmentRequest = null; onClearSegmentSaveFeedback() },
                onSave = { updated ->
                    val changed = PlanDraftEditor.moveSegment(draft, updated)
                    if (onCommitSegmentDraft != null) {
                        val requestId = java.util.UUID.randomUUID().toString()
                        pendingSegmentRequest = requestId
                        onCommitSegmentDraft(draft, changed, requestId)
                    } else {
                        onUpdateDraft(changed)
                        editingSegmentId = null
                    }
                },
            )
        }
    }
}

@Composable
private fun PlanDraftTimelinePreview(
    draft: PlanDraft,
    tasks: List<Task>,
    weeklyBlocks: List<WeeklyTimeBlock>,
    dateOverrides: List<DateOverride>,
    semesterFirstWeekMonday: LocalDate?,
    onEditSegment: (String) -> Unit,
    tracks: List<RhythmTrack> = emptyList(),
    canEdit: Boolean = true,
) {
    val dates = draft.segments.map(PlannedSegment::date).distinct().sorted()
    var selectedDate by remember(dates) { mutableStateOf(dates.firstOrNull()) }
    if (selectedDate !in dates) selectedDate = dates.firstOrNull()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("plan-draft-preview"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("安排预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("真实时间与轨道；点选蓝色事项可调整。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("尚未写入", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (dates.isEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "还没有可落位的时段",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dates.forEach { date ->
                        FilterChip(
                            selected = selectedDate == date,
                            onClick = { selectedDate = date },
                            label = { Text(date.format(DateTimeFormatter.ofPattern("M/d E", Locale.SIMPLIFIED_CHINESE))) },
                            modifier = Modifier.testTag("plan-draft-date-$date"),
                        )
                    }
                }
                val date = requireNotNull(selectedDate)
                val entries = ScheduleTimeline.entries(
                    date = date,
                    weeklyBlocks = weeklyBlocks,
                    dateOverrides = dateOverrides,
                    segments = draft.segments,
                    tasks = tasks,
                    semesterFirstWeekMonday = semesterFirstWeekMonday,
                )
                DraftDayTrackPreview(entries = entries, onEditSegment = onEditSegment, tracks = tracks, canEdit = canEdit)
                entries.filter { it.id.startsWith("segment:") }.sortedBy { it.startMinute }.forEach { entry ->
                    TextButton(
                        onClick = { onEditSegment(entry.id.removePrefix("segment:")) },
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("draft-segment-summary-${entry.id.removePrefix("segment:")}"),
                    ) {
                        Text("${entry.title} · ${TimeBlockValidator.formatTime(entry.startMinute)}–${TimeBlockValidator.formatTime(entry.endMinute)} · ${draftTrackLabel(entry.trackId, tracks)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftDayTrackPreview(
    entries: List<TimelineEntry>,
    onEditSegment: (String) -> Unit,
    tracks: List<RhythmTrack> = emptyList(),
    canEdit: Boolean = true,
) {
    val laneCount = (entries.maxOfOrNull(TimelineEntry::lane) ?: 0) + 1
    val laneTracks = (0 until laneCount).associateWith { lane ->
        entries.firstOrNull { it.lane == lane }?.trackId ?: "focus"
    }
    val rulerWidth = 38.dp
    val laneWidth = 112.dp
    val headerHeight = 28.dp
    val dayStart = minOf(7 * 60, (entries.minOfOrNull { it.startMinute } ?: 7 * 60) / 60 * 60).coerceAtLeast(0)
    val dayEnd = maxOf(23 * 60, ((entries.maxOfOrNull { it.endMinute } ?: 23 * 60) + 59) / 60 * 60).coerceAtMost(24 * 60)
    val hourHeight = 14.dp
    val contentHeight = headerHeight + hourHeight * ((dayEnd - dayStart) / 60) + 24.dp
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(contentHeight + 18.dp)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Box(Modifier.width(rulerWidth + laneWidth * laneCount).height(contentHeight)) {
                laneTracks.forEach { (lane, trackId) ->
                    Surface(
                        modifier = Modifier
                            .offset(x = rulerWidth + laneWidth * lane)
                            .width(laneWidth - 6.dp)
                            .height(22.dp),
                        color = if (lane == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            draftTrackLabel(trackId, tracks),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                (dayStart / 60..dayEnd / 60).forEach { hour ->
                    val y = headerHeight + hourHeight * (hour - dayStart / 60)
                    Text(
                        String.format(Locale.ROOT, "%02d", hour),
                        modifier = Modifier.offset(y = y - 6.dp).width(rulerWidth),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = rulerWidth, y = y)
                            .width(laneWidth * laneCount)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
                    )
                }
                entries.forEach { entry ->
                    val start = entry.startMinute.coerceIn(dayStart, dayEnd)
                    val end = entry.endMinute.coerceIn(dayStart, dayEnd)
                    if (end > start) {
                        val isProposed = entry.id.startsWith("segment:")
                        val y = headerHeight + hourHeight * ((start - dayStart) / 60f)
                        val blockHeight = (hourHeight * ((end - start) / 60f)).coerceAtLeast(24.dp)
                        val modifier = Modifier
                            .offset(x = rulerWidth + laneWidth * entry.lane, y = y)
                            .width(laneWidth - 6.dp)
                            .height(blockHeight)
                            .then(
                                if (isProposed) Modifier.testTag("draft-segment-${entry.id.removePrefix("segment:")}") else Modifier,
                            )
                        val content: @Composable () -> Unit = {
                            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(
                                    "${TimeBlockValidator.formatTime(entry.startMinute)}–${TimeBlockValidator.formatTime(entry.endMinute)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    color = if (isProposed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (isProposed) {
                            Surface(
                                onClick = { onEditSegment(entry.id.removePrefix("segment:")) },
                                enabled = canEdit,
                                modifier = modifier,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(9.dp),
                            ) { content() }
                        } else {
                            Surface(
                                modifier = modifier,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(9.dp),
                            ) { content() }
                        }
                    }
                }
            }
        }
    }
}

private fun draftTrackLabel(trackId: String, tracks: List<RhythmTrack> = emptyList()): String = tracks.firstOrNull { it.id == trackId }?.name ?: when (trackId) {
    "focus" -> "主线"
    "course" -> "课程"
    "fixed" -> "固定"
    "rest" -> "休息"
    else -> trackId.removePrefix("parallel-").toIntOrNull()?.let { "并行 $it" } ?: trackId
}

@Composable
private fun DraftTaskOrderRow(
    task: Task,
    assessment: LocalPriorityAssessment?,
    index: Int,
    total: Int,
    onMove: (Int) -> Unit,
    enabled: Boolean = true,
) {
    var accumulatedDragY by remember(task.id) { mutableFloatStateOf(0f) }
    var showReason by rememberSaveable(task.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(task.id, enabled) {
                if (!enabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { accumulatedDragY = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        accumulatedDragY += amount.y
                        if (abs(accumulatedDragY) >= 44f) {
                            onMove(if (accumulatedDragY > 0f) 1 else -1)
                            accumulatedDragY = 0f
                        }
                    },
                )
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.displayName, style = MaterialTheme.typography.bodyMedium)
                assessment?.let { value ->
                    TextButton(onClick = { showReason = !showReason }) {
                        Text(if (showReason) "收起排序依据" else "排序依据", style = MaterialTheme.typography.labelSmall)
                    }
                    if (showReason) {
                    Text(stringResource(R.string.dynamic_priority_score, value.score), style = MaterialTheme.typography.bodySmall)
                    value.reasons
                        .filter { it.kind != PriorityReasonKind.LOCAL_AI_NEUTRAL }
                        .take(3)
                        .forEach { reason ->
                            Text(
                                text = priorityReasonText(reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) {
                Text(stringResource(R.string.move_up))
            }
            TextButton(onClick = { onMove(1) }, enabled = enabled && index < total - 1) {
                Text(stringResource(R.string.move_down))
            }
        }
    }
}

@Composable
private fun PlanSegmentEditorDialog(
    segment: PlannedSegment,
    availableTrackIds: List<String>,
    hasConflict: (PlannedSegment) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (PlannedSegment) -> Unit,
    tracks: List<RhythmTrack> = emptyList(),
    canSave: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
    isSaving: Boolean = false,
    saveError: String? = null,
) {
    var dateEpochDay by rememberSaveable(segment.id) { mutableStateOf(segment.date.toEpochDay()) }
    var showDatePicker by rememberSaveable(segment.id) { mutableStateOf(false) }
    var selectedTrackId by rememberSaveable(segment.id) { mutableStateOf(segment.trackId) }
    var showValidationError by rememberSaveable(segment.id) { mutableStateOf(false) }
    var confirmConflict by rememberSaveable(segment.id) { mutableStateOf(false) }
    val startWheel = rememberTimeWheelHold(segment.startMinute, key = segment.id)
    val endWheel = rememberTimeWheelHold(segment.endMinute, allowEndOfDay = true, key = segment.id)
    AlertDialog(
        modifier = Modifier.testTag("plan-segment-editor"),
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.edit_plan_segment)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                readNotice?.invoke()
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("plan-segment-save-error").semantics { liveRegion = LiveRegionMode.Polite }) }
                Text(
                    stringResource(R.string.plan_segment_edit_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DesignClickableSurface(
                    onClick = { showDatePicker = true; confirmConflict = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plan-segment-date"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    enabled = !isSaving,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            taskDateLabel(LocalDate.ofEpochDay(dateEpochDay)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.override_date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                LabeledTimeWheel(
                    label = stringResource(R.string.start_time),
                    hold = startWheel,
                    tag = "plan-segment-start",
                    enabled = !isSaving,
                )
                LabeledTimeWheel(
                    label = stringResource(R.string.end_time),
                    hold = endWheel,
                    tag = "plan-segment-end",
                    enabled = !isSaving,
                )
                Text("轨道", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableTrackIds.forEach { trackId ->
                        FilterChip(
                            enabled = !isSaving,
                            selected = selectedTrackId == trackId,
                            onClick = {
                                selectedTrackId = trackId
                                confirmConflict = false
                            },
                            label = { Text(draftTrackLabel(trackId, tracks)) },
                        )
                    }
                }
                if (confirmConflict) {
                    Text(
                        stringResource(R.string.plan_segment_overlap_warning),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (showValidationError) {
                    Text(
                        stringResource(R.string.plan_segment_edit_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = segment.copy(
                        date = LocalDate.ofEpochDay(dateEpochDay),
                        startMinute = startWheel.settledMinuteOfDay(),
                        endMinute = endWheel.settledMinuteOfDay(),
                        trackId = selectedTrackId,
                    )
                    if (!PlanDraftEditor.isValidSegment(updated)) {
                        showValidationError = true
                    } else if (hasConflict(updated) && !confirmConflict) {
                        confirmConflict = true
                    } else {
                        onSave(updated)
                    }
                },
                enabled = canSave && !isSaving,
                modifier = Modifier.testTag("plan-segment-save"),
            ) {
                Text(if (isSaving) "保存中…" else stringResource(if (confirmConflict) R.string.confirm_anyway else R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.cancel)) } },
    )
    if (showDatePicker) {
        TaskDatePickerDialog(
            initialDate = LocalDate.ofEpochDay(dateEpochDay),
            onDismiss = { showDatePicker = false },
            onDateSelected = { dateEpochDay = it.toEpochDay(); confirmConflict = false },
        )
    }
}

@Composable
private fun PlanOverviewDialog(
    plans: List<ConfirmedPlan>,
    tasks: List<Task>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
    onClearCurrent: () -> Unit,
    onToggleSegmentLock: (segmentId: String, isLocked: Boolean) -> Unit,
) {
    val tasksById = tasks.associateBy(Task::id)
    var restoringId by rememberSaveable { mutableStateOf<String?>(null) }
    if (restoringId != null) {
        AlertDialog(onDismissRequest = { restoringId = null }, title = { Text("恢复这个计划版本？") },
            text = { Text("只恢复仍活动任务的安排与顺序；已结束任务、执行记录和过去时段不变。") },
            confirmButton = { TextButton(onClick = { restoringId?.let(onRestore); restoringId = null },
                enabled = !busy, modifier = Modifier.testTag("restore-history-confirm")) { Text("确认恢复") } },
            dismissButton = { TextButton(onClick = { restoringId = null }) { Text("取消") } })
    }
    EditorSheet(
        saving = busy,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_overview_title)) },
        text = {
            Column(
                modifier = Modifier
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()).testTag("history-scroll"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-error")) }
                if (plans.isEmpty()) {
                    Text(stringResource(R.string.no_plan_history))
                }
                plans.forEachIndexed { index, plan ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (plan.isCurrent) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (plan.isCurrent) R.string.current_plan_label
                                    else R.string.plan_history_version,
                                    index + 1,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.plan_segment_count, plan.segments.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            plan.segments.forEach { segment ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = (tasksById[segment.taskId]?.displayName
                                            ?: stringResource(R.string.unknown_task)) + " · " +
                                            segment.date + " " +
                                            TimeBlockValidator.formatTime(segment.startMinute) + "–" +
                                            TimeBlockValidator.formatTime(segment.endMinute),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (plan.isCurrent) {
                                        TextButton(
                                            enabled = !busy,
                                            modifier = Modifier.testTag("history-lock-${segment.id}"),
                                            onClick = {
                                                onToggleSegmentLock(segment.id, !segment.isLocked)
                                            },
                                        ) {
                                            Text(
                                                stringResource(
                                                    if (segment.isLocked) R.string.unlock_segment
                                                    else R.string.lock_segment,
                                                ),
                                            )
                                        }
                                    } else if (segment.isLocked) {
                                        Text(stringResource(R.string.segment_locked))
                                    }
                                }
                            }
                            if (!plan.isCurrent) {
                                TextButton(onClick = { restoringId = plan.id }, enabled = !busy,
                                    modifier = Modifier.testTag("history-restore-${plan.id}")) {
                                    Text(stringResource(R.string.restore_plan))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (plans.any { it.isCurrent }) {
                TextButton(onClick = onClearCurrent, enabled = !busy, modifier = Modifier.testTag("history-clear")) {
                    Text(
                        stringResource(R.string.clear_current_plan),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun DateOverrideCard(
    dateOverride: DateOverride,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("date-override-${dateOverride.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dateOverride.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(
                    text = dateOverride.date.toString() + " · " +
                        TimeBlockValidator.formatTime(dateOverride.startMinute) + "–" +
                        TimeBlockValidator.formatTime(dateOverride.endMinute),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(dateOverride.type.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("override-edit-${dateOverride.id}")) { Text("编辑") }
            TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("override-delete-${dateOverride.id}")) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MineScreen(
    onOpenTimeSettings: () -> Unit,
    onOpenPlanHistory: () -> Unit,
    weights: Map<TaskCategory, Int>,
    preferenceState: com.swan1127.repland.ui.preferences.CategoryPreferenceUiState,
    isLoading: Boolean,
    onSave: (Map<TaskCategory, Int>) -> Unit,
    remindersEnabled: Boolean,
    notificationsAllowed: Boolean,
    onRemindersEnabledChange: (Boolean) -> Unit,
    onExportLocalData: () -> Unit,
    onClearLocalData: () -> Unit,
    isDataWorking: Boolean,
    dataResult: DataManagementResult?,
    aiEnabled: Boolean,
    aiConsented: Boolean,
    onAiEnabledChange: (Boolean) -> Unit,
    aiProviderConfig: AiProviderConfig,
    remoteAiSupported: Boolean,
    aiProviderConfigError: String?,
    aiProviderConnectionTest: AiProviderConnectionTest,
    onSaveAiProviderConfig: (String, String, String) -> Unit,
    onClearAiProviderKey: () -> Unit,
    onTestAiProviderConnection: () -> Unit,
    engagementMode: EngagementMode,
    onEngagementModeChange: (EngagementMode) -> Unit,
    summaryContent: @Composable () -> Unit = {},
    personalProfileContent: @Composable () -> Unit = {},
    weatherContent: @Composable () -> Unit = {},
    capabilityShelfContent: @Composable () -> Unit = {},
    automaticPlanningEnabled: Boolean? = null,
    onAutomaticPlanningChange: (Boolean) -> Unit = {},
    showSettings: Boolean = false,
    onOpenSettings: () -> Unit = {},
    focusedFloorCount: Int = 0,
    memoryReferenceEnabled: Boolean = false,
    memoryProfile: com.swan1127.repland.domain.model.MemoryReference = com.swan1127.repland.domain.model.MemoryReference.empty(
        java.time.LocalDate.now(),
    ),
    teamMemoryFactCount: Int = 0,
    onMemoryReferenceChange: (Boolean) -> Unit = {},
    personaDoc: String = "",
    onEditPersona: () -> Unit = {},
) {
    if (isLoading && showSettings) return
    val profileScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    var showPreferences by rememberSaveable { mutableStateOf(false) }
    var showData by rememberSaveable { mutableStateOf(false) }
    var showCapabilities by rememberSaveable { mutableStateOf(false) }
    val weightsSaver = remember {
        mapSaver<Map<TaskCategory, String>>(
            save = { entries -> entries.mapKeys { it.key.name } },
            restore = { entries -> TaskCategory.knownEntries.associateWith { entries[it.name] as? String ?: "" } },
        )
    }
    var preferencesEdited by rememberSaveable { mutableStateOf(false) }
    var editableWeights by rememberSaveable(stateSaver = weightsSaver) {
        mutableStateOf(TaskCategory.knownEntries.associateWith { weights.getValue(it).toString() })
    }
    LaunchedEffect(weights) {
        if (!preferencesEdited) editableWeights = TaskCategory.knownEntries.associateWith { weights.getValue(it).toString() }
    }
    var showValidationError by rememberSaveable { mutableStateOf(false) }
    val parsedWeights = TaskCategory.knownEntries.associateWith { category ->
        editableWeights.getValue(category).toIntOrNull() ?: -1
    }
    val valid = parsedWeights.values.all { it in 0..100 } && parsedWeights.values.sum() > 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(if (showSettings) settingsScroll else profileScroll)
            .testTag(if (showSettings) "mine-settings-page" else "mine-profile-page")
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!showSettings) {
            personalProfileContent()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("focused-floor-card"),
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val floorColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.size(width = 34.dp, height = 40.dp)) {
                        val fw = size.width * 0.72f
                        val fh = size.height / 5f
                        val widths = listOf(0.85f, 1f, 0.92f, 1.05f, 0.8f)
                        widths.forEachIndexed { i, factor ->
                            val w2 = fw * factor
                            drawRoundRect(
                                floorColor.copy(alpha = 0.28f + i * 0.14f),
                                Offset(size.width / 2 - w2 / 2, size.height - (i + 1) * fh),
                                androidx.compose.ui.geometry.Size(w2, fh * 0.86f),
                                androidx.compose.ui.geometry.CornerRadius(fh * 0.2f),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("盖楼成就", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "已落成 $focusedFloorCount 层",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text("用户画像", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(
                onClick = onEditPersona,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().testTag("persona-doc-card"),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("关于我", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            if (personaDoc.isBlank()) "写一段" else "编辑",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        personaDoc.ifBlank { "写下目标、作息和偏好" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            summaryContent()
            if (!memoryProfile.isEmpty || teamMemoryFactCount > 0) {
                Text(
                    "${memoryProfile.logNote()} · 小组记忆 $teamMemoryFactCount 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("memory-stats-line"),
                )
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("mine-settings")) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("设置")
            }
        } else {
            Text("版本 ${com.swan1127.repland.BuildConfig.VERSION_NAME} (${com.swan1127.repland.BuildConfig.VERSION_CODE}) · ${com.swan1127.repland.BuildConfig.BUILD_SOURCE}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("app-version"))
            Text("日常使用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            automaticPlanningEnabled?.let { enabled ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("自动安排", style = MaterialTheme.typography.titleSmall)
                        }
                        Switch(checked = enabled, onCheckedChange = onAutomaticPlanningChange,
                            modifier = Modifier.testTag("autonomy-enabled-switch").semantics { contentDescription = "自动安排" })
                    }
                }
            }
            weatherContent()
            OutlinedButton(onClick = onOpenPlanHistory, modifier = Modifier.fillMaxWidth().testTag("mine-plan-history")) {
                Text("计划历史与锁定管理")
            }
            OutlinedButton(onClick = onOpenTimeSettings, modifier = Modifier.fillMaxWidth().testTag("mine-time-settings")) {
                Text("时间设置 · 课程、可用时间与学期")
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.local_reminders_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Switch(
                            checked = remindersEnabled,
                            onCheckedChange = onRemindersEnabledChange,
                            modifier = Modifier.testTag("local-reminders-switch"),
                        )
                    }
                    if (!notificationsAllowed) {
                        Text(
                            stringResource(R.string.notification_permission_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            SettingsDisclosure(
                "连接能力",
                showCapabilities,
                { showCapabilities = !showCapabilities },
                tag = "capability-shelf-disclosure",
            )
            MotionDisclosure(showCapabilities) { capabilityShelfContent() }
            Text("智能与个性化", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            LearningMemorySettings(
                enabled = memoryReferenceEnabled,
                onEnabledChange = onMemoryReferenceChange,
                summary = memoryProfile.logNote(),
                interactive = !isDataWorking,
            )
            AiSettingsSection(
                aiEnabled = aiEnabled,
                aiConsented = aiConsented,
                onAiEnabledChange = onAiEnabledChange,
                providerConfig = aiProviderConfig,
                providerConfigError = aiProviderConfigError,
                providerConnectionTest = aiProviderConnectionTest,
                onSaveProviderConfig = onSaveAiProviderConfig,
                onClearProviderKey = onClearAiProviderKey,
                onTestConnection = onTestAiProviderConnection,
                remoteSupported = remoteAiSupported,
            )
            SettingsDisclosure("类别偏好", showPreferences, { showPreferences = !showPreferences })
            MotionDisclosure(showPreferences) {
            if (!preferenceState.hasLoaded) Text("尚未读取权重",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.category_preferences_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TaskCategory.knownEntries.forEach { category ->
                OutlinedTextField(
                    value = editableWeights.getValue(category),
                    onValueChange = { value ->
                        editableWeights = editableWeights + (category to value)
                        preferencesEdited = true
                        showValidationError = false
                    },
                    enabled = !preferenceState.isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("category-weight-${category.name}"),
                    label = { Text(stringResource(category.labelRes())) },
                    suffix = { Text(stringResource(R.string.percent_suffix)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
            if (showValidationError) {
                Text(
                    stringResource(R.string.category_preferences_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    if (valid) onSave(parsedWeights) else showValidationError = true
                },
                enabled = preferenceState.isTrusted && !preferenceState.isSaving,
                modifier = Modifier.fillMaxWidth().testTag("category-preferences-save"),
            ) { Text(if (preferenceState.isSaving) "保存中…" else stringResource(R.string.save_category_preferences)) }
            preferenceState.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("category-preferences-error").semantics { liveRegion = LiveRegionMode.Polite }) }
            preferenceState.saveReceipt?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("category-preferences-receipt").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
            Text("数据与隐私", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SettingsDisclosure("本地数据", showData, { showData = !showData })
            MotionDisclosure(showData) {
            LocalDataManagementSection(
                isWorking = isDataWorking,
                result = dataResult,
                onExport = onExportLocalData,
                onClear = onClearLocalData,
            )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsDisclosure(title: String, expanded: Boolean, onClick: () -> Unit, tag: String? = null) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AiSettingsSection(
    aiEnabled: Boolean,
    aiConsented: Boolean,
    onAiEnabledChange: (Boolean) -> Unit,
    providerConfig: AiProviderConfig,
    providerConfigError: String?,
    providerConnectionTest: AiProviderConnectionTest,
    onSaveProviderConfig: (String, String, String) -> Unit,
    onClearProviderKey: () -> Unit,
    onTestConnection: () -> Unit,
    remoteSupported: Boolean,
) {
    var showProviderSettings by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!remoteSupported) Text("当前构建使用本地规划",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai-build-capability"))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ai_advisor_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Switch(
                    checked = aiEnabled,
                    onCheckedChange = onAiEnabledChange,
                    modifier = Modifier.testTag("ai-advisor-switch"),
                )
            }
            Text(
                stringResource(
                    if (aiConsented) R.string.ai_advisor_consent_recorded
                    else R.string.ai_advisor_consent_needed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                onClick = { showProviderSettings = true },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("ai-provider-settings"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = if (providerConfig.hasApiKey) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            DesignIcons.Assistant,
                            contentDescription = null,
                            modifier = Modifier.padding(9.dp),
                            tint = if (providerConfig.hasApiKey) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("模型服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (providerConfig.hasApiKey) "密钥已加密保存在本设备 · ${providerConfig.model}"
                            else "尚未添加密钥",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("配置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (providerConfigError != null) {
                Text(providerConfigError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showProviderSettings) {
        AiProviderSettingsDialog(
            config = providerConfig,
            errorMessage = providerConfigError,
            connectionTest = providerConnectionTest,
            onDismiss = { showProviderSettings = false },
            onSave = onSaveProviderConfig,
            onClearKey = onClearProviderKey,
            onTestConnection = onTestConnection,
        )
    }
}

@Composable
internal fun AiProviderSettingsDialog(
    config: AiProviderConfig,
    errorMessage: String?,
    connectionTest: AiProviderConnectionTest,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onClearKey: () -> Unit,
    onTestConnection: () -> Unit,
) {
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var apiKey by remember { mutableStateOf("") }
    // A successful repository save changes the revision. Failed saves retain the
    // entered key so the user can correct the fields and retry.
    LaunchedEffect(config.updatedAtEpochMillis) { apiKey = "" }
    val hasUnsavedChanges = baseUrl.trim().trimEnd('/') != config.baseUrl ||
        model.trim() != config.model || apiKey.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型服务配置") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("地址预设", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = baseUrl.contains("apihub.agnes-ai.com"),
                        onClick = { baseUrl = "https://apihub.agnes-ai.com/v1" },
                        label = { Text("国际服务") },
                    )
                    FilterChip(
                        selected = baseUrl.contains("api.agnes-ai.cn"),
                        onClick = { baseUrl = "https://api.agnes-ai.cn/v1" },
                        label = { Text("中国服务") },
                    )
                }
                FilterChip(
                    selected = baseUrl.trimEnd('/') == "https://api.deepseek.com",
                    onClick = { baseUrl = "https://api.deepseek.com"; model = "deepseek-flash"; apiKey = "" },
                    label = { Text("DeepSeek 官方") },
                    modifier = Modifier.testTag("ai-provider-deepseek"),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth().testTag("ai-provider-endpoint"),
                    label = { Text("服务地址") },
                    supportingText = { Text("HTTPS API 基础地址") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth().testTag("ai-provider-model"),
                    label = { Text("模型") },
                    supportingText = { Text("模型 ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth().testTag("ai-provider-key"),
                    label = { Text(if (config.hasApiKey) "替换 API 密钥（留空则保留）" else "API 密钥") },
                    placeholder = { Text(if (config.hasApiKey) "已安全保存" else "粘贴你的密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                if (errorMessage != null) Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (hasUnsavedChanges) {
                    Text("尚未保存", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else when (connectionTest) {
                    AiProviderConnectionTest.Idle -> Unit
                    AiProviderConnectionTest.Testing -> Text("正在验证连接…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    is AiProviderConnectionTest.Connected -> Text("连接成功 · ${connectionTest.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    is AiProviderConnectionTest.Failed -> Text(connectionTest.reason.connectionTestMessage(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (config.hasApiKey) {
                    TextButton(onClick = { onClearKey(); apiKey = "" }, modifier = Modifier.testTag("ai-provider-clear")) {
                        Text("清除本机密钥", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onSave(baseUrl, model, apiKey) }, modifier = Modifier.testTag("ai-provider-save")) { Text("加密保存") }
                TextButton(
                    onClick = onTestConnection,
                    enabled = config.hasApiKey && !hasUnsavedChanges && connectionTest !is AiProviderConnectionTest.Testing,
                    modifier = Modifier.testTag("ai-provider-test"),
                ) { Text("测试连接") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private fun AiAdvisorFailureReason.connectionTestMessage(): String = when (this) {
    AiAdvisorFailureReason.SOURCE_CHANGED -> "规划数据已变化，请重试。"
    AiAdvisorFailureReason.CONTEXT_UNAVAILABLE -> "暂时无法读取规划数据，请重试。"
    AiAdvisorFailureReason.CONFIGURATION_CHANGED -> "配置已变化，请使用新配置重新测试。"
    AiAdvisorFailureReason.AUTHENTICATION_FAILURE -> "鉴权失败：请检查密钥是否属于当前服务区，以及账户是否可用。"
    AiAdvisorFailureReason.RATE_LIMITED -> "服务已连通，但当前触发请求额度或频率限制。"
    AiAdvisorFailureReason.REMOTE_FAILURE -> "已到达服务网关，但模型服务暂时异常；请稍后再试。"
    AiAdvisorFailureReason.TIMEOUT -> "连接超时：请检查网络，或稍后重试。"
    AiAdvisorFailureReason.INVALID_RESPONSE -> "服务已返回内容，但格式未通过校验；请重试。"
    AiAdvisorFailureReason.TRANSPORT_FAILURE -> "无法到达服务：请确认服务区地址和网络。"
    AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED -> "尚未保存可用密钥。"
    AiAdvisorFailureReason.DISABLED -> "连接测试不依赖规划开关；请重新保存密钥后测试。"
}

@Composable
private fun ProfileEvidenceSection(
    evidence: List<ProfileEvidence>,
    onGenerate: () -> Unit,
    onUpdate: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    hasActionError: Boolean,
) {
    var editingEvidence by remember { mutableStateOf<ProfileEvidence?>(null) }
    var deletingEvidence by remember { mutableStateOf<ProfileEvidence?>(null) }
    Text(stringResource(R.string.profile_evidence_title), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.profile_evidence_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.generate_profile_evidence))
    }
    if (hasActionError) {
        Text(
            stringResource(R.string.profile_evidence_action_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (evidence.isEmpty()) {
        Text(
            stringResource(R.string.no_profile_evidence),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        evidence.forEach { item ->
            ProfileEvidenceCard(
                evidence = item,
                onEdit = { editingEvidence = item },
                onDelete = { deletingEvidence = item },
            )
        }
    }

    editingEvidence?.let { item ->
        ProfileEvidenceEditorDialog(
            evidence = item,
            onDismiss = { editingEvidence = null },
            onSave = { conclusion ->
                onUpdate(item.id, conclusion)
                editingEvidence = null
            },
        )
    }
    deletingEvidence?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingEvidence = null },
            title = { Text(stringResource(R.string.delete_profile_evidence_title)) },
            text = { Text(stringResource(R.string.delete_profile_evidence_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(item.id)
                        deletingEvidence = null
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingEvidence = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ProfileEvidenceCard(
    evidence: ProfileEvidence,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(evidence.scope.labelRes()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(evidence.conclusion, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (evidence.isUserEdited) {
                    stringResource(R.string.profile_evidence_user_edited)
                } else {
                    stringResource(R.string.profile_evidence_generated)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.profile_evidence_sources_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            if (evidence.sources.isEmpty()) {
                Text(
                    stringResource(R.string.profile_evidence_source_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                evidence.sources.forEach { source ->
                    Text(
                        text = stringResource(
                            R.string.profile_evidence_source_format,
                            source.taskDisplayName,
                            stringResource(source.eventType.labelRes()),
                            formatExecutionLogTime(source.createdAtEpochMillis),
                            source.executionLogId.take(8),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit_profile_evidence)) }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ProfileEvidenceEditorDialog(
    evidence: ProfileEvidence,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var conclusion by remember(evidence.id) { mutableStateOf(evidence.conclusion) }
    val valid = conclusion.trim().length in 1..500
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_profile_evidence)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.edit_profile_evidence_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = conclusion,
                    onValueChange = { conclusion = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.profile_evidence_conclusion)) },
                    minLines = 3,
                )
                if (!valid) {
                    Text(
                        stringResource(R.string.profile_evidence_validation_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onSave(conclusion) }, enabled = valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 「关于我」自述编辑器：第一人称、用户资产。留空保存即从提示词里移除，不编造任何进度。 */
@Composable
private fun PersonaDocEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    EditorSheet(
        onDismissRequest = onDismiss,
        title = { Text("关于我") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1000) },
                    modifier = Modifier.fillMaxWidth().testTag("persona-doc-input"),
                    placeholder = { Text("例如：我是大二学生，早上状态最好，想把高数放在上午；晚上容易累，别排太满。我在准备蓝桥杯，周末能多投入一点。") },
                    minLines = 6,
                    supportingText = { Text("${draft.length}/1000") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, modifier = Modifier.testTag("persona-doc-save")) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (draft.isNotBlank()) {
                    TextButton(onClick = { draft = "" }, modifier = Modifier.testTag("persona-doc-clear")) { Text("清空") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun LocalDataManagementSection(
    isWorking: Boolean,
    result: DataManagementResult?,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Text(stringResource(R.string.local_data_title), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.local_data_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onExport,
        enabled = !isWorking,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.export_local_json)) }
    OutlinedButton(
        onClick = onClear,
        enabled = !isWorking,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.clear_local_data), color = MaterialTheme.colorScheme.error) }
    result?.let { outcome ->
        val (message, isError) = when (outcome) {
            DataManagementResult.EXPORT_SUCCEEDED -> R.string.export_local_data_success to false
            DataManagementResult.EXPORT_FAILED -> R.string.export_local_data_failed to true
            DataManagementResult.CLEAR_SUCCEEDED -> R.string.clear_local_data_success to false
            DataManagementResult.CLEAR_FAILED -> R.string.clear_local_data_failed to true
        }
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    actionLabel: String?,
    onAction: () -> Unit,
    @DrawableRes artwork: Int? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            EmptyScene(
                artwork = artwork,
                title = title,
                action = actionLabel?.let { label ->
                    { CapsuleRow(listOf(CapsuleSpec("add", label, CapsuleKind.VERB)), onCapsuleClick = { onAction() }) }
                },
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                actionLabel?.let { label ->
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAction) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun TaskEditorDialog(
    task: Task?,
    initialText: String = "",
    @StringRes dialogTitle: Int? = null,
    @StringRes confirmLabel: Int = R.string.save,
    allowPriorityChange: Boolean = task == null || task.userPriority == TaskPriority.UNSPECIFIED,
    saving: Boolean = false,
    saveError: String? = null,
    canSave: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (TaskDraft) -> Unit,
) {
    var displayName by rememberSaveable(task?.id, initialText) {
        mutableStateOf(task?.displayName ?: TaskName.fromDescription(initialText))
    }
    var description by rememberSaveable(task?.id, initialText) {
        mutableStateOf(task?.description.takeIf { it != task?.displayName } ?: initialText)
    }
    var category by rememberSaveable(task?.id) { mutableStateOf(task?.category ?: TaskCategory.UNSPECIFIED) }
    var priority by rememberSaveable(task?.id) { mutableStateOf(task?.userPriority ?: TaskPriority.UNSPECIFIED) }
    var estimatedDaysText by rememberSaveable(task?.id) { mutableStateOf(task?.estimatedDays?.toString() ?: "") }
    var durationText by rememberSaveable(task?.id) { mutableStateOf(task?.totalDurationMinutes?.toString().orEmpty()) }
    var scheduledForDate by rememberSaveable(task?.id) { mutableStateOf(task?.scheduledForDate) }
    var dueDate by rememberSaveable(task?.id) { mutableStateOf(task?.dueDate) }
    var datePickerTarget by rememberSaveable { mutableStateOf<TaskEditorDateTarget?>(null) }
    var showValidationError by rememberSaveable { mutableStateOf(false) }
    val estimatedDays = estimatedDaysText.toIntOrNull()
    val durationMinutes = durationText.takeIf(String::isNotBlank)?.toIntOrNull()
    val estimatedDaysInvalid = estimatedDaysText.isNotEmpty() &&
        (estimatedDaysText.any { it !in '0'..'9' } || estimatedDays !in 1..30)
    val durationInvalid = durationText.isNotBlank() && (durationText.any { it !in '0'..'9' } || durationMinutes !in 1..1_440)
    val scheduleAfterDeadline = scheduledForDate?.let { scheduled ->
        dueDate?.let { deadline -> scheduled.isAfter(deadline) }
    } == true

    EditorSheet(
        onDismissRequest = onDismiss,
        saving = saving,
        title = {
            Text(stringResource(dialogTitle ?: if (task == null) R.string.add_task else R.string.edit_task))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                readNotice?.invoke()
                OutlinedTextField(
                    value = displayName,
                    enabled = !saving,
                    onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth().testTag("task-editor-name"),
                    label = { Text(stringResource(R.string.task_name)) },
                    placeholder = { Text(stringResource(R.string.task_name_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    enabled = !saving,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth().testTag("task-editor-description"),
                    label = { Text("备注 / 说明（可选）") },
                    placeholder = { Text("地点、准备物、下一步或这次安排的上下文") },
                    minLines = 2,
                )
                Text(stringResource(R.string.category), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    values = TaskCategory.entries.toList(),
                    selected = category,
                    label = { stringResource(it.labelRes()) },
                    onSelected = { if (!saving) category = it },
                    enabled = !saving,
                )
                Text(stringResource(R.string.priority), style = MaterialTheme.typography.labelLarge)
                if (allowPriorityChange) {
                    ChoiceRow(
                        values = TaskPriority.entries.toList(),
                        selected = priority,
                        label = { stringResource(it.labelRes()) },
                        onSelected = { if (!saving) priority = it },
                        enabled = !saving,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.initial_priority_fixed, stringResource(priority.labelRes())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = estimatedDaysText,
                    enabled = !saving,
                    onValueChange = { estimatedDaysText = it },
                    modifier = Modifier.fillMaxWidth().testTag("task-editor-days"),
                    label = { Text(stringResource(R.string.estimated_days)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = showValidationError && estimatedDaysInvalid,
                    supportingText = {
                        if (showValidationError && estimatedDaysInvalid) Text("请输入 1–30 天")
                    },
                )
                OutlinedTextField(
                    value = durationText,
                    enabled = !saving,
                    onValueChange = { durationText = it },
                    modifier = Modifier.fillMaxWidth().testTag("task-editor-duration"),
                    label = { Text(stringResource(R.string.duration_minutes_optional)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = showValidationError && durationInvalid,
                    supportingText = {
                        if (showValidationError && durationInvalid) Text("时长应为 1–1440 分钟；留空表示暂不估算")
                    },
                )
                TaskDateField(
                    label = "安排在哪一天？",
                    description = "只决定它在“今天 / 待安排”中的位置；不会被当作截止时间。",
                    selectedDate = scheduledForDate,
                    onSelectDate = { if (!saving) scheduledForDate = it },
                    onChooseDate = { if (!saving) datePickerTarget = TaskEditorDateTarget.SCHEDULED_FOR },
                    onClear = { if (!saving) scheduledForDate = null },
                    tag = "task-editor-scheduled-date",
                    enabled = !saving,
                )
                TaskDateField(
                    label = stringResource(R.string.due_date_optional),
                    description = "用于截止提醒、优先级和逾期判断；可自由选择任意日期。",
                    selectedDate = dueDate,
                    onSelectDate = { if (!saving) dueDate = it },
                    onChooseDate = { if (!saving) datePickerTarget = TaskEditorDateTarget.DEADLINE },
                    onClear = { if (!saving) dueDate = null },
                    tag = "task-editor-due-date",
                    enabled = !saving,
                )
                if (scheduleAfterDeadline) {
                    Text(
                        "计划晚于截止，保存后按逾期处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (showValidationError) {
                    Text(
                        stringResource(R.string.save_task_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("task-editor-error").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val draft = TaskDraft(
                        id = task?.id,
                        displayName = displayName,
                        description = description,
                        category = category,
                        userPriority = priority,
                        estimatedDays = estimatedDays,
                        totalDurationMinutes = durationMinutes,
                        dueDate = dueDate,
                        scheduledForDate = scheduledForDate,
                    )
                    if (!estimatedDaysInvalid && !durationInvalid && TaskDraftValidator.isValid(draft)) onSave(draft) else showValidationError = true
                },
                enabled = !saving && canSave,
                modifier = Modifier.testTag("task-editor-save"),
            ) { Text(if (saving) "保存中…" else stringResource(confirmLabel)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
        },
    )
    datePickerTarget?.let { target ->
        TaskDatePickerDialog(
            initialDate = when (target) {
                TaskEditorDateTarget.SCHEDULED_FOR -> scheduledForDate
                TaskEditorDateTarget.DEADLINE -> dueDate
            },
            onDismiss = { datePickerTarget = null },
            onDateSelected = { selected ->
                when (target) {
                    TaskEditorDateTarget.SCHEDULED_FOR -> scheduledForDate = selected
                    TaskEditorDateTarget.DEADLINE -> dueDate = selected
                }
                datePickerTarget = null
            },
        )
    }
}

private enum class TaskEditorDateTarget { SCHEDULED_FOR, DEADLINE }

@Composable
private fun TaskDateField(
    label: String,
    description: String,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    onChooseDate: () -> Unit,
    onClear: () -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val today = LocalDate.now()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                selectedDate?.let {
                    TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.testTag("$tag-clear")) { Text("清除") }
                }
            }
            Text(
                selectedDate?.let(::taskDateLabel) ?: "未设置",
                style = MaterialTheme.typography.bodyLarge,
                color = if (selectedDate == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedDate == today,
                    enabled = enabled,
                    onClick = { onSelectDate(today) },
                    label = { Text("今天") },
                )
                FilterChip(
                    selected = selectedDate == today.plusDays(1),
                    enabled = enabled,
                    onClick = { onSelectDate(today.plusDays(1)) },
                    label = { Text("明天") },
                )
                OutlinedButton(
                    onClick = onChooseDate,
                    enabled = enabled,
                    modifier = Modifier.testTag("$tag-picker"),
                ) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择日期")
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                enabled = enabled,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun WeeklyTimeBlockEditorDialog(
    block: WeeklyTimeBlock?,
    onDismiss: () -> Unit,
    onSave: (WeeklyTimeBlockDraft) -> Unit,
    initialKind: TimeBlockKind = TimeBlockKind.COURSE,
    busy: Boolean = false,
    error: String? = null,
    canSave: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
) {
    var title by rememberSaveable(block?.id) { mutableStateOf(block?.title.orEmpty()) }
    var kind by rememberSaveable(block?.id) { mutableStateOf(block?.kind ?: initialKind) }
    var dayOfWeek by rememberSaveable(block?.id) { mutableStateOf(block?.dayOfWeek ?: if (initialKind == TimeBlockKind.AVAILABLE) LocalDate.now().dayOfWeek else DayOfWeek.MONDAY) }
    val startWheel = rememberTimeWheelHold(block?.startMinute ?: 9 * 60, key = block?.id)
    val endWheel = rememberTimeWheelHold(block?.endMinute ?: 10 * 60, allowEndOfDay = true, key = block?.id)
    var note by rememberSaveable(block?.id) { mutableStateOf(block?.note.orEmpty()) }
    var showValidationError by rememberSaveable(block?.id) { mutableStateOf(false) }

    EditorSheet(
        saving = busy,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (block == null) R.string.add_weekly_block else R.string.edit_weekly_block))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                readNotice?.invoke()
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    enabled = !busy,
                    isError = showValidationError && title.isBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("weekly-block-title"),
                    label = { Text(stringResource(R.string.time_block_name)) },
                    placeholder = { Text(stringResource(R.string.time_block_name_hint)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.block_kind), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    values = TimeBlockKind.entries.toList(),
                    selected = kind,
                    label = { stringResource(it.labelRes()) },
                    onSelected = { kind = it },
                    enabled = !busy,
                )
                Text(stringResource(R.string.day_of_week), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    values = DayOfWeek.entries.toList(),
                    selected = dayOfWeek,
                    label = { stringResource(it.labelRes()) },
                    onSelected = { dayOfWeek = it },
                    enabled = !busy,
                )
                LabeledTimeWheel(
                    label = stringResource(R.string.start_time),
                    hold = startWheel,
                    tag = "weekly-block-start",
                    enabled = !busy,
                )
                LabeledTimeWheel(
                    label = stringResource(R.string.end_time),
                    hold = endWheel,
                    tag = "weekly-block-end",
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("weekly-block-note"),
                    label = { Text("备注（可选）") },
                    placeholder = { Text("地点、准备物、老师或上课提醒") },
                    minLines = 2,
                )
                if (showValidationError) {
                    Text(
                        stringResource(R.string.time_block_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("time-mutation-error").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val draft = WeeklyTimeBlockDraft(
                        id = block?.id,
                        title = title,
                        kind = kind,
                        dayOfWeek = dayOfWeek,
                        startMinute = startWheel.settledMinuteOfDay(),
                        endMinute = endWheel.settledMinuteOfDay(),
                        weekPattern = block?.weekPattern,
                        trackId = block?.trackId ?: "course",
                        note = note,
                    )
                    if (TimeBlockValidator.isValid(draft)) onSave(draft) else showValidationError = true
                },
                enabled = !busy && canSave,
                modifier = Modifier.testTag("weekly-block-save"),
            ) { Text(if (busy) "保存中…" else stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DateOverrideEditorDialog(
    dateOverride: DateOverride?,
    onDismiss: () -> Unit,
    onSave: (DateOverrideDraft) -> Unit,
    busy: Boolean = false,
    error: String? = null,
    canSave: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
) {
    var title by rememberSaveable(dateOverride?.id) { mutableStateOf(dateOverride?.title.orEmpty()) }
    var type by rememberSaveable(dateOverride?.id) {
        mutableStateOf(dateOverride?.type ?: DateOverrideType.BLOCKED)
    }
    var dateEpochDay by rememberSaveable(dateOverride?.id) {
        mutableStateOf((dateOverride?.date ?: LocalDate.now()).toEpochDay())
    }
    var showDatePicker by rememberSaveable(dateOverride?.id) { mutableStateOf(false) }
    val startWheel = rememberTimeWheelHold(dateOverride?.startMinute ?: 9 * 60, key = dateOverride?.id)
    val endWheel = rememberTimeWheelHold(dateOverride?.endMinute ?: 10 * 60, allowEndOfDay = true, key = dateOverride?.id)
    var note by rememberSaveable(dateOverride?.id) { mutableStateOf(dateOverride?.note.orEmpty()) }
    var showValidationError by rememberSaveable(dateOverride?.id) { mutableStateOf(false) }

    EditorSheet(
        saving = busy,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (dateOverride == null) R.string.add_date_override else R.string.edit_date_override))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                readNotice?.invoke()
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    enabled = !busy,
                    isError = showValidationError && title.isBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("date-override-title"),
                    label = { Text(stringResource(R.string.time_block_name)) },
                    placeholder = { Text(stringResource(R.string.time_block_name_hint)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.override_type), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    values = DateOverrideType.entries.toList(),
                    selected = type,
                    label = { stringResource(it.labelRes()) },
                    onSelected = { type = it },
                    enabled = !busy,
                )
                DesignClickableSurface(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("date-override-date"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    enabled = !busy,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            taskDateLabel(LocalDate.ofEpochDay(dateEpochDay)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.override_date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                LabeledTimeWheel(
                    label = stringResource(R.string.start_time),
                    hold = startWheel,
                    tag = "date-override-start",
                    enabled = !busy,
                )
                LabeledTimeWheel(
                    label = stringResource(R.string.end_time),
                    hold = endWheel,
                    tag = "date-override-end",
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("date-override-note"),
                    label = { Text("备注（可选）") },
                    placeholder = { Text("地点、准备物或本次调整的原因") },
                    minLines = 2,
                )
                if (showValidationError) {
                    Text(
                        stringResource(R.string.time_block_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("time-mutation-error").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val draft = DateOverrideDraft(
                        id = dateOverride?.id,
                        title = title,
                        type = type,
                        date = LocalDate.ofEpochDay(dateEpochDay),
                        startMinute = startWheel.settledMinuteOfDay(),
                        endMinute = endWheel.settledMinuteOfDay(),
                        note = note,
                    )
                    if (TimeBlockValidator.isValid(draft)) onSave(draft) else showValidationError = true
                },
                enabled = !busy && canSave,
                modifier = Modifier.testTag("date-override-save"),
            ) { Text(if (busy) "保存中…" else stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (showDatePicker) {
        TaskDatePickerDialog(
            initialDate = LocalDate.ofEpochDay(dateEpochDay),
            onDismiss = { showDatePicker = false },
            onDateSelected = { dateEpochDay = it.toEpochDay() },
        )
    }
}

@Composable
private fun DeleteTimeEntryDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
    canSave: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_time_entry)) },
        text = { Column { Text(stringResource(R.string.delete_time_entry_message, title));
            readNotice?.invoke()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("time-mutation-error")) } } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && canSave, modifier = Modifier.testTag("time-delete-confirm")) {
                Text(if (busy) "删除中…" else stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PartialCompletionDialog(
    task: Task,
    busy: Boolean,
    error: String?,
    canConfirm: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (TaskFeedback) -> Unit,
    onComplete: () -> Unit,
) = FeedbackDialog(
    title = stringResource(R.string.partial_completion),
    task = task,
    busy = busy, error = error,
    canConfirm = canConfirm, readNotice = readNotice,
    requireContent = false,
    requirePartialProgress = true,
    showStatusCapsules = true,
    onComplete = onComplete,
    onDismiss = onDismiss,
    onConfirm = onConfirm,
)

@Composable
private fun ExecutionFeedbackDialog(
    task: Task,
    busy: Boolean,
    error: String?,
    canConfirm: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (TaskFeedback) -> Unit,
) = FeedbackDialog(
    title = stringResource(R.string.record_feedback),
    task = task,
    busy = busy, error = error,
    canConfirm = canConfirm, readNotice = readNotice,
    requireContent = false,
    requirePartialProgress = false,
    notice = stringResource(R.string.feedback_does_not_change_status),
    onDismiss = onDismiss,
    onConfirm = onConfirm,
)

@Composable
private fun CorrectExecutionLogDialog(
    log: TaskExecutionLog,
    busy: Boolean,
    error: String?,
    canConfirm: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (TaskFeedback) -> Unit,
) = FeedbackDialog(
    title = stringResource(R.string.correct_log),
    task = null,
    identity = log.id, busy = busy, error = error,
    canConfirm = canConfirm, readNotice = readNotice,
    initialFeedback = log.feedback,
    requireContent = false,
    requirePartialProgress = false,
    showPostponeReason = true,
    notice = stringResource(R.string.correction_notice),
    onDismiss = onDismiss,
    onConfirm = onConfirm,
)

@Composable
private fun FeedbackDialog(
    title: String,
    task: Task?,
    identity: String = task?.id.orEmpty(),
    busy: Boolean = false,
    error: String? = null,
    initialFeedback: TaskFeedback = TaskFeedback(),
    requireContent: Boolean,
    requirePartialProgress: Boolean,
    canConfirm: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
    showPostponeReason: Boolean = false,
    showStatusCapsules: Boolean = false,
    notice: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (TaskFeedback) -> Unit,
    onComplete: () -> Unit = {},
) {
    var completedContent by rememberSaveable(identity) {
        mutableStateOf(initialFeedback.completedContent.orEmpty())
    }
    var completionResult by rememberSaveable(identity) {
        mutableStateOf(initialFeedback.completionResult.orEmpty())
    }
    var actualDurationText by rememberSaveable(identity) {
        mutableStateOf(initialFeedback.actualDurationMinutes?.toString().orEmpty())
    }
    var progressText by rememberSaveable(identity) {
        mutableStateOf(initialFeedback.progressPercent?.toString().orEmpty())
    }
    var postponeReason by rememberSaveable(identity) {
        mutableStateOf(initialFeedback.postponeReason.orEmpty())
    }
    var showValidationError by rememberSaveable(identity) { mutableStateOf(false) }
    val validActual = FeedbackFormValidation.validNumber(actualDurationText, 1..1440)
    val validProgress = FeedbackFormValidation.validNumber(progressText,
        if (requirePartialProgress) 1..99 else 0..100, required = false)
    EditorSheet(
        onDismissRequest = onDismiss,
        saving = busy,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                readNotice?.invoke()
                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showStatusCapsules) {
                    CapsuleRow(
                        capsules = listOf(
                            CapsuleSpec("complete", "完成", CapsuleKind.VERB, enabled = !busy),
                            CapsuleSpec("unfinished", "未完成", CapsuleKind.VERB, enabled = !busy),
                        ),
                        onCapsuleClick = { spec ->
                            if (spec.key == "complete") {
                                onComplete()
                            } else {
                                val actual = actualDurationText.trim().takeIf(String::isNotBlank)?.toIntOrNull()
                                onConfirm(TaskFeedback(
                                    completedContent = completedContent.trim().takeIf(String::isNotBlank),
                                    completionResult = completionResult.trim().takeIf(String::isNotBlank),
                                    actualDurationMinutes = actual?.takeIf { validActual },
                                    progressPercent = null,
                                    postponeReason = postponeReason.trim().takeIf(String::isNotBlank),
                                ))
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = completedContent,
                    enabled = !busy,
                    isError = showValidationError && requireContent && completedContent.isBlank(),
                    supportingText = { if (showValidationError && requireContent && completedContent.isBlank()) Text("请填写本次完成的内容") },
                    onValueChange = { completedContent = it },
                    modifier = Modifier.fillMaxWidth().testTag("feedback-content"),
                    label = {
                        Text(stringResource(if (requireContent) R.string.completed_content_required else R.string.completed_content_optional))
                    },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = completionResult,
                    enabled = !busy,
                    onValueChange = { completionResult = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.completion_result_optional)) },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = actualDurationText,
                    enabled = !busy,
                    onValueChange = { actualDurationText = it },
                    isError = actualDurationText.isNotBlank() && !validActual,
                    supportingText = { Text("实际时长应为 1–1440 分钟；留空表示未知") },
                    modifier = Modifier.fillMaxWidth().testTag("feedback-actual-duration"),
                    label = { Text(stringResource(R.string.actual_duration_optional)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = progressText,
                    enabled = !busy,
                    onValueChange = { progressText = it },
                    isError = (progressText.isNotBlank() || showValidationError) && !validProgress,
                    supportingText = { Text(if (requirePartialProgress) "部分完成进度应为 1–99%；留空表示未知" else "进度应为 0–100%；留空表示未知") },
                    modifier = Modifier.fillMaxWidth().testTag("feedback-progress"),
                    label = {
                        Text(stringResource(if (requirePartialProgress) R.string.partial_progress_required else R.string.progress_optional))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (showPostponeReason) {
                    OutlinedTextField(
                        value = postponeReason,
                        enabled = !busy,
                        onValueChange = { postponeReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.postpone_reason_optional)) },
                        minLines = 2,
                    )
                }
                if (showValidationError) {
                    Text(stringResource(R.string.feedback_validation_error), color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("feedback-save-error").semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val actual = actualDurationText.trim().takeIf(String::isNotBlank)?.toIntOrNull()
                    val progress = progressText.trim().takeIf(String::isNotBlank)?.toIntOrNull()
                    val feedback = TaskFeedback(
                        completedContent = completedContent,
                        completionResult = completionResult,
                        actualDurationMinutes = actual,
                        progressPercent = progress,
                        postponeReason = postponeReason,
                    )
                    val hasFeedback = actual != null || progress != null || completedContent.isNotBlank() ||
                        completionResult.isNotBlank() || postponeReason.isNotBlank()
                    if (hasFeedback && validActual && validProgress &&
                        (!requireContent || completedContent.isNotBlank())
                    ) onConfirm(feedback) else showValidationError = true
                },
                modifier = Modifier.testTag("feedback-confirm"),
                enabled = !busy && canConfirm,
            ) { Text(if (busy) "保存中…" else stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmTaskStatusDialog(
    title: String,
    message: String,
    confirmLabel: String,
    busy: Boolean = false,
    error: String? = null,
    canConfirm: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(title) },
        text = { Column { readNotice?.invoke(); Text(message); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && canConfirm, modifier = Modifier.testTag("cancel-task-confirm")) { Text(if (busy) "保存中…" else confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun Task.isTodayRelevant(): Boolean = status.isActive

private fun android.content.Context.canScheduleExactAlarmsCompat(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

/** A completed time window is informational until the user records task feedback. */
private fun PlannedSegment.hasEndedBefore(now: LocalDateTime): Boolean =
    !date.atStartOfDay().plusMinutes(endMinute.toLong()).isAfter(now)

private fun TaskCategory.portraitLabel(): String = when (this) {
    TaskCategory.COURSE -> "课程"
    TaskCategory.EXTRACURRICULAR -> "课外"
    TaskCategory.OFFICE -> "办公"
    TaskCategory.LEISURE -> "休闲"
    TaskCategory.UNSPECIFIED -> "未分类"
}

private fun TaskStatus.portraitLabel(): String = when (this) {
    TaskStatus.NOT_STARTED -> "待办"
    TaskStatus.IN_PROGRESS -> "进行中"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.POSTPONED -> "已延期"
    TaskStatus.CANCELLED -> "已取消"
    TaskStatus.REPLACED -> "已替换"
}

@StringRes
private fun TaskStatus.labelRes(): Int = when (this) {    TaskStatus.NOT_STARTED -> R.string.status_not_started
    TaskStatus.IN_PROGRESS -> R.string.status_in_progress
    TaskStatus.COMPLETED -> R.string.status_completed
    TaskStatus.POSTPONED -> R.string.status_postponed
    TaskStatus.CANCELLED -> R.string.status_cancelled
    TaskStatus.REPLACED -> R.string.status_replaced
}

@StringRes
private fun ExecutionLogEventType.labelRes(): Int = when (this) {
    ExecutionLogEventType.STATUS_CHANGE -> R.string.log_event_status_change
    ExecutionLogEventType.FEEDBACK -> R.string.log_event_feedback
    ExecutionLogEventType.PARTIAL_COMPLETION -> R.string.log_event_partial_completion
    ExecutionLogEventType.CORRECTION -> R.string.log_event_correction
    ExecutionLogEventType.REPLACEMENT -> R.string.log_event_replacement
}

private fun ProfileEvidenceScope.labelRes(): Int = when (this) {
    ProfileEvidenceScope.GENERAL -> R.string.profile_evidence_scope_general
    ProfileEvidenceScope.LEARNING -> R.string.profile_evidence_scope_learning
}

@StringRes
private fun AiAdvisorFailureReason.labelRes(): Int = when (this) {
    AiAdvisorFailureReason.SOURCE_CHANGED -> R.string.ai_failure_source_changed
    AiAdvisorFailureReason.CONTEXT_UNAVAILABLE -> R.string.ai_failure_context_unavailable
    AiAdvisorFailureReason.CONFIGURATION_CHANGED -> R.string.ai_failure_configuration_changed
    AiAdvisorFailureReason.DISABLED -> R.string.ai_failure_disabled
    AiAdvisorFailureReason.SERVICE_NOT_CONFIGURED -> R.string.ai_failure_not_configured
    AiAdvisorFailureReason.TRANSPORT_FAILURE -> R.string.ai_failure_transport
    AiAdvisorFailureReason.TIMEOUT -> R.string.ai_failure_timeout
    AiAdvisorFailureReason.AUTHENTICATION_FAILURE -> R.string.ai_failure_authentication
    AiAdvisorFailureReason.RATE_LIMITED -> R.string.ai_failure_rate_limited
    AiAdvisorFailureReason.REMOTE_FAILURE -> R.string.ai_failure_remote
    AiAdvisorFailureReason.INVALID_RESPONSE -> R.string.ai_failure_invalid_response
}

private fun formatExecutionLogTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable
internal fun priorityReasonText(reason: PriorityReason): String = when (reason.kind) {
    PriorityReasonKind.INITIAL_PRIORITY -> if (reason.value == null) "优先级未设置，使用中性排序值" else stringResource(
        R.string.priority_reason_initial,
        reason.value ?: 0,
    )

    PriorityReasonKind.CATEGORY_PREFERENCE -> if (reason.value == null) "未分类，类别因子使用中性值" else stringResource(
        R.string.priority_reason_category,
        reason.value ?: 0,
    )

    PriorityReasonKind.OVERDUE -> stringResource(R.string.priority_reason_overdue, reason.value ?: 0)
    PriorityReasonKind.DEADLINE -> when (reason.value ?: 0) {
        0 -> stringResource(R.string.priority_reason_due_today)
        else -> stringResource(R.string.priority_reason_due_in_days, reason.value ?: 0)
    }

    PriorityReasonKind.POSTPONEMENTS -> stringResource(R.string.priority_reason_postponed, reason.value ?: 0)
    PriorityReasonKind.PLANNED_DATE -> "计划日期距今天 ${reason.value ?: 0} 天"
    PriorityReasonKind.LOCAL_AI_NEUTRAL -> "本地排序：AI 修正为 0"
}

@StringRes
private fun UnscheduledReason.labelRes(): Int = when (this) {
    UnscheduledReason.USER_DEFERRED -> R.string.plan_user_deferred_reason
    UnscheduledReason.TOTAL_CAPACITY_IN_ROLLING_WINDOW -> R.string.capacity_total_window_reason
    UnscheduledReason.CAPACITY_BEFORE_DUE_DATE -> R.string.capacity_before_due_reason
    UnscheduledReason.CAPACITY_IN_ROLLING_WINDOW -> R.string.capacity_rolling_window_reason
}

@StringRes
private fun TaskPriority.labelRes(): Int = when (this) {
    TaskPriority.UNSPECIFIED -> R.string.priority_unspecified
    TaskPriority.REQUIRED -> R.string.priority_required
    TaskPriority.HIGH -> R.string.priority_high
    TaskPriority.MEDIUM -> R.string.priority_medium
    TaskPriority.LOW -> R.string.priority_low
}

@StringRes
private fun TaskCategory.labelRes(): Int = when (this) {
    TaskCategory.UNSPECIFIED -> R.string.category_unspecified
    TaskCategory.COURSE -> R.string.category_course
    TaskCategory.EXTRACURRICULAR -> R.string.category_extracurricular
    TaskCategory.OFFICE -> R.string.category_office
    TaskCategory.LEISURE -> R.string.category_leisure
}

@StringRes
private fun TimeBlockKind.labelRes(): Int = when (this) {
    TimeBlockKind.AVAILABLE -> R.string.block_kind_available
    TimeBlockKind.COURSE -> R.string.block_kind_course
    TimeBlockKind.REST -> R.string.block_kind_rest
    TimeBlockKind.OTHER -> R.string.block_kind_other
}

@StringRes
private fun DateOverrideType.labelRes(): Int = when (this) {
    DateOverrideType.BLOCKED -> R.string.override_blocked
    DateOverrideType.AVAILABLE -> R.string.override_available
}

@StringRes
private fun DayOfWeek.labelRes(): Int = when (this) {
    DayOfWeek.MONDAY -> R.string.monday
    DayOfWeek.TUESDAY -> R.string.tuesday
    DayOfWeek.WEDNESDAY -> R.string.wednesday
    DayOfWeek.THURSDAY -> R.string.thursday
    DayOfWeek.FRIDAY -> R.string.friday
    DayOfWeek.SATURDAY -> R.string.saturday
    DayOfWeek.SUNDAY -> R.string.sunday
}
