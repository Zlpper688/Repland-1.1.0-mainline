package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 队伍聚合根：一队伍绑定一个比赛基线（§16）。
 * 个人日程块集合（blocks）是唯一真相源；本队排期、其他队伍排期、个人固定占用都落在同一个人的块序列里。
 */
data class Objection(
    val objectionId: String,
    val taskId: String,
    val raisedBy: String,
    val reason: String,
    val at: LocalDateTime,
    val resolution: ObjectionResolution = ObjectionResolution.PENDING,
    val resolvedBy: String? = null,
)

enum class ObjectionResolution(val label: String) { PENDING("待裁决"), UPHELD("维持转派"), WITHDRAWN("收回转派") }

/** 通知分级（§16）：需要行动的 → 推送+飞书双通道；纯状态变更 → 仅 App 内。 */
enum class NotificationChannel { APP_ONLY, PUSH_AND_FEISHU }

/**
 * 飞书卡片模板种类（T4c，与后端 CardBuilder / CardActionService 契约对齐）：
 * 待确认草案 / 异议裁决（§16 需要行动的双通道通知）。
 */
const val CARD_DRAFT_CONFIRM = "draft_confirm"
const val CARD_TRANSFER_ADJUDICATION = "transfer_adjudication"

/** 卡片元数据（T4c）：随 PUSH_AND_FEISHU 通知上送后端构造 interactive 卡片；null = 纯文本。 */
data class NotificationCardRef(
    /** [CARD_DRAFT_CONFIRM] | [CARD_TRANSFER_ADJUDICATION] */
    val cardKind: String,
    /** 草案 id；裁决卡承载 objectionId（对齐后端 value 契约 draftId 槽位） */
    val refId: String,
    val revision: Int = 1,
)

data class TeamNotification(
    val notificationId: String,
    val channel: NotificationChannel,
    val audience: String,
    val message: String,
    val at: LocalDateTime,
    val card: NotificationCardRef? = null,
)

data class TransferRecord(
    val transferId: String,
    val taskId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val byMemberId: String,
    val at: LocalDateTime,
    val objectionId: String?,
)

