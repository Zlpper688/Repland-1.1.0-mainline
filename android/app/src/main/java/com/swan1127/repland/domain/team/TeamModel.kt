package com.swan1127.repland.domain.team

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 团队协作域核心模型（TEAM_COLLAB_DESIGN.md §3/§4/§8/§10）。
 * 纯 Kotlin、无网络依赖；确定性调度内核（§5）与 Agent 层（§6）共用这一套词汇。
 */

// ---------- 权限控制台（§10）：队长默认全权限；队员按人配置；头衔只是标签 ----------

enum class TeamPermission(val label: String) {
    EDIT_BASELINE("改基线与截止"),
    SCHEDULE_TRANSFER("排期与转派"),
    CREATE_TASK("创建任务"),
    MANAGE_MEMBERS("邀请移除成员"),
    SCHEDULE_MEETING("发起会议排期"),
    EDIT_LEDGER("台账编辑"),
    FINANCE_BOOKKEEPING("财务记账"),
    MEMORY_WRITE("记忆库写入"),
    ORGANIZE_TIMELINE("晾衣架内容整理"),
    WEEKLY_REPORT_TOGGLE("周报开关"),
    MANAGE_EXTERNAL_LINK("文件外链管理"),
    DISSOLVE_ARCHIVE("解散归档"),
}

val CAPTAIN_PERMISSIONS: Set<TeamPermission> = TeamPermission.entries.toSet()

// ---------- 比赛档案卡（§3.1）：六组预设槽位 + 补充区；unknown 折叠 ----------

enum class ProfileSlotGroup(val label: String) {
    BASIC_INFO("基本信息"),
    TIMELINE("时间节点"),
    QUOTA("组队与资格"),
    DELIVERABLES("提交物"),
    REVIEW("评审与规则"),
    RESOURCES("资源与其他"),
}

data class ProfileField(
    val key: String,
    val label: String,
    val value: String? = null,
) {
    val isUnknown: Boolean get() = value.isNullOrBlank()
}

data class ExtraEntry(
    val key: String,
    val value: String,
)

data class CompetitionProfileCard(
    val competitionName: String,
    val fields: Map<ProfileSlotGroup, List<ProfileField>>,
    val extras: List<ExtraEntry> = emptyList(),
) {
    fun visibleFields(group: ProfileSlotGroup): List<ProfileField> =
        fields[group].orEmpty().filterNot { it.isUnknown }

    /** 关键槽位缺失 → 「待补充」引导（报名截止、人数上限）。 */
    fun missingCritical(): List<String> = buildList {
        if (field("signup_deadline").isNullOrBlank()) add("报名截止")
        if (field("member_limit").isNullOrBlank()) add("人数上限")
    }

    fun field(key: String): String? =
        fields.values.flatten().firstOrNull { it.key == key }?.value

    fun withField(group: ProfileSlotGroup, key: String, label: String, value: String): CompetitionProfileCard {
        val groupFields = fields[group].orEmpty()
        val updated = if (groupFields.any { it.key == key }) {
            groupFields.map { if (it.key == key) it.copy(value = value) else it }
        } else {
            groupFields + ProfileField(key, label, value)
        }
        return copy(fields = fields + (group to updated))
    }

    /** 补充区键值对（数模赛题号、器件清单等单一赛事特有参数）。 */
    fun withExtra(entry: ExtraEntry): CompetitionProfileCard =
        copy(extras = extras.filterNot { it.key == entry.key } + entry)

    /** 升级机制：补充区键手动升级为正式槽位并回填本卡。 */
    fun promoteExtraToSlot(group: ProfileSlotGroup, key: String, label: String): CompetitionProfileCard {
        val entry = extras.firstOrNull { it.key == key } ?: return this
        return withField(group, key, label, entry.value).copy(extras = extras.filterNot { it.key == key })
    }
}

// ---------- 队员卡（§3.2）：负责说明（项目维度）与画像（人维度）严格分离 ----------

/** 课表未导入 → 照排但标低置信度，保守排大段空闲（§5）。 */
enum class ScheduleConfidence { HIGH, LOW }

data class FreeWindow(val startMinute: Int, val endMinute: Int) {
    init {
        require(endMinute > startMinute) { "空闲窗口起点不小于终点：$startMinute-$endMinute" }
    }
    val minutes: Int get() = endMinute - startMinute
    fun overlaps(other: FreeWindow): Boolean = startMinute < other.endMinute && other.endMinute > startMinute
}

data class MemberCard(
    val memberId: String,
    val name: String,
    val isCaptain: Boolean = false,
    val roleTag: String? = null,
    val responsibility: String = "",
    val profileRef: String? = null,
    val permissions: Set<TeamPermission> = emptySet(),
    val weeklyAvailability: Map<DayOfWeek, List<FreeWindow>> = emptyMap(),
    val scheduleConfidence: ScheduleConfidence = ScheduleConfidence.HIGH,
    val dailyCapacityHours: Double = 3.0,
) {
    val canSchedule: Boolean get() = TeamPermission.SCHEDULE_TRANSFER in permissions

    /** 课表未导入（或显式标 LOW）→ 照排但标低置信度，保守排大段空闲（§5 自动降级）。 */
    val lowConfidence: Boolean
        get() = scheduleConfidence == ScheduleConfidence.LOW || weeklyAvailability.isEmpty()
    fun hasPermission(permission: TeamPermission): Boolean = permission in permissions

    /** 适配权重输入：负责说明命中任务标题片段（确定性规则，画像只作跨项目佐证）。 */
    fun affinityWith(taskTitle: String): Int {
        if (responsibility.isBlank() || taskTitle.isBlank()) return 0
        var hit = 0
        var start = 0
        while (start + 2 <= taskTitle.length) {
            val token = taskTitle.substring(start, start + 2)
            if (responsibility.contains(token)) hit += 1
            start += 1
        }
        return hit
    }
}

