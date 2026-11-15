package com.swan1127.repland.ui.team

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swan1127.repland.data.team.TeamDemoSeed
import com.swan1127.repland.domain.team.BlockVisibility
import com.swan1127.repland.domain.team.MemberCard
import com.swan1127.repland.domain.team.Objection
import com.swan1127.repland.domain.team.PersonalBlock
import com.swan1127.repland.domain.team.TaskRank
import com.swan1127.repland.domain.team.TeamCollabState
import com.swan1127.repland.domain.team.TeamPermission
import com.swan1127.repland.domain.team.TeamTask
import com.swan1127.repland.domain.team.TeamTaskStatus
import com.swan1127.repland.R
import com.swan1127.repland.ui.components.EmptyScene
import com.swan1127.repland.ui.components.DesignClickableSurface
import com.swan1127.repland.ui.components.DesignOutlinedButton
import com.swan1127.repland.ui.theme.replandAccent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * TEAM tab 主屏：按功能优先级排布，一屏只回答四个问题——
 * 1) 接下来最要紧的是什么（竞赛排期 hero，含倒排与「排这一周」）
 * 2) 有什么在等我（待裁决 / 逾期 / 未分配 / 周报）
 * 3) 我自己的任务与本周安排（团队任务落到个人）
 * 4) 想补充什么（一句话说事，原话直通编排）
 * 档案 / 基线 / 台账 / 记忆 / 权限等低频管理面收进右上角 ⋮，改成整页进入。
 */
private val DAY_MONTH = DateTimeFormatter.ofPattern("M月d日")
private val NUM_MONTH = DateTimeFormatter.ofPattern("M/d")

private enum class TeamPage(val label: String) {
    CHARTER("队伍大局"), PROFILE("比赛档案"), BASELINE("任务基线"), LEDGER("台账与周报"), MEMORY("记忆库"),
    CARDS("我的资料卡"), PERMISSION("权限控制台"),
}

@Composable
fun TeamScreen(vm: TeamViewModel) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val snapshot = ui.snapshot
    val snackbar = remember { SnackbarHostState() }
    var entryChooser by rememberSaveable { mutableStateOf(false) }
    var showCaptainCreate by rememberSaveable { mutableStateOf(false) }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    var showBackendSettings by rememberSaveable { mutableStateOf(false) }
    var showCards by rememberSaveable { mutableStateOf(false) }
    var openCompKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.refreshAiAvailability()
        // 团队域暂以演示数据打底：没有任何真实队伍时自动载入演示种子（一次）
        vm.ensureDemoTeam()
    }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.testTag("team-snackbar")) },
        // 外层 App Scaffold 已经处理过系统栏；内层再取一次 inset 会在标题下多出一截空白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // 团队页也站在全局山水背景上，不再铺一层不透明底色把背景盖掉。
        containerColor = Color.Transparent,
    ) { padding ->
        if (showBackendSettings) {
            BackendSettingsScreen(vm = vm, onClose = { showBackendSettings = false })
        } else if (snapshot == null) {
            TeamEmptyHome(
                ui = ui,
                onStart = { entryChooser = true },
                onCaptain = { showCaptainCreate = true },
                onMember = { showJoin = true },
                onDemo = { vm.loadDemo() },
                onOpenCards = { showCards = true },
                onOpenInstance = { openCompKey = it.key },
                onDeleteAward = vm::deleteAward,
                modifier = Modifier.padding(padding),
            )
        } else {
            val state = snapshot.state
            val actor = ui.actorId?.let { id -> state.members.firstOrNull { it.memberId == id } }
            TeamHome(
                vm, ui, state, actor,
                onCreateTeam = { entryChooser = true },
                onOpenBackendSettings = { showBackendSettings = true },
                onOpenInstance = { openCompKey = it.key },
                modifier = Modifier.padding(padding),
            )
        }
    }
    if (entryChooser) {
        TeamEntryChooser(
            onDismiss = { entryChooser = false },
            onCaptain = { entryChooser = false; showCaptainCreate = true },
            onMember = { entryChooser = false; showJoin = true },
        )
    }
    if (showCaptainCreate) CaptainCreateSheet(vm, onDismiss = { showCaptainCreate = false })
    if (showJoin) JoinTeamDialog(vm, onDismiss = { showJoin = false })
    if (showCards) {
        val state = snapshot?.state
        val actor = state?.let { current ->
            ui.actorId?.let { id -> current.members.firstOrNull { it.memberId == id } }
        }
        TeamCardsOverlay(vm, ui, state, actor, onDismiss = { showCards = false })
    }
    // 校内比赛详情：空态与主界面共用一个 sheet，入口在比赛卡堆下方的竞赛库卡片
    val compSeed = ui.competitionSeed
    val openComp = openCompKey?.let { key -> compSeed?.instance(key) }
    if (compSeed != null && openComp != null) {
        CompetitionInstanceSheet(
            instance = openComp,
            seed = compSeed,
            tracking = ui.competitionTracking.firstOrNull { it.instanceKey == openComp.key },
            onFollow = vm::followCompetition,
            onUnfollow = vm::unfollowCompetition,
            onSetStatus = vm::setTrackStatus,
            onToggleRemind = vm::toggleTrackRemind,
            onRegisterAward = vm::registerAward,
            onDismiss = { openCompKey = null },
        )
    }
}