class TeamCollabState(
    val teamId: String,
    val teamName: String,
    val competitionDeadline: LocalDate?,
    val now: () -> LocalDateTime = LocalDateTime::now,
) {
    var profileCard: CompetitionProfileCard? = null
        private set
    /** 队伍大局与队规：保守默认恒在，队长可开关策略、补自定义规矩；注入全队提示词顶部。 */
    var charter: TeamCharter = TeamCharter()
        internal set
    val members = mutableListOf<MemberCard>()
    val phases = mutableListOf<Phase>()
    val tasks = mutableListOf<TeamTask>()
    /** 个人日程块（唯一真相源）：含本队排期、跨队占用、课表与个人固定占用。 */
    val blocks = mutableListOf<PersonalBlock>()
    val ledger = mutableListOf<LedgerEntry>()
    val objections = mutableListOf<Objection>()
    val transfers = mutableListOf<TransferRecord>()
    val notifications = mutableListOf<TeamNotification>()
    /** 团队域记忆（§12 T5）：档案要点/负责说明/会议结论，全队可读，写入受「记忆库写入」权限门控。 */
    val memories = mutableListOf<TeamMemoryEntry>()
    /** 参赛归档（§12/§16 T5）：true = 队伍只读；归档产物沉淀进个人域（不随队伍快照存档）。 */
    var archived: Boolean = false
        internal set
    var archivedAt: LocalDateTime? = null
        internal set

    private fun id(prefix: String) = "$prefix-" + UUID.randomUUID().toString().take(8)
    private fun nowAt(): LocalDateTime = now()

    // ---------- 建队与档案卡（§3/§16） ----------

    fun addMember(card: MemberCard) {
        guardMutable()
        require(members.none { it.memberId == card.memberId }) { "成员 ${card.memberId} 已在队" }
        members += if (card.isCaptain) card.copy(permissions = CAPTAIN_PERMISSIONS) else card
    }

    /** 队员凭队伍码自助加入：动作由本人发起，不需要队内管理权限；新成员默认无权限。 */
    fun joinAsMember(name: String): MemberCard {
        guardMutable()
        val trimmed = name.trim().ifBlank { "队员" }
        require(members.none { it.name == trimmed }) { "队伍里已经有「$trimmed」了" }
        val card = MemberCard(id("m"), trimmed, roleTag = "队员")
        members += card
        notifyAction("${card.name} 加入了队伍", audience = "队长")
        return card
    }

    fun adoptProfileCard(card: CompetitionProfileCard) {
        guardMutable()
        profileCard = card
    }

    /** 队伍大局只有队长能改；改完通知全员，让每个人知道约束自己的规矩变了。 */
    fun updateCharter(actorId: String, newCharter: TeamCharter) {
        guardMutable()
        check(member(actorId).isCaptain) { "只有队长能调整队伍大局与队规" }
        charter = newCharter
        notifyAction(
            "队伍大局已更新：${newCharter.activeRules.size} 条队规生效" +
                if (newCharter.goal.isNotBlank()) "，目标「${newCharter.goal}」" else "",
            audience = "全员",
        )
    }

    // ---------- 基线（§2 硬约束层）：阶段 + 任务包 + 依赖边，队长锁定 ----------

    fun setBaseline(actorId: String, newPhases: List<Phase>, newTasks: List<TeamTask>) {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        phases.clear(); phases += newPhases
        tasks.clear(); tasks += newTasks
        notifyAction("基线已锁定：${newTasks.size} 项任务、${newPhases.size} 个阶段", audience = "全员")
    }

    fun cancelTask(actorId: String, taskId: String) {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        val task = task(taskId)
        check(task.cancellable) { "「${task.title}」是 MUST 级任务，不可取消；请调整基线" }
        val from = task.status
        updateTask(task.copy(status = TeamTaskStatus.WONT))
        ledger += TeamLedger.recordStatusChange({ id("led") }, task, actorId, from, TeamTaskStatus.WONT, nowAt())
        notifyAction("「${task.title}」已取消（可撤销）", audience = "全员")
    }

    // ---------- 排期（§5）：排进某人的某天时段，落个人日程 ----------

    fun scheduleInput(startDate: LocalDate): TeamScheduler.ScheduleInput = TeamScheduler.ScheduleInput(
        teamId = teamId,
        tasks = tasks.toList(),
        members = members.toList(),
        phases = phases.toList(),
        existingBlocks = blocks.toList(),
        startDate = startDate,
        competitionDeadline = competitionDeadline,
    )

    fun commitDraft(actorId: String, draft: TeamPlanDraft, replacesTaskIds: Set<String> = emptySet()) {
        guardMutable()
        requirePermission(actorId, TeamPermission.SCHEDULE_TRANSFER)
        val taskIds = tasks.map { it.taskId }.toSet()
        draft.placements.forEach { placement ->
            check(placement.taskId in taskIds) { "草案包含未知任务 ${placement.taskId}" }
        }
        // 重排：被重排任务的旧计划块先作废，再落新草案（个人日程唯一真相源）。
        blocks.removeAll { it.sourceTeamId == teamId && it.taskId in replacesTaskIds }
        draft.placements.forEach { placement ->
            // 定人写回：确认排期即确认分工（§5 负载定人）
            val task = tasks.firstOrNull { it.taskId == placement.taskId }
            if (task != null && task.assignedTo != placement.memberId) {
                updateTask(task.copy(assignedTo = placement.memberId))
            }
            blocks += PersonalBlock(
                blockId = id("blk"),
                memberId = placement.memberId,
                date = placement.date,
                startMinute = placement.startMinute,
                endMinute = placement.endMinute,
                sourceTeamId = teamId,
                taskId = placement.taskId,
                label = tasks.firstOrNull { it.taskId == placement.taskId }?.title,
            )
        }
        draft.unassigned.forEach { taskId ->
            notifyAction("「${task(taskId).title}」未排进本期，进入待安排", audience = "队长")
        }
        notifyAction("排期草案已生效：${draft.placements.size} 段安排", audience = "全员")
    }

    /** 队员改自己的时间/时长：只允许动自己的块，且只能在自己的空闲内（§5）。 */
    fun moveOwnBlock(actorId: String, blockId: String, newStartMinute: Int) {
        guardMutable()
        val block = blocks.firstOrNull { it.blockId == blockId }
            ?: error("找不到时间块 $blockId")
        check(block.memberId == actorId) { "只能修改自己的时间块" }
        check(block.sourceTeamId == teamId) { "该时间块不属于本队" }
        val member = member(actorId)
        val window = member.weeklyAvailability[block.date.dayOfWeek].orEmpty()
        val fits = window.any { newStartMinute >= it.startMinute && newStartMinute + (block.endMinute - block.startMinute) <= it.endMinute }
        check(fits) { "新时段不在你 ${block.date} 的空闲窗口内" }
        blocks.removeAll { it.blockId == blockId }
        blocks += block.copy(startMinute = newStartMinute, endMinute = newStartMinute + (block.endMinute - block.startMinute))
    }

    // ---------- 转派与异议（§7）：自动生效 + 一键异议；基线责任与执行流转分离、双线留痕 ----------

    fun transfer(actorId: String, taskId: String, toMemberId: String): TransferRecord {
        guardMutable()
        requirePermission(actorId, TeamPermission.SCHEDULE_TRANSFER)
        val task = task(taskId)
        member(toMemberId)
        val from = task.assignedTo
        updateTask(task.copy(assignedTo = toMemberId))
        val record = TransferRecord(id("xfer"), taskId, from ?: actorId, toMemberId, actorId, nowAt(), objectionId = null)
        transfers += record
        ledger += LedgerEntry(
            entryId = id("led"), kind = LedgerKind.TRANSFER, taskId = taskId,
            memberId = actorId, at = nowAt(),
            evidence = "「${task.title}」执行转派：${from ?: "未分配"} → $toMemberId（基线责任不变）",
            isBaseline = task.isBaseline,
        )
        notifyAction("「${task.title}」已转派给 ${member(toMemberId).name}，可一键异议", audience = toMemberId, channel = NotificationChannel.PUSH_AND_FEISHU)
        return record
    }

    fun raiseObjection(actorId: String, taskId: String, reason: String): Objection {
        guardMutable()
        val record = transfers.lastOrNull { it.taskId == taskId && it.toMemberId == actorId }
            ?: error("你只能对转派给你的任务提异议")
        check(objections.none { it.transferKey() == record.transferId && it.resolution == ObjectionResolution.PENDING }) {
            "该转派已有待裁决的异议"
        }
        val objection = Objection(id("obj"), taskId, actorId, reason, nowAt())
        objections += objection
        val updated = record.copy(objectionId = objection.objectionId)
        transfers.remove(record); transfers += updated
        ledger += LedgerEntry(
            entryId = id("led"), kind = LedgerKind.OBJECTION, taskId = taskId,
            memberId = actorId, at = objection.at,
            evidence = "对转派提出异议：$reason",
            isBaseline = task(taskId).isBaseline,
        )
        notifyAction(
            "新异议待裁决：「${task(taskId).title}」",
            audience = "队长",
            channel = NotificationChannel.PUSH_AND_FEISHU,
            // T4c：裁决卡 —— 按钮 value 把 objectionId 放进 draftId 槽位（CardActionService 契约）
            card = NotificationCardRef(CARD_TRANSFER_ADJUDICATION, objection.objectionId),
        )
        return objection
    }

    fun resolveObjection(actorId: String, objectionId: String, keepTransfer: Boolean) {
        guardMutable()
        requirePermission(actorId, TeamPermission.SCHEDULE_TRANSFER)
        val objection = objections.firstOrNull { it.objectionId == objectionId }
            ?: error("找不到异议 $objectionId")
        check(objection.resolution == ObjectionResolution.PENDING) { "异议已裁决" }
        val resolution = if (keepTransfer) ObjectionResolution.UPHELD else ObjectionResolution.WITHDRAWN
        objections.removeAll { it.objectionId == objectionId }
        objections += objection.copy(resolution = resolution, resolvedBy = actorId)
        if (!keepTransfer) {
            val record = transfers.lastOrNull { it.objectionId == objectionId } ?: return
            val task = task(record.taskId)
            updateTask(task.copy(assignedTo = record.fromMemberId))
        }
        ledger += LedgerEntry(
            entryId = id("led"), kind = LedgerKind.OBJECTION, taskId = objection.taskId,
            memberId = actorId, at = nowAt(),
            evidence = "异议裁决：${resolution.label}",
            isBaseline = task(objection.taskId).isBaseline,
        )
    }

    fun openObjections(): List<Objection> = objections.filter { it.resolution == ObjectionResolution.PENDING }

    private fun Objection.transferKey(): String =
        transfers.lastOrNull { it.objectionId == objectionId }?.transferId ?: objectionId

    // ---------- 台账（§9）：客观留证 ----------

    fun addExternalLink(actorId: String, taskId: String, url: String) {
        guardMutable()
        requirePermission(actorId, TeamPermission.MANAGE_EXTERNAL_LINK)
        ledger += LedgerEntry(
            entryId = id("led"), kind = LedgerKind.EXTERNAL_LINK, taskId = taskId,
            memberId = actorId, at = nowAt(), evidence = "作品外链", externalUrl = url,
            isBaseline = task(taskId).isBaseline,
        )
    }

    fun recordFinance(actorId: String, amountCents: Long, reason: String): FinanceRecord {
        guardMutable()
        requirePermission(actorId, TeamPermission.FINANCE_BOOKKEEPING)
        val record = FinanceRecord(id("fin"), actorId, nowAt(), amountCents, reason)
        ledger += LedgerEntry(
            entryId = id("led"), kind = LedgerKind.FINANCE, taskId = null,
            memberId = actorId, at = record.at, evidence = "¥${amountCents / 100.0}：$reason",
            amountCents = amountCents, isBaseline = true,
        )
        return record
    }

    // ---------- 周报（§11）：只写项目进展，不对个人作评价 ----------

    fun weeklyReport(weekStart: LocalDate): WeeklyReportDraft = TeamLedger.weeklyReport(
        tasks = tasks.toList(),
        entries = ledger.toList(),
        weekStart = weekStart,
        weekEnd = weekStart.plusDays(6),
        openObjections = openObjections(),
        overdueTaskIds = tasks.filter { task ->
            task.active && task.computedDeadline != null && task.computedDeadline!!.isBefore(weekStart)
        }.map { it.taskId },
    )

    // ---------- 记忆库（§12 T5 团队域）：全队可读；写入受「记忆库写入」权限门控 ----------

    fun addTeamMemory(actorId: String, kind: TeamMemoryKind, content: String): TeamMemoryEntry {
        guardMutable()
        requirePermission(actorId, TeamPermission.MEMORY_WRITE)
        val trimmed = content.trim()
        check(trimmed.isNotEmpty()) { "记忆内容不能为空" }
        val entry = TeamMemoryEntry(id("mem"), kind, trimmed, actorId, nowAt())
        memories += entry
        notifyAction("记忆库新增「${kind.label}」：${trimmed.take(20)}", audience = "全员")
        return entry
    }

    fun removeTeamMemory(actorId: String, memoryId: String) {
        guardMutable()
        requirePermission(actorId, TeamPermission.MEMORY_WRITE)
        memories.removeAll { it.memoryId == memoryId }
    }

    // ---------- 参赛归档（§12/§16 T5）：一键归档 → 参赛档案沉淀个人域 → 队伍转只读 ----------

    fun archiveTeam(actorId: String, result: String): ArchiveEntry {
        guardMutable() // 已归档队伍在此被拒：归档不可重复
        requirePermission(actorId, TeamPermission.DISSOLVE_ARCHIVE)
        val trimmed = result.trim()
        check(trimmed.isNotEmpty()) { "归档前请填写比赛成绩" }
        val links = ledger.mapNotNull { it.externalUrl?.takeIf(String::isNotBlank) }.distinct()
        val entry = ArchiveEntry(
            archiveId = id("arc"),
            teamId = teamId,
            teamName = teamName,
            competitionName = profileCard?.competitionName,
            result = trimmed,
            archivedAt = nowAt(),
            archivedBy = actorId,
            stats = mapOf(
                "members" to members.size,
                "tasks" to tasks.size,
                "tasksDone" to tasks.count { it.status == TeamTaskStatus.DONE },
                "ledger" to ledger.size,
                "externalLinks" to links.size,
            ),
            finalLedger = ledger.sortedBy { it.at }.toList(),
            workLinks = links,
        )
        archived = true
        archivedAt = entry.archivedAt
        notifyAction("队伍已归档转只读；参赛档案已沉淀进个人记忆域", audience = "全员", channel = NotificationChannel.PUSH_AND_FEISHU)
        return entry
    }

    // ---------- 权限校验的写入口（手动路径与 AI 工具共用；旧方法保留给建队/编解码/测试） ----------

    /** 建任务：需「创建任务」或「改基线」权限；AI 与手动共用同一道门。 */
    fun addTaskBy(actorId: String, task: TeamTask) {
        guardMutable()
        val actor = member(actorId)
        check(actor.hasPermission(TeamPermission.CREATE_TASK) || actor.hasPermission(TeamPermission.EDIT_BASELINE)) {
            "${actor.name} 没有「创建任务」权限：请队长或有权限成员来建"
        }
        check(tasks.none { it.taskId == task.taskId }) { "任务 ${task.taskId} 已存在" }
        tasks += task
        notifyAction("新增任务「${task.title}」", audience = "全员")
    }

    /** 改任务本身（标题/工时/等级/阶段）：基线动作，需「改基线」权限。 */
    fun editTask(actorId: String, updated: TeamTask) {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        val current = task(updated.taskId)
        check(updated.status != TeamTaskStatus.WONT || current.status == TeamTaskStatus.WONT || current.cancellable) {
            "「${current.title}」是 MUST 级任务，不能通过改任务取消；请调整基线"
        }
        updateTask(updated)
        notifyAction("「${updated.title}」已更新", audience = "全员")
    }

    /** 截止日期设定（§7）：超过比赛截止直接拦截，不写仓库。 */
    fun setDeadlineBy(actorId: String, taskId: String, date: LocalDate, hour: Int?) {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        val task = task(taskId)
        check(competitionDeadline == null || !date.isAfter(competitionDeadline)) {
            "截止 $date 超过比赛截止 $competitionDeadline，请重新选择"
        }
        updateTask(task.copy(hardDeadline = date, deadlineHour = hour))
    }

    fun addPhaseBy(actorId: String, phase: Phase) {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        check(!phase.end.isBefore(phase.start)) { "阶段「${phase.name}」结束不能早于开始" }
        check(phases.none { it.phaseId == phase.phaseId }) { "阶段 ${phase.phaseId} 已存在" }
        phases += phase
        notifyAction("新增阶段「${phase.name}」", audience = "全员")
    }

    /** 套用环节模板的结果：能套的先套上，套不上的逐条说明，不整批回滚。 */
    data class StagePlanApplyResult(
        val addedPhases: List<Phase>,
        val addedTasks: List<TeamTask>,
        val skipped: List<String>,
    )

    /** 套用环节模板：一次写入整条赛段与基线任务，只留一条通知，不刷屏。 */
    fun applyStagePlan(actorId: String, label: String, newPhases: List<Phase>, newTasks: List<TeamTask>): StagePlanApplyResult {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        val skipped = mutableListOf<String>()
        val phaseIds = phases.mapTo(HashSet()) { it.phaseId }
        val acceptedPhases = newPhases.mapNotNull { phase ->
            when {
                phase.end.isBefore(phase.start) -> {
                    skipped += "阶段「${phase.name}」结束早于开始"
                    null
                }
                phase.phaseId in phaseIds -> {
                    skipped += "阶段「${phase.name}」编号 ${phase.phaseId} 已存在"
                    null
                }
                else -> {
                    phaseIds += phase.phaseId
                    phase
                }
            }
        }
        val taskIds = tasks.mapTo(HashSet()) { it.taskId }
        val acceptedTasks = newTasks.mapNotNull { task ->
            when {
                task.taskId in taskIds -> {
                    skipped += "任务「${task.title}」编号 ${task.taskId} 已存在"
                    null
                }
                task.phaseId != null && task.phaseId !in phaseIds -> {
                    skipped += "任务「${task.title}」挂的阶段 ${task.phaseId} 不存在"
                    null
                }
                else -> {
                    taskIds += task.taskId
                    task
                }
            }
        }
        if (acceptedPhases.isEmpty() && acceptedTasks.isEmpty() && skipped.isEmpty()) {
            return StagePlanApplyResult(emptyList(), emptyList(), emptyList())
        }
        phases += acceptedPhases
        tasks += acceptedTasks
        val message = buildString {
            append("套用「$label」：${acceptedPhases.size} 个环节、${acceptedTasks.size} 项基线任务")
            if (skipped.isNotEmpty()) append("，跳过 ${skipped.size} 项（${skipped.take(2).joinToString("；")}）")
        }
        notifyAction(message, audience = "全员")
        return StagePlanApplyResult(acceptedPhases, acceptedTasks, skipped)
    }

    /** 负责说明（§3.2）：队长和本人可改，双向可见；换项目就换。 */
    fun updateResponsibility(actorId: String, memberId: String, text: String) {
        guardMutable()
        val actor = member(actorId)
        check(actor.isCaptain || actor.memberId == memberId) {
            "只能改自己的负责说明；替他人修改需队长操作"
        }
        val index = members.indexOfFirst { it.memberId == memberId }
        check(index >= 0) { "找不到成员 $memberId" }
        members[index] = members[index].copy(responsibility = text.trim())
    }

    /** 资料卡是个人空闲的源：保存时同步给队伍，排期和「队里每个人的空闲」读同一份。 */
    fun syncMemberAvailability(
        actorId: String,
        memberId: String,
        availability: Map<DayOfWeek, List<FreeWindow>>,
        dailyCapacityHours: Double,
    ) {
        guardMutable()
        val actor = member(actorId)
        check(actor.memberId == memberId) { "只能同步自己的空闲" }
        val index = members.indexOfFirst { it.memberId == memberId }
        check(index >= 0) { "找不到成员 $memberId" }
        val card = members[index]
        members[index] = card.copy(
            weeklyAvailability = availability,
            dailyCapacityHours = dailyCapacityHours.coerceIn(0.5, 16.0),
            scheduleConfidence = if (availability.isNotEmpty()) ScheduleConfidence.HIGH else card.scheduleConfidence,
        )
    }

    /** 权限控制台（§10）：只有队长能按人授予/收回；头衔只是标签。 */
    fun setMemberPermission(actorId: String, memberId: String, permission: TeamPermission, enabled: Boolean) {
        guardMutable()
        val actor = member(actorId)
        check(actor.isCaptain) { "只有队长能调整成员权限（§10）" }
        val index = members.indexOfFirst { it.memberId == memberId }
        check(index >= 0) { "找不到成员 $memberId" }
        val card = members[index]
        check(!card.isCaptain) { "队长默认全权限，无需配置" }
        val next = if (enabled) card.permissions + permission else card.permissions - permission
        members[index] = card.copy(permissions = next)
        notifyAction(
            "${card.name} 的「${permission.label}」${if (enabled) "已授予" else "已收回"}",
            audience = memberId,
            channel = NotificationChannel.PUSH_AND_FEISHU,
        )
    }

    /** 档案卡整体更新（建档 / AI 结构化提取后写入）：基线动作。 */
    fun updateProfileCard(actorId: String, card: CompetitionProfileCard) {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        profileCard = card
    }

    /** 加队员（§16）：需「邀请移除成员」权限；新成员默认无权限，由队长按人配置。 */
    fun addMemberBy(actorId: String, card: MemberCard) {
        guardMutable()
        requirePermission(actorId, TeamPermission.MANAGE_MEMBERS)
        check(card.name.isNotBlank()) { "队员名字不能为空" }
        check(members.none { it.memberId == card.memberId }) { "成员 ${card.memberId} 已在队" }
        members += card.copy(isCaptain = false)
        notifyAction("${card.name} 已加入队伍", audience = "全员")
    }

    /**
     * 批量邀请：一次校验权限，重名（含队长）跳过；只发一条汇总通知，避免一次导入刷一屏。
     * 返回实际加入人数，供上层如实回执。
     */
    fun addMembersBy(actorId: String, cards: List<MemberCard>): Int {
        guardMutable()
        requirePermission(actorId, TeamPermission.MANAGE_MEMBERS)
        val ids = members.mapTo(mutableSetOf()) { it.memberId }
        val names = members.mapTo(mutableSetOf()) { it.name.trim() }
        val added = mutableListOf<MemberCard>()
        cards.forEach { card ->
            val name = card.name.trim()
            if (name.isEmpty() || !ids.add(card.memberId) || !names.add(name)) return@forEach
            added += card.copy(name = name, isCaptain = false)
        }
        if (added.isNotEmpty()) {
            members += added
            notifyAction(
                "已邀请 ${added.size} 名队员：${added.joinToString("、") { it.name }}",
                audience = "全员",
                channel = NotificationChannel.PUSH_AND_FEISHU,
            )
        }
        return added.size
    }

    /** 退队（§16）：名下任务自动转派给队长，台账永久留痕不删。 */
    fun removeMemberBy(actorId: String, memberId: String) {
        guardMutable()
        requirePermission(actorId, TeamPermission.MANAGE_MEMBERS)
        val actor = member(actorId)
        val card = member(memberId)
        check(!card.isCaptain) { "队长不能移除自己；如需结束请用归档" }
        check(memberId != actorId) { "不能移除自己" }
        val captain = members.firstOrNull { it.isCaptain }
        val at = nowAt()
        val active = tasks.filter { it.assignedTo == memberId && it.active }
        active.forEach { task ->
            updateTask(task.copy(assignedTo = captain?.memberId))
            transfers += TransferRecord(id("xfer"), task.taskId, memberId, captain?.memberId.orEmpty(), actorId, at, objectionId = null)
            ledger += LedgerEntry(
                entryId = id("led"), kind = LedgerKind.TRANSFER, taskId = task.taskId,
                memberId = actorId, at = at,
                evidence = "成员离队「${task.title}」自动转派：${card.name} → ${captain?.name ?: "未分配"}（台账保留）",
                isBaseline = task.isBaseline,
            )
        }
        blocks.removeAll { it.memberId == memberId && it.sourceTeamId == teamId }
        members.removeAll { it.memberId == memberId }
        notifyAction(
            "${card.name} 已离队；名下 ${active.size} 项任务转派给${captain?.name ?: "队长"}，台账留痕",
            audience = "全员",
            channel = NotificationChannel.PUSH_AND_FEISHU,
        )
    }

    fun restoreTaskBy(actorId: String, taskId: String) {
        guardMutable()
        requirePermission(actorId, TeamPermission.EDIT_BASELINE)
        val task = task(taskId)
        check(task.status == TeamTaskStatus.WONT) { "只有已取消的任务可以撤销取消" }
        updateTask(task.copy(status = TeamTaskStatus.NOT_STARTED))
        notifyAction("「${task.title}」取消已撤销，恢复为待办", audience = "全员")
    }

    /**
     * 报告完成（§9）：本人、未分配任务的认领人、队长或有排期权限者可报；
     * 状态流转 + 口语留痕进台账，未来排期块作废（个人日程唯一真相源）。
     * 返回 false 表示任务本来就已经完成，没有重复留痕。
     */
    fun reportDone(actorId: String, taskId: String, note: String? = null, today: LocalDate = nowAt().toLocalDate()): Boolean {
        guardMutable()
        val actor = member(actorId)
        val task = task(taskId)
        if (task.status == TeamTaskStatus.DONE) return false
        check(task.active) { "「${task.title}」状态是 ${task.status.label}，不能报告完成" }
        val allowed = actor.isCaptain || task.assignedTo == null || task.assignedTo == actorId ||
            actor.hasPermission(TeamPermission.SCHEDULE_TRANSFER)
        check(allowed) { "「${task.title}」不在你名下，不能替他人报告完成" }
        val from = task.status
        updateTask(task.copy(status = TeamTaskStatus.DONE, assignedTo = task.assignedTo ?: actorId))
        blocks.removeAll { it.taskId == taskId && it.sourceTeamId == teamId && !it.date.isBefore(today) }
        ledger += TeamLedger.recordStatusChange({ id("led") }, task, actorId, from, TeamTaskStatus.DONE, nowAt())
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            ledger += TeamLedger.recordSaying({ id("led") }, task, actorId, text, nowAt())
        }
        notifyAction("「${task.title}」已报告完成", audience = "全员")
        return true
    }

    // ---------- 内部 ----------

    fun task(taskId: String): TeamTask =
        tasks.firstOrNull { it.taskId == taskId } ?: error("找不到任务 $taskId")

    fun member(memberId: String): MemberCard =
        members.firstOrNull { it.memberId == memberId } ?: error("找不到成员 $memberId")

    fun updateTask(updated: TeamTask) {
        guardMutable()
        val index = tasks.indexOfFirst { it.taskId == updated.taskId }
        check(index >= 0) { "找不到任务 ${updated.taskId}" }
        tasks[index] = updated
    }

    fun requirePermission(actorId: String, permission: TeamPermission) {
        val card = member(actorId)
        check(card.hasPermission(permission)) {
            "${card.name} 没有「${permission.label}」权限：越权请求被拒绝"
        }
    }

    /** 只读守卫（§16 T5）：一键归档后队伍转只读，一切写仓库动作被拒。 */
    private fun guardMutable() {
        check(!archived) { "队伍已归档，已转只读（§16）" }
    }

    private fun notifyAction(
        message: String,
        audience: String,
        channel: NotificationChannel = NotificationChannel.APP_ONLY,
        card: NotificationCardRef? = null,
    ) {
        notifications += TeamNotification(id("ntf"), channel, audience, message, nowAt(), card)
    }

    companion object {
        fun create(
            teamId: String,
            teamName: String,
            competitionDeadline: LocalDate?,
            captain: MemberCard,
            now: () -> LocalDateTime = LocalDateTime::now,
        ): TeamCollabState {
            val state = TeamCollabState(teamId, teamName, competitionDeadline, now)
            state.addMember(captain.copy(isCaptain = true))
            return state
        }
    }
}

/** 队伍码：由 teamId 确定性派生、不另存；队长复制给队员即可在本机加入。 */
fun inviteCodeOf(teamId: String): String =
    teamId.removePrefix("t-").uppercase().filter { it.isLetterOrDigit() }.take(8)

val TeamCollabState.inviteCode: String get() = inviteCodeOf(teamId)

/** 课表工具：未导入课表 → 置信度 LOW；补完自动重排该成员任务（§5）。 */
fun MemberCard.withCourseTable(availability: Map<DayOfWeek, List<FreeWindow>>): MemberCard =
    copy(weeklyAvailability = availability, scheduleConfidence = ScheduleConfidence.HIGH)