// ---------- 任务体系（§4）：三型 + MoSCoW 内化为「取消」 + 依赖边 ----------

/** 长期任务 = 竞赛本身；中期任务 = 挂阶段；短期任务 = 会议类。 */
enum class TaskTier(val label: String) {
    LONG_TERM("长期"), MID_TERM("中期"), SHORT_TERM("短期"),
}

/** 建基线时定级（任务属性）；取消 = 降为 WONT（划线可撤销），MUST 不可取消。 */
enum class TaskRank(val label: String) {
    MUST("必须"), SHOULD("应该"), COULD("可以"), WONT("不做"),
}

enum class TeamTaskStatus(val label: String) {
    NOT_STARTED("未开始"), IN_PROGRESS("进行中"), DONE("已完成"), WONT("已取消"),
}

/**
 * 环节内的关键时点：相对环节开始日的偏移；[hard] 表示错过就回不来（报名截止、定题、提交、答辩）。
 * 普通检查点只是提醒，硬时点会进入关卡体检与提醒。
 */
data class StageMoment(
    val label: String,
    val offsetDays: Int = 0,
    val hard: Boolean = false,
)

/**
 * 阶段 = 比赛里的一个环节（报名/准备/攻关/提交/答辩…）。
 * [kind] 来自环节模板，可空字段全部有缺省值，旧存档无需迁移。
 * [isGate] 关卡环节（提交、答辩）过了才算进入下一段，排期与风险都按更严的余量看。
 */
data class Phase(
    val phaseId: String,
    val name: String,
    val start: LocalDate,
    val end: LocalDate,
    val kind: StageKind = StageKind.CUSTOM,
    val deliverables: List<String> = emptyList(),
    val checkpoints: List<String> = emptyList(),
    /** 环节内的关键时点（报名截止、定题、初稿齐…），硬时点不能滑。 */
    val moments: List<StageMoment> = emptyList(),
    val isGate: Boolean = false,
) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    fun momentDate(moment: StageMoment): LocalDate {
        val raw = start.plusDays(moment.offsetDays.toLong())
        return when {
            raw.isBefore(start) -> start
            raw.isAfter(end) -> end
            else -> raw
        }
    }
}

data class TeamTask(
    val taskId: String,
    val title: String,
    val tier: TaskTier,
    val phaseId: String? = null,
    val estimatedHours: Double,
    val rank: TaskRank = TaskRank.SHOULD,
    val status: TeamTaskStatus = TeamTaskStatus.NOT_STARTED,
    val assignedTo: String? = null,
    val dependsOn: List<String> = emptyList(),
    val hardDeadline: LocalDate? = null,
    val deadlineHour: Int? = null,
    val computedDeadline: LocalDate? = null,
    val isBaseline: Boolean = true,
    val createdBy: String = "",
) {
    val active: Boolean get() = status == TeamTaskStatus.NOT_STARTED || status == TeamTaskStatus.IN_PROGRESS
    val cancellable: Boolean get() = rank != TaskRank.MUST && status != TeamTaskStatus.WONT
    fun effectiveDeadline(phases: List<Phase>, fallback: LocalDate?): LocalDate? =
        hardDeadline ?: computedDeadline ?: phaseId?.let { id -> phases.firstOrNull { it.phaseId == id }?.end } ?: fallback
}

// ---------- 个人日程是唯一真相源（§5）：所有队伍的安排落到同一份个人日程 ----------

enum class BlockVisibility { TEAM_VISIBLE, PRIVATE_BUSY }

data class PersonalBlock(
    val blockId: String,
    val memberId: String,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val sourceTeamId: String?,
    val taskId: String?,
    val visibility: BlockVisibility = BlockVisibility.TEAM_VISIBLE,
    val label: String? = null,
    /** 本地合成的个人忙碌块：既挡住排期，也占用当天容量；课表类只挡不占。 */
    val countsTowardCapacity: Boolean = false,
) {
    fun overlaps(other: PersonalBlock): Boolean =
        date == other.date && startMinute < other.endMinute && other.endMinute > startMinute

    /** 私有时间 = 「在忙」占位：时间可见，内容任何人含队长不可见（§8）。 */
    fun publicLabel(): String? = if (visibility == BlockVisibility.PRIVATE_BUSY) null else label

    fun asWindow(): FreeWindow = FreeWindow(startMinute, endMinute)
}

/** 跨队冲突块：按优先级 + 先到先得，冲突标红由本人裁决（§5）。 */
data class ConflictMark(
    val placement: Placement,
    val competingWith: List<PersonalBlock>,
)

data class Placement(
    val taskId: String,
    val memberId: String,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val lowConfidence: Boolean = false,
    val conflicts: List<PersonalBlock> = emptyList(),
) {
    val minutes: Int get() = endMinute - startMinute
}

data class MeetingSlot(
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val attendeeIds: List<String>,
)

/** 多天会议建议：分数越高越容易被接受（下午 > 上午 > 晚上），[freeMinutes] 是当天共同空闲的宽度。 */
data class MeetingSuggestion(
    val slot: MeetingSlot,
    val score: Int,
    val freeMinutes: Int,
)

data class TeamPlanDraft(
    val placements: List<Placement>,
    val unassigned: List<String> = emptyList(),
    val meetingCandidates: List<MeetingSlot> = emptyList(),
    val notes: List<String> = emptyList(),
)