@Composable
private fun TeamEmptyHome(
    ui: TeamViewModel.UiState,
    onStart: () -> Unit,
    onCaptain: () -> Unit,
    onMember: () -> Unit,
    onDemo: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenInstance: (com.swan1127.repland.domain.competition.CompetitionInstance) -> Unit,
    onDeleteAward: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CompetitionDeck(
            teams = ui.teams,
            state = null,
            revision = 0L,
            onSwitchTeam = {},
            onAdd = onStart,
            onOpenBaseline = {},
            onOpenProfile = {},
            onPlan = {},
            canPlan = false,
            planBusy = false,
            archived = false,
        )
        ui.competitionSeed?.let { seed ->
            SchoolCompetitionsCard(seed, ui.competitionTracking, ui.competitionAwards,
                onOpenInstance = onOpenInstance, onDeleteAward = onDeleteAward)
        }
        Text("从哪一步开始", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        EmptyGuideRow(
            badge = "卡",
            title = "先写个人资料卡",
            tag = "team-empty-cards",
            onClick = onOpenCards,
        )
        EmptyGuideRow(
            badge = "队",
            title = "队长：登记比赛",
            tag = "team-empty-captain",
            onClick = onCaptain,
        )
        EmptyGuideRow(
            badge = "员",
            title = "队员：输入队伍码",
            tag = "team-empty-member",
            onClick = onMember,
        )
        TextButton(onClick = onDemo, modifier = Modifier.testTag("team-demo")) {
            Text("先看看演示队伍 ›")
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun EmptyGuideRow(
    badge: String,
    title: String,
    tag: String,
    onClick: () -> Unit,
) {
    val accent = replandAccent(if (badge == "队") 1 else 2)
    DesignClickableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(accent.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = accent.ink)
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium)
            Text("›", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- 建队入口已移到 TeamOnboarding：空态先选身份，队长登记信息后直接成档案卡 ----------

// ---------- 主界面 ----------

@Composable
private fun TeamHome(
    vm: TeamViewModel,
    ui: TeamViewModel.UiState,
    state: TeamCollabState,
    actor: MemberCard?,
    onCreateTeam: () -> Unit,
    onOpenBackendSettings: () -> Unit,
    onOpenInstance: (com.swan1127.repland.domain.competition.CompetitionInstance) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { TeamPage.valueOf(it) }
    var openTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showInvite by rememberSaveable { mutableStateOf(false) }
    // 域对象是就地变更的可变聚合：只有把 revision 传进读取它的区块，Compose 才不会因
    // 「参数还是同一个实例」而跳过重组（否则确认排期后仍显示旧的任务/时段）。
    val revision = ui.snapshot?.revision ?: 0L

    Column(modifier.fillMaxSize()) {
        if (page == null) {
            TeamTopBar(
                state, ui, vm, actor,
                onOpenBackendSettings = onOpenBackendSettings,
                onCreateTeam = onCreateTeam,
                onOpenPage = { pageName = it.name },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.archived) ArchivedBanner()
                // 一屏只回答：这场比赛的安排 → 我的任务 → 需要处理 → 补充信息 → 本周排期。
                CompetitionDeck(
                    teams = ui.teams,
                    state = state,
                    revision = revision,
                    onSwitchTeam = vm::switchTeam,
                    onAdd = onCreateTeam,
                    onOpenBaseline = { pageName = TeamPage.BASELINE.name },
                    onOpenProfile = { pageName = TeamPage.PROFILE.name },
                    onPlan = vm::planWeek,
                    canPlan = actor?.hasPermission(TeamPermission.SCHEDULE_TRANSFER) == true,
                    planBusy = ui.busy == TeamBusy.SCHEDULING,
                    archived = state.archived,
                    onInvite = { showInvite = true },
                )
                CharterSummaryCard(
                    state = state,
                    revision = revision,
                    onOpen = { pageName = TeamPage.CHARTER.name },
                )
                ui.competitionSeed?.let { seed ->
                    SchoolCompetitionsCard(seed, ui.competitionTracking, ui.competitionAwards,
                        onOpenInstance = onOpenInstance, onDeleteAward = vm::deleteAward)
                }
                MyTasksSection(
                    state, actor,
                    revision = revision,
                    onOpenTask = { openTaskId = it },
                    onOpenPage = { pageName = it.name },
                )
                ActionQueue(
                    vm, state, actor, ui.weeklyReport,
                    revision = revision,
                    onOpenPage = { pageName = it.name },
                    onOpenTask = { openTaskId = it },
                )
                SetupStepsCard(
                    state = state,
                    revision = revision,
                    onOpenPage = { pageName = it.name },
                    onInvite = { showInvite = true },
                )
                ScheduleSection(
                    vm, state, actor,
                    revision = revision,
                    onOpenTask = { openTaskId = it },
                    canSchedule = actor?.hasPermission(TeamPermission.SCHEDULE_TRANSFER) == true,
                    archived = state.archived,
                )
                Spacer(Modifier.height(4.dp))
            }
        } else {
            SectionPage(page.label, onBack = { pageName = null }) {
                when (page) {
                    TeamPage.CHARTER -> CharterPanel(vm, state, actor, revision)
                    TeamPage.PROFILE -> ProfilePanel(vm, state, actor, revision)
                    TeamPage.BASELINE -> BaselinePanel(vm, state, actor, revision, onOpenTask = { openTaskId = it.taskId })
                    TeamPage.LEDGER -> LedgerPanel(vm, state, actor, ui.weeklyReport, revision)
                    TeamPage.MEMORY -> MemoryPanel(vm, state, actor, revision)
                    TeamPage.CARDS -> TeamCardsPanel(vm, ui, state, actor, revision)
                    TeamPage.PERMISSION -> if (actor?.isCaptain == true) PermissionPanel(vm, state, actor, revision)
                }
            }
        }
    }

    openTaskId?.let { taskId ->
        state.tasks.firstOrNull { it.taskId == taskId }?.let { task ->
            TaskConfirmSheet(vm, state, actor, task, onDismiss = { openTaskId = null })
        }
    }
    if (showInvite) TeamInviteSheet(vm, state, actor, onDismiss = { showInvite = false })
}

@Composable
private fun SetupStepsCard(
    state: TeamCollabState,
    revision: Long,
    onOpenPage: (TeamPage) -> Unit,
    onInvite: () -> Unit,
) {
    val hasCharter = remember(revision) { state.charter.goal.isNotBlank() }
    val hasProfile = remember(revision) { state.profileCard != null }
    val hasMembers = remember(revision) { state.members.any { !it.isCaptain } }
    val hasBaseline = remember(revision) { state.phases.isNotEmpty() || state.tasks.isNotEmpty() }
    if (hasCharter && hasProfile && hasMembers && hasBaseline) return
    Card(Modifier.fillMaxWidth().testTag("team-setup-steps")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("开跑前还差几步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SetupStep(1, "队伍大局", hasCharter, "队长定目标、立队规，给全队一个大局",
                Modifier.testTag("setup-charter")) { onOpenPage(TeamPage.CHARTER) }
            SetupStep(2, "比赛档案", hasProfile, "粘贴比赛材料，自动填六组槽位",
                Modifier.testTag("setup-profile")) { onOpenPage(TeamPage.PROFILE) }
            SetupStep(3, "队员", hasMembers, "发码、翻历史队友、记下想拉的人",
                Modifier.testTag("setup-members"), onClick = onInvite)
            SetupStep(4, "任务基线", hasBaseline, "建任务时设截止，当场排进个人日程",
                Modifier.testTag("setup-baseline")) { onOpenPage(TeamPage.BASELINE) }
        }
    }
}

@Composable
private fun CharterSummaryCard(state: TeamCollabState, revision: Long, onOpen: () -> Unit) {
    val charter = remember(revision) { state.charter }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("team-charter-summary")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("队伍大局", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Badge("${charter.activeRules.size} 条队规生效", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("查看 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                charter.goal.ifBlank { "还没定目标：队长写一句话，给全队一个大局" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (charter.goal.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SetupStep(
    index: Int,
    label: String,
    done: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "✓" else "$index",
                style = MaterialTheme.typography.labelSmall,
                color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (done) "已完成" else "去处理 ›", style = MaterialTheme.typography.labelSmall,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("team-section-page")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("team-section-back")) { Text("‹ 返回") }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun ArchivedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "队伍已归档 · 只读",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- 顶栏：队伍 / 身份 / 低频入口 ----------

@Composable
private fun TeamTopBar(
    state: TeamCollabState,
    ui: TeamViewModel.UiState,
    vm: TeamViewModel,
    actor: MemberCard?,
    onCreateTeam: () -> Unit,
    onOpenBackendSettings: () -> Unit,
    onOpenPage: (TeamPage) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 外层 App 顶栏已经有「团队协作」标题；这里只放身份与低频入口，队伍名/多队切换在比赛窗口里。
        if (state.teamId == TeamDemoSeed.DEMO_TEAM_ID) {
            Badge("演示", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(6.dp))
        }
        IdentityChip(state, ui, vm)
        Spacer(Modifier.weight(1f))
        MoreMenu(actor, onOpenPage, onOpenBackendSettings, onCreateTeam)
    }
}

@Composable
private fun MoreMenu(
    actor: MemberCard?,
    onOpenPage: (TeamPage) -> Unit,
    onOpenBackendSettings: () -> Unit,
    onCreateTeam: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("team-more")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TeamPage.entries.forEach { candidate ->
                if (candidate == TeamPage.PERMISSION && actor?.isCaptain != true) return@forEach
                DropdownMenuItem(
                    text = { Text(candidate.label) },
                    onClick = {
                        open = false
                        onOpenPage(candidate)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("账号与服务器") }, onClick = {
                open = false
                onOpenBackendSettings()
            })
            DropdownMenuItem(text = { Text("切换 / 新建队伍") }, onClick = {
                open = false
                onCreateTeam()
            })
        }
    }
}

/** 身份切换收成一枚小胶囊：点开选人，不再占一整行成员 chip。 */
@Composable
private fun IdentityChip(state: TeamCollabState, ui: TeamViewModel.UiState, vm: TeamViewModel) {
    var open by remember { mutableStateOf(false) }
    val actor = ui.actorId?.let { id -> state.members.firstOrNull { it.memberId == id } }
    val accent = replandAccent(if (actor?.isCaptain == true) 1 else 0)
    Box {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clickable { open = true }.testTag("team-actor"),
        ) {
            Row(
                Modifier.padding(start = 5.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(accent.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(actor?.name?.take(1) ?: "?", style = MaterialTheme.typography.labelSmall,
                        color = accent.ink, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    actor?.let { "你：${it.name}${if (it.isCaptain) " · 队长" else ""}" } ?: "选身份",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text("▾", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.members.forEach { member ->
                DropdownMenuItem(
                    text = { Text(if (member.memberId == ui.actorId) "${member.name} ✓" else member.name) },
                    onClick = {
                        open = false
                        vm.switchActor(member.memberId)
                    },
                    trailingIcon = {
                        if (member.isCaptain) {
                            Badge("队长", MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                )
            }
        }
    }
}

// ---------- 2) 需要你处理 ----------

@Composable
private fun ActionQueue(
    vm: TeamViewModel,
    state: TeamCollabState,
    actor: MemberCard?,
    weeklyReport: com.swan1127.repland.domain.team.WeeklyReportDraft?,
    revision: Long,
    onOpenPage: (TeamPage) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val today = LocalDate.now()
    val openObjections = remember(revision) { state.openObjections() }
    val overdue = remember(revision, today) {
        state.tasks.filter { task ->
            task.active && task.effectiveDeadline(state.phases, state.competitionDeadline)?.isBefore(today) == true
        }
    }
    val unassigned = remember(revision) { state.tasks.filter { it.active && it.assignedTo == null } }
    val canSchedule = actor?.hasPermission(TeamPermission.SCHEDULE_TRANSFER) == true
    val total = openObjections.size + overdue.size + unassigned.size + if (weeklyReport != null) 1 else 0
    if (total == 0) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("需要你处理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Badge("$total", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        if (openObjections.isNotEmpty()) {
            if (actor?.isCaptain == true) {
                openObjections.forEach { objection -> ObjectionCard(vm, state, objection) }
            } else {
                ActionRow(
                    title = "有 ${openObjections.size} 项异议待队长裁决",
                    hint = "",
                    action = null, urgent = false, onClick = null,
                )
            }
        }
        if (overdue.isNotEmpty()) {
            ActionRow(
                title = "${overdue.size} 项任务已过截止",
                hint = overdue.first().title,
                action = if (canSchedule) "改期" else "查看",
                urgent = true,
            ) { onOpenTask(overdue.first().taskId) }
        }
        if (unassigned.isNotEmpty()) {
            ActionRow(
                title = "${unassigned.size} 项任务还没人接",
                hint = unassigned.take(3).joinToString("、") { it.title },
                action = if (canSchedule) "去指派" else "查看",
                urgent = false,
            ) {
                if (canSchedule) onOpenTask(unassigned.first().taskId) else onOpenPage(TeamPage.BASELINE)
            }
        }
        if (weeklyReport != null) {
            ActionRow(
                title = "本周周报已生成",
                hint = "进展摘要 · 风险雷达 · 下周重点",
                action = "查看", urgent = false,
            ) { onOpenPage(TeamPage.LEDGER) }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    hint: String,
    action: String?,
    urgent: Boolean,
    onClick: (() -> Unit)?,
) {
    DesignClickableSurface(
        onClick = { onClick?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        enabled = onClick != null,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            action?.let {
                Text("$it ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ObjectionCard(vm: TeamViewModel, state: TeamCollabState, objection: Objection) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.tasks.firstOrNull { it.taskId == objection.taskId }?.title ?: objection.taskId,
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Badge("待裁决", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
            Text("${memberName(state, objection.raisedBy)}：${objection.reason}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.resolveObjection(objection.objectionId, keepTransfer = true) }) { Text("维持转派") }
                TextButton(onClick = { vm.resolveObjection(objection.objectionId, keepTransfer = false) }) { Text("收回转派") }
            }
        }
    }
}

// ---------- 3) 我的任务 ----------

@Composable
private fun MyTasksSection(
    state: TeamCollabState,
    actor: MemberCard?,
    revision: Long,
    onOpenTask: (String) -> Unit,
    onOpenPage: (TeamPage) -> Unit,
) {
    if (actor == null) return
    val mine = remember(revision, actor.memberId) {
        state.tasks
            .filter { it.assignedTo == actor.memberId && it.active }
            .sortedBy { it.effectiveDeadline(state.phases, state.competitionDeadline) ?: LocalDate.MAX }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的任务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("${mine.size} 项", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onOpenPage(TeamPage.BASELINE) }) { Text("全部 ›") }
        }
        if (mine.isEmpty()) {
            Text("还没分到你名下的任务", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            mine.take(3).forEach { task -> MyTaskRow(state, task, onClick = { onOpenTask(task.taskId) }) }
            if (mine.size > 3) {
                Text("还有 ${mine.size - 3} 项", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MyTaskRow(state: TeamCollabState, task: TeamTask, onClick: () -> Unit) {
    val deadline = task.effectiveDeadline(state.phases, state.competitionDeadline)
    val days = deadline?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() }
    DesignClickableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(999.dp))
                    .background(rankColor(task.rank)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildList {
                        add(state.phases.firstOrNull { it.phaseId == task.phaseId }?.name ?: task.tier.label)
                        add("${task.estimatedHours.toInt()}h")
                        add(deadline?.format(DAY_MONTH) ?: "未定截止")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    task.status == TeamTaskStatus.IN_PROGRESS -> "进行中"
                    days == null -> "待定"
                    days < 0 -> "逾期 ${-days} 天"
                    days == 0 -> "今天"
                    else -> "$days 天"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    days != null && days < 0 -> MaterialTheme.colorScheme.error
                    task.status == TeamTaskStatus.IN_PROGRESS -> com.swan1127.repland.ui.theme.LocalSemanticStateColors.current.onInProgress
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun rankColor(rank: TaskRank): Color = when (rank) {
    TaskRank.MUST -> MaterialTheme.colorScheme.error
    TaskRank.SHOULD -> MaterialTheme.colorScheme.primary
    TaskRank.COULD -> MaterialTheme.colorScheme.tertiary
    TaskRank.WONT -> MaterialTheme.colorScheme.outline
}

// ---------- 4) 本周排期 ----------

@Composable
private fun ScheduleSection(
    vm: TeamViewModel,
    state: TeamCollabState,
    actor: MemberCard?,
    revision: Long,
    onOpenTask: (String) -> Unit,
    canSchedule: Boolean,
    archived: Boolean,
) {
    var mode by rememberSaveable { mutableStateOf("list") }
    var movingBlock by remember { mutableStateOf<PersonalBlock?>(null) }
    // 聚合就地变更：以 revision 为键重算派生值，Compose 才会把这块连同子区块一起重组。
    val weekBlockCount = remember(revision) {
        val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        state.blocks.count {
            it.sourceTeamId == state.teamId && !it.date.isBefore(monday) && it.date.isBefore(monday.plusWeeks(1))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本周排期", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("$weekBlockCount 段", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            MiniToggle(mode, onSelect = { mode = it })
        }
        WeekLoadLine(state, revision)
        if (mode == "timeline") {
            DayRope(vm, state, revision, onMoveBlock = { movingBlock = it })
        } else {
            WeekAgenda(state, actor, revision, onOpenTask = onOpenTask, onMoveBlock = { movingBlock = it })
        }
    }
    movingBlock?.let { block -> BlockMoveDialog(vm, block, onDismiss = { movingBlock = null }) }
}

@Composable
private fun MiniToggle(selected: String, onSelect: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("list" to "列表", "timeline" to "时间轴").forEach { (key, label) ->
                val active = key == selected
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                ) {
                    Text(
                        label,
                        Modifier.clickable { onSelect(key) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekLoadLine(state: TeamCollabState, revision: Long) {
    val line = remember(revision) {
        val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        fun minutesOf(memberId: String?) = state.blocks
            .filter {
                (memberId == null || it.memberId == memberId) && it.sourceTeamId == state.teamId &&
                    !it.date.isBefore(monday) && it.date.isBefore(monday.plusWeeks(1))
            }
            .sumOf { it.endMinute - it.startMinute }
        val total = minutesOf(null)
        val memberIds = state.members.map { it.memberId }.toSet()
        val hasOtherBlocks = state.blocks.any {
            it.memberId in memberIds && !it.date.isBefore(monday) && it.date.isBefore(monday.plusWeeks(1))
        }
        if (total == 0) {
            "本周团队还没有排期"
        } else {
            "本周已排 ${hoursText(total)} · " +
                state.members.joinToString(" · ") { "${it.name} ${hoursText(minutesOf(it.memberId))}" }
        }
    }
    Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun hoursText(minutes: Int): String {
    if (minutes <= 0) return "0h"
    return "%.1f".format(minutes / 60f).removeSuffix(".0") + "h"
}

@Composable
private fun WeekAgenda(
    state: TeamCollabState,
    actor: MemberCard?,
    revision: Long,
    onOpenTask: (String) -> Unit,
    onMoveBlock: (PersonalBlock) -> Unit,
) {
    val today = LocalDate.now()
    val memberIds = remember(revision) { state.members.map { it.memberId }.toSet() }
    val days = (0..6).map { today.plusDays(it.toLong()) }
    val agenda = remember(revision, days) {
        days.map { day ->
            day to state.blocks
                .filter { it.memberId in memberIds && it.date == day }
                .sortedBy { it.startMinute }
        }.filter { it.second.isNotEmpty() }
    }

    if (agenda.isEmpty()) {
        EmptyScene(
            artwork = R.drawable.empty_team,
            title = "本周还没有具体安排",
            hint = "用上方「排这一周」让 AI 生成草案",
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        agenda.forEach { (day, blocks) ->
            val dayLabel = when (ChronoUnit.DAYS.between(today, day).toInt()) {
                0 -> "今天"
                1 -> "明天"
                else -> "周${"一二三四五六日"[day.dayOfWeek.value - 1]}"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(46.dp).padding(top = 7.dp)) {
                    Text(
                        dayLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(day.format(NUM_MONTH), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    blocks.forEach { block ->
                        AgendaBlock(state, actor, block, onOpenTask, onMoveBlock)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaBlock(
    state: TeamCollabState,
    actor: MemberCard?,
    block: PersonalBlock,
    onOpenTask: (String) -> Unit,
    onMoveBlock: (PersonalBlock) -> Unit,
) {
    val mine = block.memberId == actor?.memberId
    val isPrivate = block.visibility == BlockVisibility.PRIVATE_BUSY
    val isTeamBlock = block.sourceTeamId == state.teamId
    Surface(
        color = when {
            isPrivate -> MaterialTheme.colorScheme.surfaceContainerHighest
            isTeamBlock -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable {
            when {
                mine && isTeamBlock -> onMoveBlock(block)
                block.taskId != null -> onOpenTask(block.taskId)
            }
        },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${minutesText(block.startMinute)}–${minutesText(block.endMinute)}",
                Modifier.width(82.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                block.publicLabel() ?: "在忙",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isPrivate) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (mine) "你" else memberName(state, block.memberId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

