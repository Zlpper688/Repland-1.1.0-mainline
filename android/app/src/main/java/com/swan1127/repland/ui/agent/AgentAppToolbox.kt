package com.swan1127.repland.ui.agent

import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.AgentBatchPreviewRow
import com.swan1127.repland.domain.model.AgentActionResult
import com.swan1127.repland.domain.model.AgentRulePlacement
import com.swan1127.repland.domain.model.AgentToolKind
import com.swan1127.repland.domain.model.AgentToolbox
import com.swan1127.repland.domain.model.AgentToolParam
import com.swan1127.repland.domain.model.AgentToolResult
import com.swan1127.repland.domain.model.AgentToolSpec
import com.swan1127.repland.domain.model.ArrangementCandidate
import com.swan1127.repland.domain.model.ArrangementClarification
import com.swan1127.repland.domain.model.ArrangementPlacementSource
import com.swan1127.repland.domain.model.ArrangementTimeHint
import com.swan1127.repland.domain.model.AutonomousCaptureDate
import com.swan1127.repland.domain.model.ChatDayPlanner
import com.swan1127.repland.domain.model.MemoryReference
import com.swan1127.repland.domain.model.PlannedSegment
import com.swan1127.repland.domain.model.QuickAvailability
import com.swan1127.repland.domain.model.RuleTask
import com.swan1127.repland.domain.model.SchedulingStyle
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskDraft
import com.swan1127.repland.domain.model.TaskFeedback
import com.swan1127.repland.domain.model.TaskName
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.TimelineKind
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.domain.model.WeeklyTimeBlockDraft
import com.swan1127.repland.ui.components.taskDateLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's real operation surface behind the agent tools.
 *
 * Reads answer from data the day screen already holds. Writes never run inside the tool
 * call: [runWrite] only happens after the user taps 确认 on the pending-action card, which
 * is the whole point of the confirmation gate. `plan_tasks` collapses the old
 * create→确认→schedule→再确认 chain into one card: it builds every item, lets
 * [ChatDayPlanner] pick the slots before the card is drawn, and writes both halves when the
 * user confirms. `create_task` records the new ids so `schedule_draft` can still auto-place
 * what this conversation created.
 */
class AgentAppToolbox(
    private val activeDate: LocalDate,
    private val tasksProvider: () -> List<Task>,
    private val occupiedProvider: () -> List<TimelineEntry>,
    private val onSaveTasks: suspend (List<TaskDraft>) -> Boolean,
    private val onStartTask: (String) -> Unit,
    private val onCompleteTask: (taskId: String, content: String?, actualMinutes: Int?, progress: Int?) -> Unit,
    private val onCancelTask: (String) -> Unit,
    private val onPostponeTask: (taskId: String, reason: String?) -> Unit,
    private val onRecordFeedback: (taskId: String, feedback: TaskFeedback) -> Unit,
    /** 返回 null 才是真写进了时间轴；人话失败原因由仓储给，回执不许替它说话。 */
    private val onPlaceTask: suspend (
        taskId: String, date: LocalDate, startMinute: Int, endMinute: Int, trackId: String,
        reminderEnabled: Boolean, leadMinutes: Int?,
    ) -> String?,
    /** 挪动顺带换天时 date 才非空；为空就是原来那天里改时刻。 */
    private val onMovePlacement: suspend (segmentId: String, date: LocalDate?, startMinute: Int, endMinute: Int, trackId: String) -> String?,
    private val onRemovePlacement: (segmentId: String) -> Unit,
    private val onSetSegmentReminder: (segmentId: String, enabled: Boolean, leadMinutes: Int?) -> Unit,
    private val onStartFocus: (taskId: String) -> Unit,
    /** 用户存下来的排期风格；卡上点了另一套时以参数为准。 */
    private val schedulingStyle: () -> SchedulingStyle = { SchedulingStyle.PACKED },
    /**
     * 换天排期得先看到那一天的实况：给不出视图（返回 null）就挡回去，
     * 拿"空的一天"去排，排出来的每一段都是撞车的。
     */
    private val timelineForDate: (LocalDate) -> List<TimelineEntry>? = { null },
    /** 改任务本身（名称、时长、计划日、截止日）；同样只认返回的理由，没接上写入通路就明说。 */
    private val onUpdateTask: suspend (TaskDraft) -> String? = { "任务信息没有接上写入通路，这次没有修改。" },
    /**
     * 某一天登记下来的可用档（空闲模板 + 当日例外）。空列表是「没登记」而不是「全天有空」，
     * 读时间预算时要把这两种情况说成不同的话。
     */
    private val availableRangesForDate: (LocalDate) -> List<IntRange> = { emptyList() },
    /** 沉淀下来的用户习惯、记忆画像和小组结论：提示词里只放了最相关的几条，追问时要能翻到其余的。 */
    private val memoryFacts: () -> List<Pair<String, String>> = { emptyList() },
    private val memoryProfile: () -> MemoryReference = { MemoryReference.empty(activeDate) },
    private val memoryEpisodes: () -> List<String> = { emptyList() },
    /** 登记一档可用时间（空闲或占用）；返回 null 才是真登记上了。 */
    private val onSaveAvailability: suspend (QuickAvailability) -> String? = { "可用档没有接上写入通路，这次没有登记。" },
    /**
     * 每周重复的固定安排（上课、练琴、每晚固定玩一会儿）。[removeIds] 是这次要替换掉的
     * 旧同名安排，[drafts] 是要新增的星期；两半合成一次写入，不会出现"旧的删了新的没写上"。
     */
    private val onSaveRoutine: suspend (removeIds: List<String>, drafts: List<WeeklyTimeBlockDraft>) -> String? =
        { _, _ -> "固定安排没有接上写入通路，这次没有登记。" },
    /** 已有的每周固定块；filter 掉 AVAILABLE 后就是用户可以点名取消的固定安排。 */
    private val weeklyBlocksProvider: () -> List<WeeklyTimeBlock> = { emptyList() },
    /** 已确认计划里的全部落点：规则层改期要知道任务排在哪一天，不能只看今天。 */
    private val segmentsProvider: () -> List<PlannedSegment> = { emptyList() },
) : AgentToolbox {

    private val tasks: List<Task> get() = tasksProvider()
    private val occupiedEntries: List<TimelineEntry> get() = occupiedProvider()

    override fun ruleTasks(): List<RuleTask> =
        tasks.filter { it.status.isActive }.map { task ->
            RuleTask(task.id, task.displayName, task.totalDurationMinutes, task.scheduledForDate)
        }

    override fun rulePlacementOf(taskId: String): AgentRulePlacement? {
        // 计划里同一件事可能分了好几段：改期改的是还没到的那一段，先按日期找最近的；
        // 都过去了才退回最早那一段，总比凭空多排一段强。
        val planned = segmentsProvider()
            .filter { it.taskId == taskId && it.id.isNotBlank() }
            .sortedWith(compareBy(PlannedSegment::date, PlannedSegment::startMinute))
        val upcoming = planned.firstOrNull { !it.date.isBefore(activeDate) } ?: planned.firstOrNull()
        if (upcoming != null) {
            return AgentRulePlacement(upcoming.id, upcoming.date, upcoming.startMinute, upcoming.endMinute)
        }
        val scheduled = tasks.firstOrNull { it.id == taskId }?.scheduledForDate ?: activeDate
        return listOf(activeDate, scheduled).distinct().firstNotNullOfOrNull { day ->
            occupiedOn(day).firstOrNull { it.taskId == taskId }?.let {
                AgentRulePlacement(it.id, it.date, it.startMinute, it.endMinute)
            }
        }
    }

    /** 被点名那一天的已有安排。就是界面那天时直接读现成的视图，不必再算一遍。 */
    private fun occupiedOn(date: LocalDate): List<TimelineEntry> =
        if (date == activeDate) occupiedProvider() else timelineForDate(date).orEmpty()

    /** 引擎每轮开始时交进来的用户原话；钟点、时长、星期没写进参数时从这里回读。 */
    private var currentUtterance: String = ""

    override fun noteUtterance(utterance: String) {
        currentUtterance = utterance
    }

    /** 日期写法说清楚一次，几个工具共用；模型才不会把 2026-10-28 写成 10/28。 */
    private val dateParam = listOf(
        AgentToolParam("date", "日期：2026-10-28、明天、10 月 28 日；不写就是界面正在显示的那天"),
    )

    /** 本轮会话里由工具创建、尚未排期的任务；schedule_draft 只排这些。 */
    internal data class CreatedTask(
        val taskId: String,
        val name: String,
        val durationMinutes: Int?,
        val startHintMinute: Int?,
        val category: TaskCategory,
        val priority: TaskPriority,
    )

    private val createdTasks = mutableListOf<CreatedTask>()
    private val scheduledTaskIds = mutableSetOf<String>()

    init {
        // The sheet can be dismissed and reopened mid-conversation while the protocol session
        // survives in GroupChatSession; a fresh toolbox must re-adopt the ledger or
        // schedule_draft would report 本会话没有待排期 and strand the flow.
        createdTasks += GroupChatSession.createdTasks
        scheduledTaskIds += GroupChatSession.scheduledTaskIds
    }

    private fun persistLedger() {
        GroupChatSession.createdTasks = createdTasks.toList()
        GroupChatSession.scheduledTaskIds = scheduledTaskIds.toSet()
    }

    /** 进程重启后工具箱早于落盘快照创建：把会话账本装回来，接着排之前创建的任务。 */
    internal fun adoptLedger(tasks: List<CreatedTask>, scheduled: Set<String>) {
        createdTasks.clear()
        createdTasks += tasks
        scheduledTaskIds.clear()
        scheduledTaskIds += scheduled
    }

    override fun specs(): List<AgentToolSpec> = listOf(
        AgentToolSpec(
            "query_tasks", "读任务清单", "读取任务列表：编号、名称、状态、时长、截止",
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            "query_schedule", "读某天安排", "读取某一天已有安排：编号、类型、标题、时段；不写 date 就是界面正在显示的那天",
            params = dateParam,
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            "query_free_windows", "读空档", "读取某一天 30 分钟以上的连续空档；不写 date 就是界面正在显示的那天",
            params = dateParam,
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            "query_time_budget", "读时间预算",
            "读取某一天的容量：课程和任务各占掉多少分钟、登记可用档里还剩多少没排上；用户问「今天还排得下吗」「还剩多少时间」时用",
            params = dateParam,
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            "query_routines", "读固定安排",
            "读取每周重复的固定安排：编号、名称、星期几、时段、类型；登记前查重、取消或改时间前核对原名时用",
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            "query_memory", "读记忆",
            "读取沉淀下来的用户习惯、记忆画像和小组结论；用户问「你记得我什么」或要翻更久的习惯时用，可给 keyword 只挑相关的",
            params = listOf(AgentToolParam("keyword", "只挑跟这几个字有关的记录")),
            kind = AgentToolKind.READ,
        ),
        AgentToolSpec(
            "ask_specialist", "咨询成员", "向小组专家提问，回答会作为该成员的发言展示",
            params = listOf(
                AgentToolParam("member", "成员名", required = true, enumValues = listOf("小析", "小刻", "小习", "小盾")),
                AgentToolParam("question", "问题", required = true),
            ),
            kind = AgentToolKind.READ,
        ),
        writeSpec(
            "create_task", "只建不排", "创建一件任务但不排时间；要连着排时间请用 plan_tasks",
            params = listOf(
                AgentToolParam("name", "任务名称", required = true),
                AgentToolParam("durationMinutes", "时长（分钟）"),
                AgentToolParam("start", "开始时间 HH:mm"),
                AgentToolParam("priority", "优先级", enumValues = listOf("REQUIRED", "HIGH", "MEDIUM", "LOW")),
                AgentToolParam("category", "类别", enumValues = listOf("COURSE", "EXTRACURRICULAR", "OFFICE", "LEISURE")),
                AgentToolParam("dueDate", "截止日：2026-10-28、明天；这是期限，不是排进时间轴"),
            ) + dateParam,
            summarize = { args ->
                buildString {
                    append("创建任务「${args.optString("name")}」")
                    args.optIntOrNull("durationMinutes")?.let { append("，$it 分钟") }
                    args.optString("start").takeIf { it.isNotBlank() }?.let { append("，$it 开始") }
                    args.optString("dueDate").trim().takeIf { it.isNotEmpty() }?.let { append("，截止 $it") }
                    dayNote(args)?.let { append(it) }
                }
            },
            validate = { args ->
                when {
                    args.optString("name").isBlank() -> "任务名称不能为空"
                    args.optIntOrNull("durationMinutes")?.let { it !in 5..720 } == true -> "时长需要 5–720 分钟"
                    dateRejected(args) != null -> dateRejected(args)
                    dateRejected(args, "dueDate") != null -> dateRejected(args, "dueDate")
                    args.optString("start").isNotBlank() -> timeRejected(args.opt("start"), "开始时间")
                    else -> null
                }
            },
        ),
        writeSpec(
            "plan_tasks", "批量建与排", "一次处理多件事：没 taskId 的建新任务，有 taskId 的排已有任务；" +
                "落点全部算进一张确认卡，确认一次就写清单并排上时间轴；" +
                "空档用尽时会自动把事项完整放进某个已有安排里当下属事件（同一时间做事），摘要写明挂进了谁；" +
                "date 决定这一整批落在哪一天",
            params = listOf(
                AgentToolParam(
                    "items",
                    "任务数组 JSON：[{name?, taskId?, durationMinutes?, start?, category?, priority?, reminder?, lead?}]，" +
                        "start 用 HH:mm；不写 start 就自动挑空档，不写 durationMinutes 按 60 分钟或任务自带时长；" +
                        "reminder=true 到点响铃、lead=提前多少分钟（只写 lead 没写 reminder 就按响铃处理）",
                    required = true,
                ),
            ) + dateParam,
            summarize = { args -> summarizeBatch(args) },
            validate = { args -> batchRejected(args) },
            draftSlot = "plan",
        ),
        writeSpec(
            "schedule_draft", "自动排期", "把本次会话创建、还没排期的任务自动放进今天的空档；结果会先列出等你确认",
            summarize = { "自动排期（${unscheduledCreated().size} 项待排）" },
            draftSlot = "plan",
        ),
        writeSpec(
            "place_task", "放入时段", "把一个任务放到某天的某个时段（不写 date 就是界面那天）；时段完整落在某个已有安排之内即成为它的下属事件（同一时间做事）；" +
                "reminder=true 让这项安排到点响铃，lead 指定提前多少分钟",
            params = listOf(
                AgentToolParam("taskId", "任务编号", required = true),
                AgentToolParam("startMinute", "开始分钟数或 HH:mm", required = true),
                AgentToolParam("endMinute", "结束分钟数或 HH:mm"),
                AgentToolParam("trackId", "轨道，默认 focus"),
                AgentToolParam("reminder", "true 到点响铃提醒；不写默认不响"),
                AgentToolParam("lead", "提前多少分钟响铃（1–1440）；只写了 lead 没写 reminder 就按响铃处理"),
            ) + dateParam,
            summarize = { args ->
                val start = parseMinute(args.opt("startMinute"))
                "把任务放到 ${start?.let(::formatMinute) ?: "?"} 起" +
                    reminderNote(placeReminderOf(args), leadOf(args)) + dayNote(args).orEmpty()
            },
            validate = { args ->
                val start = parseMinute(args.opt("startMinute"))
                val date = dateOf(args)
                val day = dayRejected(date)
                when {
                    dateRejected(args) != null -> dateRejected(args)
                    day != null -> day
                    start == null -> timeRejected(args.opt("startMinute"), "开始时间")
                    taskRefRejected(args.optString("taskId")) != null -> taskRefRejected(args.optString("taskId"))
                    placeReminderRejected(args) != null -> placeReminderRejected(args)
                    args.opt("endMinute") != null && !args.isNull("endMinute") &&
                        parseMinute(args.opt("endMinute")).let { it == null || it <= start } ->
                        "结束时间要晚于开始时间"
                    else -> {
                        val end = parseMinute(args.opt("endMinute")) ?: (start + (durationOf(resolveTaskReference(args.optString("taskId"))) ?: 60))
                        overlapRejected(start, end.coerceAtMost(1_440), excludeSegmentId = null, date = date)
                    }
                }
            },
        ),
        writeSpec(
            "move_placement", "移动时段", "把已有安排挪到新时间，也可以顺带挪到另一个日子",
            params = listOf(
                AgentToolParam("segmentId", "安排编号（segment:… 的后半段）", required = true),
                AgentToolParam("startMinute", "开始分钟数或 HH:mm", required = true),
                AgentToolParam("endMinute", "结束分钟数或 HH:mm"),
                AgentToolParam("trackId", "轨道，默认 focus"),
            ) + dateParam,
            summarize = { args ->
                "移动 ${args.optString("segmentId")} 到 ${parseMinute(args.opt("startMinute"))?.let(::formatMinute) ?: "?"}" +
                    dayNote(args).orEmpty()
            },
            validate = { args ->
                val start = parseMinute(args.opt("startMinute"))
                val segmentId = args.optString("segmentId")
                val date = dateOf(args)
                val day = dayRejected(date)
                val ref = segmentRefRejected(segmentId, alsoOn = date)
                when {
                    dateRejected(args) != null -> dateRejected(args)
                    day != null -> day
                    ref != null -> ref
                    start == null -> timeRejected(args.opt("startMinute"), "开始时间")
                    args.opt("endMinute") != null && !args.isNull("endMinute") &&
                        parseMinute(args.opt("endMinute")).let { it == null || it <= start } ->
                        "结束时间要晚于开始时间"
                    else -> {
                        val end = parseMinute(args.opt("endMinute")) ?: (start + 60)
                        overlapRejected(start, end.coerceAtMost(1_440),
                            excludeSegmentId = segmentId.removePrefix("segment:"), date = date)
                    }
                }
            },
        ),
        writeSpec(
            "remove_placement", "移出安排", "把一项安排从那一天移除（不写 date 就是界面那天）；任务本身和历史都保留",
            params = listOf(AgentToolParam("segmentId", "安排编号（segment:… 的后半段）", required = true)) + dateParam,
            summarize = { args -> "移出 ${args.optString("segmentId")}" + dayNote(args).orEmpty() },
            validate = { args ->
                val date = dateOf(args)
                segmentRefRejected(args.optString("segmentId"), alsoOn = date) ?: dayRejected(date)
            },
        ),
        writeSpec(
            "complete_task", "完成任务", "把任务标记为已完成",
            params = listOf(
                AgentToolParam("taskId", "任务编号", required = true),
                AgentToolParam("content", "完成了什么"),
                AgentToolParam("actualDurationMinutes", "实际用时（分钟）"),
            ),
            summarize = { args -> "完成「${resolveTaskLabel(args.optString("taskId"))}」" },
            validate = { args -> taskRefRejected(args.optString("taskId")) },
        ),
        writeSpec(
            "cancel_task", "取消任务", "把任务标记为已取消",
            params = listOf(AgentToolParam("taskId", "任务编号", required = true)),
            summarize = { args -> "取消「${resolveTaskLabel(args.optString("taskId"))}」" },
            validate = { args -> taskRefRejected(args.optString("taskId")) },
        ),
        writeSpec(
            "postpone_task", "顺延任务", "把任务顺延并记录原因",
            params = listOf(
                AgentToolParam("taskId", "任务编号", required = true),
                AgentToolParam("reason", "顺延原因"),
            ),
            summarize = { args -> "顺延「${resolveTaskLabel(args.optString("taskId"))}」" },
            validate = { args -> taskRefRejected(args.optString("taskId")) },
        ),
        writeSpec(
            "update_task", "改任务本身", "改一件已有任务的名称、时长、计划日或截止日；没写的字段保持原样，已排好的时段不动",
            params = listOf(
                AgentToolParam("taskId", "任务编号或名称", required = true),
                AgentToolParam("name", "新名称"),
                AgentToolParam("durationMinutes", "时长（分钟）"),
                AgentToolParam("scheduledDate", "打算做的那一天：2026-10-28、明天"),
                AgentToolParam("dueDate", "截止日：2026-10-28、明天"),
                AgentToolParam("category", "类别", enumValues = listOf("COURSE", "EXTRACURRICULAR", "OFFICE", "LEISURE")),
            ),
            summarize = { args -> "改「${resolveTaskLabel(args.optString("taskId"))}」：${updatedFields(args) ?: "没说要改什么"}" },
            validate = { args -> updateTaskRejected(args) },
        ),
        writeSpec(
            "record_feedback", "记录反馈", "给任务补一条执行反馈",
            params = listOf(
                AgentToolParam("taskId", "任务编号", required = true),
                AgentToolParam("content", "反馈内容", required = true),
                AgentToolParam("progressPercent", "进度百分比"),
            ),
            summarize = { args -> "给「${resolveTaskLabel(args.optString("taskId"))}」记反馈" },
            validate = { args -> taskRefRejected(args.optString("taskId")) },
        ),
        writeSpec(
            "save_routine", "设固定安排",
            "登记每周重复的固定占用（每天、工作日、周末、每周几），不是待办任务；" +
                "用户说「排成日常事项」「每天这个时间」「每周三晚上」时用它，绝不能只排一天；" +
                "用户点名了钟点才写 start；说「看着空档/合适时间/你定」或没给钟点时省掉 start，" +
                "只给 durationMinutes，助手会按那几天的共同空档挑一个时段。" +
                "改时间或改日子时给 replace=true，同名旧安排会在同一次写入里被替换",
            params = listOf(
                AgentToolParam("name", "安排名称，例如「玩王者荣耀」「晚自习」", required = true),
                AgentToolParam("start", "开始时间 HH:mm；只有用户点名钟点时才写"),
                AgentToolParam("end", "结束时间 HH:mm，可以写 24:00"),
                AgentToolParam("durationMinutes", "时长（分钟）；没写 end 时按它算，默认 60"),
                AgentToolParam(
                    "weekdays", "哪几天：每天 / 工作日 / 周末 / 周一、周三 / 1,3,5（1=周一，7=周日）",
                    required = true,
                ),
                AgentToolParam("kind", "类型", enumValues = listOf("OTHER", "COURSE", "REST")),
                AgentToolParam("replace", "true 表示同名旧固定安排一起替换（改时间、改日子时用）"),
            ),
            summarize = { args -> routineSummary(args) },
            validate = { args -> routineRejected(args) },
        ),
        writeSpec(
            "remove_routine", "取消固定安排",
            "取消每周重复的固定安排；任务本身和已排的时段都不受影响。只给 name 就取消同名全部，给 weekdays 只取消其中几天",
            params = listOf(
                AgentToolParam("routineId", "固定安排编号（query_routines 里的 id）"),
                AgentToolParam("name", "固定安排原名；没给 routineId 时用"),
                AgentToolParam("weekdays", "只取消其中几天：每天 / 工作日 / 周末 / 周一、周三 / 1,3,5"),
            ),
            summarize = { args -> removeRoutineSummary(args) },
            validate = { args -> removeRoutineRejected(args) },
        ),
        writeSpec(
            "set_available_time", "登记可用时间",
            "登记一段时间算不算能排事：free=true 是空闲、false 是占用（班会、值班这类）；" +
                "不写 repeatWeekly 就只落在 date 那一天，写了 true 就按那一天是星期几每周重复",
            params = listOf(
                AgentToolParam("startMinute", "开始时间 HH:mm", required = true),
                AgentToolParam("endMinute", "结束时间 HH:mm，可以写 24:00 到当天结束", required = true),
                AgentToolParam("free", "true 空闲可排事 / false 占用别排事", required = true),
                AgentToolParam("repeatWeekly", "true 表示每周这几天都这样；不写就只这一天"),
                AgentToolParam("title", "这段叫什么，例如「班会」「晚自习」"),
            ) + dateParam,
            summarize = { args -> "把${availabilityPhrase(args)}" + pressedNote(args) },
            validate = { args -> availabilityRejected(args) },
        ),
        writeSpec(
            "set_reminder", "提醒开关", "打开或关闭一项安排的独立提醒；enabled=true 时可用 lead 指定提前多少分钟（不写就沿用原提前量）",
            params = listOf(
                AgentToolParam("segmentId", "安排编号（segment:… 的后半段）", required = true),
                AgentToolParam("enabled", "true 开 / false 关", required = true),
                AgentToolParam("lead", "提前多少分钟响铃（1–1440）；只在 enabled=true 时有效"),
            ),
            summarize = { args ->
                val on = args.optBoolean("enabled", true)
                "${if (on) "打开" else "关闭"}提醒（${args.optString("segmentId")}）" + reminderNote(on, leadOf(args))
            },
            validate = { args ->
                val raw = args.opt("enabled")
                val flag = raw is Boolean || raw == "true" || raw == "false"
                val on = args.optBoolean("enabled", true)
                when {
                    segmentRefRejected(args.optString("segmentId")) != null -> segmentRefRejected(args.optString("segmentId"))
                    !flag -> "enabled 要填 true 或 false"
                    on && leadRejected(args) != null -> leadRejected(args)
                    !on && leadOf(args) != null -> "关闭提醒时不用给 lead"
                    else -> null
                }
            },
        ),
        writeSpec(
            "start_focus", "进入专注", "为任务开始一个专注执行会话",
            params = listOf(AgentToolParam("taskId", "任务编号", required = true)),
            summarize = { args -> "为「${resolveTaskLabel(args.optString("taskId"))}」开始专注" },
            validate = { args -> taskRefRejected(args.optString("taskId")) },
        ),
        AgentToolSpec(
            "confirm_draft", "按最新状态执行草案",
            "用户这句话明确授权执行当前待确认草案时调用；界面会按最新数据重新校验后落地，成功与否由执行结果决定",
            kind = AgentToolKind.CONTROL,
        ),
        AgentToolSpec(
            "cancel_draft", "取消待确认草案",
            "用户明确要放弃当前待确认草案时调用；草案作废，不会写入任何东西",
            kind = AgentToolKind.CONTROL,
        ),
        AgentToolSpec(
            "undo_last", "撤销上一批写入",
            "用户要撤销刚确认执行的那一批写入时调用；界面整体恢复到执行前",
            kind = AgentToolKind.CONTROL,
        ),
    )

    // ------------------------------------------------------------------ reads

    override suspend fun runRead(name: String, arguments: JSONObject): AgentToolResult = when (name) {
        "query_tasks" -> AgentToolResult(true, describeTasks())
        "query_schedule", "query_free_windows", "query_time_budget" -> {
            val date = dateOf(arguments)
            val rejection = dateRejected(arguments) ?: dayRejected(date)
            if (rejection != null) AgentToolResult(false, rejection)
            else AgentToolResult(true, when (name) {
                "query_schedule" -> describeSchedule(date)
                "query_free_windows" -> describeFreeWindows(date)
                else -> describeTimeBudget(date)
            })
        }
        "query_memory" -> AgentToolResult(true, describeMemory(arguments.optString("keyword")))
        "query_routines" -> AgentToolResult(true, describeRoutines())
        else -> AgentToolResult(false, "未知读取工具：$name")
    }

    private fun describeTasks(): String {
        if (tasks.isEmpty()) return "还没有任务"
        return "共 ${tasks.size} 项：" + tasks.take(15).joinToString("；") { task ->
            "${task.id} 「${task.displayName}」${statusLabel(task.status)}" +
                (task.totalDurationMinutes?.let { " 约${it}分钟" } ?: "") +
                (task.dueDate?.let { " 截止$it" } ?: "")
        }
    }

    /** 读的是哪一天要说在结论里：跨天读取后只剩"共 3 项"，模型和用户都会当成界面那天。 */
    private fun describeSchedule(date: LocalDate): String {
        val occupied = occupiedOn(date)
        val head = dayPhrase(date)
        if (occupied.isEmpty()) return "${head}还没有安排"
        return "${head}共 ${occupied.size} 项：" + occupied.sortedBy { it.startMinute }.joinToString("；") { entry ->
            "${entry.id} ${kindLabel(entry.kind)}「${entry.title}」${span(entry.startMinute, entry.endMinute)}"
        }
    }

    private fun describeFreeWindows(date: LocalDate): String {
        val windows = freeWindows(occupiedOn(date))
        val head = dayPhrase(date)
        if (windows.isEmpty()) return "${head}没有 30 分钟以上的连续空档"
        return "${head}空档：" + windows.joinToString("、") { span(it.first, it.last + 1) }
    }

    /**
     * 容量账，不是空档清单：课占掉的、任务占掉的、用户登记的可用档还剩多少，三个数各说各的事。
     * 嵌套安排的父子占同一段时间，分钟数只算一次；没登记可用档时照实说，不拿没课冒充有空。
     */
    private fun describeTimeBudget(date: LocalDate): String {
        val occupied = occupiedOn(date)
        val hard = mergedMinutes(occupied.filter { it.kind != TimelineKind.TASK })
        val soft = mergedMinutes(occupied.filter { it.kind == TimelineKind.TASK })
        val head = "${dayPhrase(date)}已排课或固定安排 $hard 分钟、任务 $soft 分钟"
        val registered = merged(availableRangesForDate(date))
        if (registered.isEmpty()) return "$head；没有登记可用档（空闲模板或当日例外），别把没课当成有空"
        val open = subtract(registered, merged(occupied.map { it.startMinute until it.endMinute }))
            .filter { it.count() >= 30 }
        return if (open.isEmpty()) {
            "$head；登记可用 ${minutes(registered)} 分钟，已经没有 30 分钟以上的整段空档"
        } else {
            "$head；登记可用 ${minutes(registered)} 分钟，还能再排 ${minutes(open)} 分钟" +
                "（只算 30 分钟以上的整段）：" + open.joinToString("、") { span(it.first, it.last + 1) }
        }
    }

    // ------------------------------------------------------------------ routines
    // 日常安排就是可重复的周模板：一次登记按星期几展开，登记、取消和查重都以这里为准。

    private fun routines(): List<WeeklyTimeBlock> =
        weeklyBlocksProvider().filter { it.kind != TimeBlockKind.AVAILABLE }

    private fun describeRoutines(): String {
        val blocks = routines()
        if (blocks.isEmpty()) return "还没有每周重复的固定安排"
        return "共 ${blocks.size} 段：" + blocks
            .sortedWith(compareBy({ it.dayOfWeek.value }, { it.startMinute }))
            .joinToString("；") {
                "${it.id} 「${it.title}」${weekDayName(it.dayOfWeek)} ${span(it.startMinute, it.endMinute)}" +
                    "（${routineKindLabel(it.kind)}）"
            }
    }

    private fun routineKindLabel(kind: TimeBlockKind): String = when (kind) {
        TimeBlockKind.COURSE -> "课程"
        TimeBlockKind.REST -> "休息"
        else -> "固定安排"
    }

    private fun weekDayName(day: DayOfWeek): String = "周" + "一二三四五六日"[day.value - 1]

    /** 星期口径只有一个：1=周一 … 7=周日；也接住「每天/工作日/周末/周一、周三」这些说法。 */
    private fun weekdaysOf(raw: Any?): List<DayOfWeek>? {
        if (raw == null || raw == JSONObject.NULL) return null
        val tokens = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { index -> raw.opt(index)?.toString() }
            else -> raw.toString().split(Regex("[,，、/\\s]+")).filter { it.isNotBlank() }
        }
        if (tokens.isEmpty()) return emptyList()
        val days = mutableSetOf<DayOfWeek>()
        for (token in tokens) {
            val text = token.trim()
            when {
                text.matches(Regex("(?i)daily|every\\s*day")) ||
                    Regex("每天|每日|天天").containsMatchIn(text) -> return DayOfWeek.values().toList()
                Regex("工作日|平时").containsMatchIn(text) -> days += listOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                )
                Regex("周末").containsMatchIn(text) -> {
                    days += DayOfWeek.SATURDAY
                    days += DayOfWeek.SUNDAY
                }
                text.matches(Regex("[1-7]")) -> days += DayOfWeek.of(text.toInt())
                else -> {
                    val label = text.removePrefix("每").replace("星期", "").replace("礼拜", "").removePrefix("周")
                    val index = "一二三四五六日".indexOf(label.replace("天", "日"))
                    if (label.length == 1 && index >= 0) days += DayOfWeek.of(index + 1)
                }
            }
        }
        return days.sortedBy { it.value }
    }

    private fun daysLabel(days: List<DayOfWeek>): String {
        val sorted = days.distinct().sortedBy { it.value }
        return when {
            sorted.size == 7 -> "每天"
            sorted == listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "工作日"
            sorted == listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "周末"
            else -> "每" + sorted.joinToString("、") { weekDayName(it) }
        }
    }

    private data class RoutineInput(
        val name: String,
        val startMinute: Int,
        val endMinute: Int,
        val days: List<DayOfWeek>,
        val kind: TimeBlockKind,
    )

    private data class RoutinePlan(val input: RoutineInput, val autoPicked: Boolean)

    private sealed interface RoutineAttempt {
        data class Ready(val plan: RoutinePlan) : RoutineAttempt
        data class Rejected(val message: String) : RoutineAttempt
    }

    private enum class RoutinePreference { MORNING, AFTERNOON, EVENING }

    /**
     * 卡上承诺的落点和最后写进去的落点必须是同一个。自动选档依赖「现在」的空档，
     * 用户确认前可能又过了一轮对话；按参数指纹记住方案，确认那一下直接复用。
     */
    private val routineMemo = mutableMapOf<String, RoutinePlan>()

    private fun routineMemoKey(args: JSONObject): String = listOf(
        "name" to args.optString("name").trim(),
        "weekdays" to args.optString("weekdays").trim(),
        "start" to args.optString("start").trim(),
        "end" to args.optString("end").trim(),
        "duration" to (args.optIntOrNull("durationMinutes") ?: -1).toString(),
        "replace" to args.optBoolean("replace", false).toString(),
    ).joinToString("|") { (key, value) -> "$key=$value" }

    /**
     * 固定安排的时间解析：句子里点名的钟点最硬，模型补的字段只是建议。
     * 只给时长/只说「看着空档」时按周模板与未来七天已排时段挑一个共同空档，
     * 反向、越过 24:00、没有共同空档都要变成一句能回答的问题，而不是内部校验行。
     */
    private fun routinePlan(args: JSONObject): RoutineAttempt {
        val key = routineMemoKey(args)
        routineMemo[key]?.let { return RoutineAttempt.Ready(it) }

        val name = args.optString("name").trim()
        if (name.isEmpty()) return RoutineAttempt.Rejected("固定安排叫什么名字？")
        val utterance = currentUtterance
        val spokenDuration = routineDurationOf(utterance)
        val rawDuration = args.optIntOrNull("durationMinutes")
        if (rawDuration != null && rawDuration !in 5..720) return RoutineAttempt.Rejected("时长需要 5–720 分钟")
        val rawStart = parseMinute(args.opt("start"))?.takeIf { it < 1_440 }
        val rawEnd = endMinuteOf(args.opt("end"))
        val modelSpan = rawStart?.let { start -> rawEnd?.takeIf { it > start }?.minus(start) }
        val duration = (spokenDuration ?: rawDuration ?: modelSpan ?: 60).coerceIn(5, 720)
        val days = weekdaysOf(args.opt("weekdays")).takeUnless { it.isNullOrEmpty() }
            ?: weekdaysFromUtterance(utterance)
        if (days.isNullOrEmpty()) return RoutineAttempt.Rejected("是每天、工作日、周末，还是每周几？")
        val sortedDays = days.distinct().sortedBy { it.value }
        val kind = args.optString("kind").takeIf { it.isNotBlank() }
            ?.let { runCatching { TimeBlockKind.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?.takeUnless { it == TimeBlockKind.AVAILABLE } ?: TimeBlockKind.OTHER

        val clocks = routineClocksOf(utterance)
        val endAnchored = ROUTINE_END_ANCHORED.containsMatchIn(utterance)
        var start = rawStart
        var end = rawEnd

        if (clocks.size >= 2) {
            val first = clocks[0]
            val second = clocks[1]
            if (first < second) {
                start = first
                end = second
            } else {
                return RoutineAttempt.Rejected(reversedRoutineQuestion(name, first, second))
            }
        } else if (clocks.size == 1) {
            val clock = clocks[0]
            if (endAnchored) {
                if (clock - duration < 0) {
                    return RoutineAttempt.Rejected(
                        "「$name」要在 ${formatMinute(clock)} 结束、排 $duration 分钟就得跨天了，固定安排不能跨天。" +
                            "要缩短时长，还是换个结束时间？",
                    )
                }
                start = clock - duration
                end = clock
            } else {
                start = clock
                if (end == null || end!! <= clock) end = clock + duration
            }
        }

        if (start != null && end == null) end = start + duration
        if (start != null && end != null && end!! <= start) {
            start = null
            end = null
        }
        if (start != null && end != null && clocks.size < 2 && spokenDuration != null && end!! - start != spokenDuration) {
            if (start + spokenDuration <= 1_440) {
                end = start + spokenDuration
            } else if (clocks.isNotEmpty()) {
                return RoutineAttempt.Rejected(overflowRoutineQuestion(name, start, spokenDuration))
            } else {
                start = null
                end = null
            }
        }
        if (start != null && end != null && end!! > 1_440) {
            if (clocks.isNotEmpty()) return RoutineAttempt.Rejected(overflowRoutineQuestion(name, start, end!! - start))
            start = null
            end = null
        }

        fun remember(plan: RoutinePlan): RoutineAttempt {
            if (routineMemo.size > 32) routineMemo.clear()
            routineMemo[key] = plan
            return RoutineAttempt.Ready(plan)
        }

        if (start == null || end == null) {
            val slot = pickRoutineSlot(name, sortedDays, duration)
                ?: return RoutineAttempt.Rejected(noCommonRoutineWindow(name, sortedDays, duration))
            return remember(RoutinePlan(RoutineInput(name, slot.first, slot.second, sortedDays, kind), autoPicked = true))
        }
        // 用户没点名钟点时，模型填的时段也只是建议：撞上课程/已有安排时，
        // 「看着空档放」就该真的去空档里挑，而不是把两件事叠在一起。
        if (clocks.isEmpty() && routineCollides(name, sortedDays, start!!, end!!)) {
            val slot = pickRoutineSlot(name, sortedDays, duration)
                ?: return RoutineAttempt.Rejected(noCommonRoutineWindow(name, sortedDays, duration))
            return remember(RoutinePlan(RoutineInput(name, slot.first, slot.second, sortedDays, kind), autoPicked = true))
        }
        return remember(RoutinePlan(RoutineInput(name, start!!, end!!, sortedDays, kind), autoPicked = false))
    }

    /** 查重与替换都按「同名 + 同星期 + 同时段」判断；时段的比较只认分钟数。 */
    private fun routineCovered(block: WeeklyTimeBlock, input: RoutineInput): Boolean =
        block.title == input.name && block.kind != TimeBlockKind.AVAILABLE &&
            block.startMinute == input.startMinute && block.endMinute == input.endMinute

    private fun routineRejected(args: JSONObject): String? {
        val attempt = routinePlan(args)
        if (attempt is RoutineAttempt.Rejected) return attempt.message
        val plan = (attempt as RoutineAttempt.Ready).plan
        val existing = routines().filter {
            it.title == plan.input.name && it.startMinute == plan.input.startMinute &&
                it.endMinute == plan.input.endMinute && dayIn(plan.input.days, it.dayOfWeek)
        }
        if (existing.map { it.dayOfWeek }.distinct().size >= plan.input.days.distinct().size) {
            return "「${plan.input.name}」这些天已经是固定安排了"
        }
        return null
    }

    private fun dayIn(days: List<DayOfWeek>, day: DayOfWeek): Boolean = days.any { it == day }

    private fun routineSummary(args: JSONObject): String {
        val plan = (routinePlan(args) as? RoutineAttempt.Ready)?.plan
            ?: return "登记固定安排「${args.optString("name").trim()}」"
        val replace = args.optBoolean("replace", false)
        val head = "把「${plan.input.name}」设为${daysLabel(plan.input.days)} " +
            "${span(plan.input.startMinute, plan.input.endMinute)} 的固定安排" +
            if (plan.autoPicked) "（按空档挑的）" else ""
        return if (!replace) head else {
            val stale = routines().filter {
                it.title == plan.input.name && (!dayIn(plan.input.days, it.dayOfWeek) || !routineCovered(it, plan.input))
            }
            if (stale.isEmpty()) head else "$head（替换旧的 ${stale.size} 段）"
        }
    }

    private fun routineCollides(name: String, days: List<DayOfWeek>, start: Int, end: Int): Boolean {
        val busy = routineBusyByDay(days, excludeName = name)
        return days.any { day -> busy[day].orEmpty().any { (from, to) -> start < to && end > from } }
    }

    /** 固定安排要落在这一周真实的占用之外：周模板 + 未来七天已排的时段。 */
    private fun routineBusyByDay(days: List<DayOfWeek>, excludeName: String?): Map<DayOfWeek, List<Pair<Int, Int>>> {
        val byDay = days.associateWith { mutableListOf<Pair<Int, Int>>() }
        weeklyBlocksProvider().forEach { block ->
            if (block.kind == TimeBlockKind.AVAILABLE) return@forEach
            if (excludeName != null && block.title.trim() == excludeName) return@forEach
            byDay[block.dayOfWeek]?.add(block.startMinute to block.endMinute)
        }
        for (offset in 0..6) {
            val date = activeDate.plusDays(offset.toLong())
            val bucket = byDay[date.dayOfWeek] ?: continue
            val entries = if (date == activeDate) occupiedEntries else timelineForDate(date).orEmpty()
            entries.forEach { entry -> bucket.add(entry.startMinute to entry.endMinute) }
        }
        return byDay
    }

    private fun pickRoutineSlot(name: String, days: List<DayOfWeek>, duration: Int): Pair<Int, Int>? {
        val dayStart = ChatDayPlanner.DEFAULT_DAY_START_MINUTE
        val dayEnd = ChatDayPlanner.DEFAULT_DAY_END_MINUTE
        if (duration > dayEnd - dayStart) return null
        val busy = routineBusyByDay(days, excludeName = name)
        val freeByDay = days.associateWith { day -> freeRanges(busy[day].orEmpty(), dayStart, dayEnd) }
        val starts = (dayStart..(dayEnd - duration) step 15).filter { start ->
            days.all { day -> freeByDay.getValue(day).any { (from, to) -> start >= from && start + duration <= to } }
        }
        if (starts.isEmpty()) return null
        val chosen = when (routinePreferenceOf(currentUtterance)) {
            RoutinePreference.MORNING -> starts.filter { it < 12 * 60 }.minOrNull() ?: starts.min()
            RoutinePreference.AFTERNOON -> starts.filter { it >= 12 * 60 && it + duration <= 18 * 60 }.maxOrNull()
                ?: starts.filter { it >= 17 * 60 }.maxOrNull() ?: starts.max()
            else -> starts.filter { it >= 17 * 60 }.maxOrNull() ?: starts.max()
        }
        return chosen to chosen + duration
    }

    private fun freeRanges(busy: List<Pair<Int, Int>>, dayStart: Int, dayEnd: Int): List<Pair<Int, Int>> {
        val merged = mutableListOf<Pair<Int, Int>>()
        busy.map { (from, to) -> maxOf(from, dayStart) to minOf(to, dayEnd) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
            .forEach { (from, to) ->
                val last = merged.lastOrNull()
                if (last != null && from <= last.second) {
                    merged[merged.lastIndex] = last.first to maxOf(last.second, to)
                } else {
                    merged += from to to
                }
            }
        val free = mutableListOf<Pair<Int, Int>>()
        var cursor = dayStart
        merged.forEach { (from, to) ->
            if (from > cursor) free += cursor to from
            cursor = maxOf(cursor, to)
        }
        if (cursor < dayEnd) free += cursor to dayEnd
        return free
    }

    private fun noCommonRoutineWindow(name: String, days: List<DayOfWeek>, duration: Int): String {
        val dayStart = ChatDayPlanner.DEFAULT_DAY_START_MINUTE
        val dayEnd = ChatDayPlanner.DEFAULT_DAY_END_MINUTE
        val busy = routineBusyByDay(days, excludeName = name)
        var common: List<Pair<Int, Int>>? = null
        days.forEach { day ->
            val free = freeRanges(busy[day].orEmpty(), dayStart, dayEnd)
            common = common?.let { current -> intersectRanges(current, free) } ?: free
        }
        val longest = common.orEmpty().maxByOrNull { it.second - it.first }
        if (longest == null || longest.second - longest.first < 30) {
            return "「$name」要排${daysLabel(days)}，但这几天已经排满了，找不到共同空档。要减少天数，还是换个时长？"
        }
        return "「$name」要排${daysLabel(days)} $duration 分钟，但这几天没有共同的长空档；" +
            "最长的一段是 ${formatMinute(longest.first)}-${formatMinute(longest.second)}" +
            "（${longest.second - longest.first} 分钟）。要缩短到这个时长，还是只排其中几天？"
    }

    private fun intersectRanges(left: List<Pair<Int, Int>>, right: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        left.forEach { (aFrom, aTo) ->
            right.forEach { (bFrom, bTo) ->
                val from = maxOf(aFrom, bFrom)
                val to = minOf(aTo, bTo)
                if (to > from) result += from to to
            }
        }
        return result.sortedBy { it.first }
    }

    private fun reversedRoutineQuestion(name: String, first: Int, second: Int): String {
        // 22:00 到 02:00 说的是跨夜，不是两个钟点说反了；这时给 02:00-22:00 的建议只会更糟。
        val crossesMidnight = first >= 18 * 60 &&
            (second <= 6 * 60 || ROUTINE_CROSS_MIDNIGHT.containsMatchIn(currentUtterance))
        if (crossesMidnight) {
            return "「$name」从 ${formatMinute(first)} 到 ${formatMinute(second)} 会跨过午夜，固定安排不能跨天。" +
                "要拆成 ${formatMinute(first)}-24:00 和 00:00-${formatMinute(second)} 两段，" +
                "还是换一个不跨夜的时间？"
        }
        val start = minOf(first, second)
        val end = maxOf(first, second)
        return "「$name」的 ${formatMinute(first)} 和 ${formatMinute(second)} 在同一天里是倒着的，固定安排不能跨天。" +
            "要按 ${span(start, end)} 设吗？"
    }

    private fun overflowRoutineQuestion(name: String, start: Int, duration: Int): String {
        val latest = 1_440 - start
        val shrink = maxOf(5, minOf(duration, latest))
        val crosses = (start + duration) % 1_440
        val option = if (latest >= 30) "缩短到 $shrink 分钟（${formatMinute(start)}-24:00）" else "换个更早的开始时间"
        return "「$name」从 ${formatMinute(start)} 开始 $duration 分钟会排到次日 ${formatMinute(crosses)}，" +
            "固定安排不能跨天。要${option}吗？"
    }

    private fun weekdaysFromUtterance(text: String): List<DayOfWeek>? {
        if (text.isBlank()) return null
        if (ROUTINE_EVERY_DAY.containsMatchIn(text)) return (1..7).map(DayOfWeek::of)
        if (ROUTINE_WORKDAYS.containsMatchIn(text)) return (1..5).map(DayOfWeek::of)
        if (ROUTINE_WEEKEND.containsMatchIn(text)) return listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val days = mutableSetOf<DayOfWeek>()
        ROUTINE_WEEKLY_DAY.findAll(text).forEach { match ->
            weekdayIndexOf(match.groupValues[1])?.let { days += it }
        }
        ROUTINE_DAY_RANGE.findAll(text).forEach { match ->
            val from = weekdayIndexOf(match.groupValues[1])
            val to = weekdayIndexOf(match.groupValues[2])
            if (from != null && to != null && from.value <= to.value) {
                (from.value..to.value).forEach { days += DayOfWeek.of(it) }
            }
        }
        return days.takeIf { it.isNotEmpty() }?.sortedBy { it.value }
    }

    private fun weekdayIndexOf(raw: String): DayOfWeek? {
        val index = "一二三四五六日".indexOf(raw.replace('天', '日'))
        return if (index >= 0) DayOfWeek.of(index + 1) else null
    }

    private fun routinePreferenceOf(text: String): RoutinePreference? = when {
        ROUTINE_MORNING.containsMatchIn(text) -> RoutinePreference.MORNING
        ROUTINE_AFTERNOON.containsMatchIn(text) -> RoutinePreference.AFTERNOON
        ROUTINE_EVENING.containsMatchIn(text) -> RoutinePreference.EVENING
        else -> null
    }

    /** 句子里所有的钟点；「晚上」只影响它后面那几个钟点，「凌晨」不跟着跨到下午。 */
    private fun routineClocksOf(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        fun shiftAt(index: Int): Int {
            val tail = text.substring(maxOf(0, index - 8), index)
            return when {
                ROUTINE_EARLY.containsMatchIn(tail) -> 0
                ROUTINE_LATE.containsMatchIn(tail) -> 12
                else -> 0
            }
        }
        val clocks = mutableListOf<Int>()
        ROUTINE_CLOCK_COLON.findAll(text).forEach { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            val value = if (shiftAt(match.range.first) > 0 && hour in 1..11) hour + 12 else hour
            if (value <= 23 && minute <= 59) clocks += value * 60 + minute
        }
        ROUTINE_CLOCK_WORD.findAll(text).forEach { match ->
            val hour = numberOf(match.groupValues[1]) ?: return@forEach
            val minute = when (val raw = match.groupValues[2].removeSuffix("分").trim()) {
                "" -> 0
                "半" -> 30
                "一刻" -> 15
                "三刻" -> 45
                else -> numberOf(raw) ?: 0
            }
            val value = if (shiftAt(match.range.first) > 0 && hour in 1..11) hour + 12 else hour
            if (value <= 23 && minute <= 59) clocks += value * 60 + minute
        }
        return clocks.distinct()
    }

    /** 「每天四个小时」里的时长；模型没填 durationMinutes 时以这句话为准。 */
    private fun routineDurationOf(text: String): Int? {
        if (text.isBlank()) return null
        if (ROUTINE_90_MIN.containsMatchIn(text)) return 90
        if (ROUTINE_150_MIN.containsMatchIn(text)) return 150
        if (ROUTINE_HALF_HOUR.containsMatchIn(text)) return 30
        // 钟点先整段抹掉：「八点二十分」里的「二十分」是分钟数，不是时长。
        val rest = ROUTINE_CLOCK_WORD.replace(ROUTINE_CLOCK_COLON.replace(text, " "), " ")
        ROUTINE_MINUTES.find(rest)?.let { match ->
            numberOf(match.groupValues[1])?.let { return it.coerceIn(5, 720) }
        }
        ROUTINE_HOURS.find(rest)?.let { match ->
            numberOf(match.groupValues[1])?.let { return (it * 60).coerceIn(5, 720) }
        }
        return null
    }

    private fun numberOf(raw: String): Int? {
        val token = raw.trim()
        if (token.isEmpty()) return null
        token.toIntOrNull()?.let { return it }
        token.toDoubleOrNull()?.let { return Math.round(it).toInt() }
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        val ten = token.indexOf('十')
        return when {
            token == "十" -> 10
            token.length == 1 -> digits[token[0]]
            ten == 0 -> digits[token[1]]?.plus(10)
            ten == token.length - 1 -> digits[token[0]]?.times(10)
            ten > 0 -> digits[token[0]]?.times(10)?.plus(digits[token[1]] ?: return null)
            else -> null
        }
    }

    private fun routineMatches(args: JSONObject): List<WeeklyTimeBlock> {
        val id = args.optString("routineId").trim().removePrefix("weekly:")
        val name = args.optString("name").trim()
        val days = weekdaysOf(args.opt("weekdays")).orEmpty()
        val pool = routines().filter { days.isEmpty() || dayIn(days, it.dayOfWeek) }
        if (id.isNotBlank()) return pool.filter { it.id == id }
        if (name.isBlank()) return emptyList()
        val exact = pool.filter { it.title.trim() == name }
        return if (exact.isNotEmpty()) exact else pool.filter { it.title.contains(name) }
    }

    private fun removeRoutineRejected(args: JSONObject): String? {
        val hasId = args.optString("routineId").isNotBlank()
        val hasName = args.optString("name").isNotBlank()
        if (!hasId && !hasName) return "要取消哪一项固定安排？给我名称或编号，先调用 query_routines 核对"
        val rawDays = args.opt("weekdays")
        if (rawDays != null && rawDays != JSONObject.NULL && weekdaysOf(rawDays).orEmpty().isEmpty()) {
            return "weekdays 没看懂，请写 每天 / 工作日 / 周末 / 周一、周三 / 1,3,5"
        }
        if (routineMatches(args).isEmpty()) {
            return "没找到这项固定安排；先调用 query_routines 核对原名，可能已经不在日常清单里了"
        }
        return null
    }

    private fun removeRoutineSummary(args: JSONObject): String {
        val matches = routineMatches(args)
        val name = matches.firstOrNull()?.title ?: args.optString("name").trim().ifBlank { "这项日常" }
        val days = matches.map { it.dayOfWeek }.distinct().sortedBy { it.value }
        val whenLabel = if (days.isEmpty()) "" else daysLabel(days)
        return "取消「$name」${whenLabel}的固定安排" + if (matches.size > 1) "（共 ${matches.size} 段）" else ""
    }

    /**
     * 提示词里只放了最近几条习惯和最容易被忘的几段记忆；用户追问「你还记得我什么」或点名某个
     * 关键词时得能翻到其余的。挑过的记录必须在开头说清是子集，否则模型会把挑出来的当成全部。
     */
    private fun describeMemory(rawKeyword: String): String {
        val keyword = rawKeyword.trim()
        val allFacts = memoryFacts()
        val allEpisodes = memoryEpisodes()
        val profile = memoryProfile()
        if (allFacts.isEmpty() && allEpisodes.isEmpty() && profile.isEmpty) return "还没有沉淀任何记忆"
        val facts = if (keyword.isEmpty()) allFacts else allFacts.filter { "${it.first} ${it.second}".contains(keyword) }
        val episodes = if (keyword.isEmpty()) allEpisodes else allEpisodes.filter { it.contains(keyword) }
        val risks = if (keyword.isEmpty()) {
            profile.riskLines(6)
        } else {
            profile.riskLines(20).filter { it.contains(keyword) }
        }
        if (keyword.isNotEmpty() && facts.isEmpty() && episodes.isEmpty() && risks.isEmpty()) {
            return "没找到跟「$keyword」有关的记忆（已有 ${allFacts.size} 条习惯、${allEpisodes.size} 条结论）"
        }
        val head = if (keyword.isEmpty()) {
            "已有 ${allFacts.size} 条习惯、${allEpisodes.size} 条小组结论；${profile.logNote()}"
        } else {
            "按「$keyword」从 ${allFacts.size} 条习惯、${allEpisodes.size} 条结论里挑出 " +
                "${facts.size} 条习惯、${episodes.size} 条结论；没挑中的与它无关"
        }
        return buildString {
            appendLine(head)
            if (facts.isNotEmpty()) {
                appendLine("习惯：")
                appendLine(facts.joinToString("\n") { (key, value) -> "- $key：$value" })
            }
            if (risks.isNotEmpty()) {
                appendLine("记忆画像：")
                appendLine(risks.joinToString("\n"))
            }
            if (episodes.isNotEmpty()) {
                appendLine("小组结论：")
                appendLine(episodes.takeLast(10).joinToString("\n") { "- $it" })
            }
        }.trimEnd()
    }

    /** 相邻或重叠的段落合成一段：嵌套的父子占同一段时间，分开加会把一天算爆。 */
    private fun merged(ranges: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        ranges.filter { !it.isEmpty() }.sortedBy(IntRange::first).forEach { range ->
            val last = out.lastOrNull()
            if (last != null && range.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, range.last)
            else out += range
        }
        return out
    }

    private fun minutes(ranges: List<IntRange>): Int = ranges.sumOf { it.count() }

    /** 这些安排实际压掉了多少分钟——重叠的只算一次。 */
    private fun mergedMinutes(entries: List<TimelineEntry>): Int =
        minutes(merged(entries.map { it.startMinute until it.endMinute }))

    /** [cut] 必须是排好序且不重叠的（先过 [merged]）。 */
    private fun subtract(source: List<IntRange>, cut: List<IntRange>): List<IntRange> =
        source.map { range ->
            val pieces = mutableListOf<IntRange>()
            var cursor = range.first
            cut.forEach { hole ->
                if (hole.last >= cursor && hole.first <= range.last) {
                    if (hole.first > cursor) pieces += cursor..(hole.first - 1)
                    cursor = hole.last + 1
                }
            }
            if (cursor <= range.last) pieces += cursor..range.last
            pieces
        }.flatten()

    // ------------------------------------------------------------------ writes

    override suspend fun runWrite(action: AgentAction): AgentActionResult {
        val args = runCatching { JSONObject(action.argumentsJson) }.getOrDefault(JSONObject())
        return when (action.toolName) {
            "create_task" -> createTask(args)
            "plan_tasks" -> planTasks(args)
            "schedule_draft" -> scheduleDraft()
            "place_task" -> {
                val taskId = resolveTaskReference(args.optString("taskId"))
                val date = dateOf(args)
                val start = parseMinute(args.opt("startMinute"))
                    ?: return AgentActionResult(false, "开始时间没看懂（缺参数或格式不对），请用 HH:mm，例如 14:30")
                val end = parseMinute(args.opt("endMinute")) ?: (start + (durationOf(taskId) ?: 60))
                if (end <= start) return AgentActionResult(false, "结束时间必须晚于开始时间")
                val reminderEnabled = placeReminderOf(args)
                val lead = leadOf(args)
                val failure = onPlaceTask(
                    taskId, date, start, end.coerceAtMost(1_440),
                    args.optString("trackId").ifBlank { "focus" }, reminderEnabled, lead,
                )
                if (failure != null) return AgentActionResult(false, "安排没写进时间轴：$failure")
                scheduledTaskIds += taskId
                persistLedger()
                AgentActionResult(
                    true,
                    "已把任务放入 ${date.dayNotePrefix()}${formatMinute(start)}-${formatMinute(end.coerceAtMost(1_440))}" +
                        reminderNote(reminderEnabled, lead),
                )
            }
            "move_placement" -> {
                val segmentId = args.optString("segmentId").removePrefix("segment:")
                val start = parseMinute(args.opt("startMinute"))
                    ?: return AgentActionResult(false, "开始时间没看懂（缺参数或格式不对），请用 HH:mm，例如 14:30")
                val end = parseMinute(args.opt("endMinute")) ?: (start + 60)
                if (end <= start) return AgentActionResult(false, "结束时间必须晚于开始时间")
                // 没写 date 就留在原来那天：把界面那天当成目标日，会把别的日子的安排拽到今天。
                val date = args.optString("date").trim().takeIf { it.isNotEmpty() }?.let { dateOf(args) }
                val failure = onMovePlacement(segmentId, date, start, end.coerceAtMost(1_440), args.optString("trackId").ifBlank { "focus" })
                if (failure != null) return AgentActionResult(false, "挪动没写进时间轴：$failure")
                AgentActionResult(true, "已挪到 ${date?.dayNotePrefix().orEmpty()}${formatMinute(start)}-${formatMinute(end.coerceAtMost(1_440))}")
            }
            "remove_placement" -> {
                onRemovePlacement(args.optString("segmentId").removePrefix("segment:"))
                AgentActionResult(true, "已移出安排")
            }
            "complete_task" -> {
                onCompleteTask(
                    resolveTaskReference(args.optString("taskId")),
                    args.optString("content").takeIf { it.isNotBlank() },
                    args.optIntOrNull("actualDurationMinutes"),
                    args.optIntOrNull("progressPercent"),
                )
                AgentActionResult(true, "已完成「${resolveTaskLabel(args.optString("taskId"))}」")
            }
            "cancel_task" -> {
                onCancelTask(resolveTaskReference(args.optString("taskId")))
                AgentActionResult(true, "已取消「${resolveTaskLabel(args.optString("taskId"))}」")
            }
            "postpone_task" -> {
                onPostponeTask(resolveTaskReference(args.optString("taskId")), args.optString("reason").takeIf { it.isNotBlank() })
                AgentActionResult(true, "已顺延「${resolveTaskLabel(args.optString("taskId"))}」")
            }
            "update_task" -> updateTask(args)
            "save_routine" -> saveRoutine(args)
            "remove_routine" -> removeRoutine(args)
            "set_available_time" -> {
                // 参数在这一条路上只解析一次：卡上写的是哪一段，登记下去就得是哪一段。
                val value = availabilityOf(args)
                    ?: return AgentActionResult(false, "起止时间没看懂，请用 HH:mm，例如 19:00 到 21:00")
                val failure = onSaveAvailability(value)
                if (failure != null) return AgentActionResult(false, "可用档没登记上：$failure")
                AgentActionResult(true, "已把${availabilityPhrase(args)}")
            }
            "record_feedback" -> {
                onRecordFeedback(
                    resolveTaskReference(args.optString("taskId")),
                    TaskFeedback(
                        completedContent = args.optString("content").takeIf { it.isNotBlank() },
                        progressPercent = args.optIntOrNull("progressPercent"),
                    ),
                )
                AgentActionResult(true, "已记录反馈")
            }
            "set_reminder" -> {
                val enabled = args.optBoolean("enabled", true)
                val lead = leadOf(args)
                onSetSegmentReminder(
                    args.optString("segmentId").removePrefix("segment:"),
                    enabled,
                    lead,
                )
                AgentActionResult(
                    true,
                    if (enabled) "提醒已打开" + reminderNote(true, lead) else "提醒已关闭",
                )
            }
            "start_focus" -> {
                onStartFocus(resolveTaskReference(args.optString("taskId")))
                AgentActionResult(true, "专注会话已开始")
            }
            else -> AgentActionResult(false, "未知写入工具：${action.toolName}")
        }
    }

    /** 可用档的参数只解析一次：卡上写的时间和真正登记的时间必须出自同一个值。 */
    private fun availabilityOf(args: JSONObject): QuickAvailability? {
        val start = parseMinute(args.opt("startMinute")) ?: return null
        val end = endMinuteOf(args.opt("endMinute")) ?: return null
        return QuickAvailability(
            date = dateOf(args),
            startMinute = start,
            endMinute = end,
            repeatWeekly = args.optBoolean("repeatWeekly", false),
            free = args.optBoolean("free", true),
            title = args.optString("title").takeIf { it.isNotBlank() },
        )
    }

    /** 24:00 只能当结束，跟界面同一口径。 */
    private fun endMinuteOf(raw: Any?): Int? =
        if (raw is String && raw.trim() == "24:00") 1_440 else parseMinute(raw)

    /** 卡和回执共用这一句：哪一天（或星期几）、哪一段、记成空闲还是占用。 */
    private fun availabilityPhrase(args: JSONObject): String {
        val value = availabilityOf(args)
            ?: return "可用档 ${args.optString("startMinute")}-${args.optString("endMinute")}"
        val kind = when {
            value.free && value.repeatWeekly -> "每周可用时间"
            value.free -> "临时空闲"
            value.repeatWeekly -> "每周不可用时间"
            else -> "临时占用"
        }
        val whenLabel = if (value.repeatWeekly) weekDayName(value.date) else dayPhrase(value.date)
        return "$whenLabel ${span(value.startMinute, value.endMinute)} 记为$kind" + value.title?.let { "（$it）" }.orEmpty()
    }

    /** 占用一段时间不会把已有安排挪走，卡上得写明压到了谁——否则用户以为锁死就自动清空了。 */
    private fun pressedNote(args: JSONObject): String {
        val value = availabilityOf(args) ?: return ""
        if (value.free) return ""
        val pressed = occupiedOn(value.date)
            .filter { it.endMinute > value.startMinute && it.startMinute < value.endMinute }
            .take(3).joinToString("、") { "「${it.title}」" }
        return if (pressed.isEmpty()) "" else "；这段时间已有 $pressed，登记后它们仍留在时间轴上"
    }

    private fun weekDayName(date: LocalDate): String = "周" + "一二三四五六日"[date.dayOfWeek.value - 1]

    private fun availabilityRejected(args: JSONObject): String? {
        val raw = args.opt("free")
        val gaveFree = raw is Boolean || raw == "true" || raw == "false"
        val value = availabilityOf(args)
        return when {
            dateRejected(args) != null -> dateRejected(args)
            value == null -> "起止时间没看懂，请用 HH:mm，例如 19:00 到 21:00（结束可以写 24:00）"
            !gaveFree -> "free 要填 true（空闲可排事）或 false（占用，别往这儿排）"
            value.endMinute <= value.startMinute -> "结束时间必须晚于开始时间"
            value.endMinute - value.startMinute < 15 -> "这一段不到 15 分钟，登记了也排不下事"
            else -> null
        }
    }

    private suspend fun createTask(args: JSONObject): AgentActionResult {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return AgentActionResult(false, "任务名称不能为空")
        val duration = args.optIntOrNull("durationMinutes")?.takeIf { it in 5..720 }
            ?: return AgentActionResult(false, "时长需要 5–720 分钟")
        val category = args.optString("category").takeIf { it.isNotBlank() }
            ?.let { runCatching { TaskCategory.valueOf(it) }.getOrNull() } ?: TaskCategory.UNSPECIFIED
        val priority = args.optString("priority").takeIf { it.isNotBlank() }
            ?.let { runCatching { TaskPriority.valueOf(it) }.getOrNull() } ?: TaskPriority.UNSPECIFIED
        val start = parseMinute(args.opt("start"))
        val taskId = UUID.randomUUID().toString()
        val plannedDay = dateField(args, "date")
        val dueDay = dateField(args, "dueDate")
        val saved = onSaveTasks(
            listOf(
                TaskDraft(
                    id = taskId,
                    displayName = TaskName.fromDescription(name),
                    description = name,
                    category = category,
                    userPriority = priority,
                    estimatedDays = 1,
                    totalDurationMinutes = duration,
                    scheduledForDate = plannedDay,
                    dueDate = dueDay,
                ),
            ),
        )
        if (!saved) return AgentActionResult(false, "任务没有写进清单（保存失败）；时间也没排，请稍后重试")
        createdTasks += CreatedTask(taskId, name, duration, start, category, priority)
        persistLedger()
        return AgentActionResult(
            true,
            "已创建任务「$name」（$duration 分钟${start?.let { "，${formatMinute(it)} 开始" } ?: ""}" +
                "${dueDay?.let { "，截止 ${taskDateLabel(it)}" } ?: ""}）",
        )
    }

    /**
     * 一次说完 + 一次排齐 + 一次确认。
     *
     * 旧路径要 create_task ×N → 确认 → schedule_draft → 再确认落点：同一次意图拦两道门，模型多跑
     * N+2 轮。这里在建卡之前就把 [ChatDayPlanner] 的落点算进摘要，用户看到的即最终时间；
     * 确认时按同一份今日实况重算，卡片与写入保持一致。带 taskId 的项只排不建。
     */
    private suspend fun planTasks(args: JSONObject): AgentActionResult {
        // 看不见那一天就一个字也不写：落点是按"空的一天"算出来的，写下去每段都会撞车。
        dayRejected(dateOf(args))?.let { return AgentActionResult(false, it) }
        val solution = solveBatch(args)
        val items = solution.items
        val date = solution.date
        if (items.isEmpty()) return AgentActionResult(false, "items 里没有任何任务")
        val ids = items.map { it.taskId ?: UUID.randomUUID().toString() }
        val failed = mutableListOf<String>()
        val drafts = items.mapIndexedNotNull { index, item ->
            if (item.taskId != null) return@mapIndexedNotNull null
            TaskDraft(
                id = ids[index],
                displayName = TaskName.fromDescription(item.name),
                description = item.name,
                category = item.category,
                userPriority = item.priority,
                estimatedDays = 1,
                totalDurationMinutes = item.durationMinutes,
                scheduledForDate = date,
            )
        }
        if (drafts.isNotEmpty() && !onSaveTasks(drafts)) {
            return AgentActionResult(false, "任务没有写进清单（保存失败）；一条时间也没排")
        }
        val ok = solution.rows.filter { row ->
            val item = items[row.index]
            val failure = onPlaceTask(
                ids[row.index], date, row.startMinute, row.endMinute, row.trackId,
                item.reminderEnabled ?: false, item.leadMinutes,
            )
            // 卡上算好的落点被仓储拒了，就不能再报「排入」：一次假成功比一次失败更难查。
            if (failure != null) {
                failed += "「${item.name}」：$failure"
                return@filter false
            }
            if (item.taskId == null) {
                createdTasks += CreatedTask(ids[row.index], item.name, item.durationMinutes, item.start, item.category, item.priority)
            }
            scheduledTaskIds += ids[row.index]
            true
        }
        val placed = ok.map { row -> "「${items[row.index].name}」${span(row.startMinute, row.endMinute)}${nestingNote(row.nestedUnderTitle)}" }
        val left = solution.unplaced.map { "「${it.name}」" } + failed
        persistLedger()
        val nested = ok.count { it.nestedUnderTitle != null }
        val head = date.dayNotePrefix() + (if (drafts.isEmpty()) "" else "已建 ${drafts.size} 项、") +
            "排入 ${placed.size} 项：" + placed.joinToString("；") +
            if (nested == 0) "" else "；${dayPhrase(date)}已无空档，其中 $nested 项挂进已有安排里同时做"
        return AgentActionResult(
            placed.isNotEmpty(),
            if (left.isEmpty()) head else "$head；没排上：${left.joinToString("、")}",
        )
    }

    /**
     * The second confirmation stage: this action's execution produces the placements as
     * follow-up pending actions, so nothing lands on the timeline until the user confirms
     * the concrete times.
     */
    private fun scheduleDraft(): AgentActionResult {
        val pending = unscheduledCreated()
        if (pending.isEmpty()) return AgentActionResult(true, "本次会话没有待排期的任务")
        val items = pending.map { created ->
            PlanItem(
                name = created.name,
                taskId = created.taskId,
                durationMinutes = created.durationMinutes ?: DEFAULT_PLAN_MINUTES,
                start = created.startHintMinute,
                category = created.category,
                priority = created.priority,
                reminderEnabled = null,
                leadMinutes = null,
            )
        }
        val plan = ChatDayPlanner.plan(items.map(::candidateOf), occupiedEntries, style = schedulingStyle())
        val followUps = pending.mapIndexedNotNull { index, created ->
            val placement = plan.placementFor(index) ?: return@mapIndexedNotNull null
            AgentAction(
                id = UUID.randomUUID().toString(),
                toolName = "place_task",
                label = "放入时段",
                summary = "把「${created.name}」放到 ${formatMinute(placement.startMinute)}-" +
                    formatMinute(placement.startMinute + (created.durationMinutes ?: 60)) + nestingNote(placement.nestedUnderTitle),
                argumentsJson = JSONObject()
                    .put("taskId", created.taskId)
                    .put("startMinute", placement.startMinute)
                    .put("endMinute", placement.startMinute + (created.durationMinutes ?: 60))
                    .put("trackId", placement.trackId ?: "focus")
                    .toString(),
            )
        }
        if (followUps.isEmpty()) return AgentActionResult(false, "今天没有放得下的空档，试试换一天或缩短时长")
        return AgentActionResult(
            true,
            // 这一步只产出提案：写入发生在用户确认下面的落点之后。
            // 说成「已排出」，模型就照着向用户宣布排好了。
            "算出 ${followUps.size} 项落点，尚未写入时间表，待用户逐项确认：" +
                followUps.joinToString("；") { it.summary },
            followUpActions = followUps,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun unscheduledCreated(): List<CreatedTask> = createdTasks.filterNot { it.taskId in scheduledTaskIds }

    /** 批量卡共用的排期口径：模型只报意图（名称或编号/时长/可选开始时间），落点由规划器算。 */
    private data class PlanItem(
        val name: String,
        val taskId: String?,
        val durationMinutes: Int?,
        val start: Int?,
        val category: TaskCategory,
        val priority: TaskPriority,
        val reminderEnabled: Boolean?,
        val leadMinutes: Int?,
    )

    private fun candidateOf(item: PlanItem): ArrangementCandidate = ArrangementCandidate(
        title = item.name.ifBlank { resolveTaskLabel(item.taskId.orEmpty()) },
        category = item.category,
        durationMinutes = item.durationMinutes,
        timeHint = ArrangementTimeHint(explicitStartMinute = item.start),
        needsClarification = buildSet {
            if (item.start == null) add(ArrangementClarification.TIME)
            if (item.durationMinutes == null) add(ArrangementClarification.DURATION)
        },
        placementSource = if (item.start == null) {
            ArrangementPlacementSource.UNSCHEDULED
        } else {
            ArrangementPlacementSource.USER_EXPLICIT
        },
        existingTaskId = item.taskId,
        preferredTrackId = "focus",
        priority = item.priority.takeIf { it != TaskPriority.UNSPECIFIED },
    )

    /** 模型可能给数组、给单个对象、或把数组写成字符串；缺省时长按 60 分钟先排，用户在卡上调。 */
    private fun rawItemsOf(args: JSONObject): JSONArray? = when (val raw = args.opt("items")) {
        is JSONArray -> raw
        is JSONObject -> JSONArray().put(raw)
        is String -> runCatching { JSONArray(raw.trim()) }.getOrNull()
        else -> null
    }

    private fun batchItems(args: JSONObject): List<PlanItem> {
        val array = rawItemsOf(args) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val taskId = item.optString("taskId").trim().takeIf { it.isNotEmpty() }?.let(::resolveTaskReference)
            val category = item.optString("category").takeIf { it.isNotBlank() }
                ?.let { runCatching { TaskCategory.valueOf(it) }.getOrNull() } ?: TaskCategory.UNSPECIFIED
            val priority = item.optString("priority").takeIf { it.isNotBlank() }
                ?.let { runCatching { TaskPriority.valueOf(it) }.getOrNull() } ?: TaskPriority.UNSPECIFIED
            val lead = leadOf(item)
            PlanItem(
                name = item.optString("name").trim(),
                taskId = taskId,
                durationMinutes = item.optIntOrNull("durationMinutes")?.takeIf { it in 5..720 }
                    ?: taskId?.let(::durationOf) ?: DEFAULT_PLAN_MINUTES,
                start = parseMinute(item.opt("start")),
                category = category,
                priority = priority,
                reminderEnabled = reminderOf(item) ?: (lead != null),
                leadMinutes = lead,
            )
        }
    }

    /**
     * 卡片逐件列表、摘要文案和真正写入的落点，全部来自这一次求解。
     * 分三处各算一遍，卡上说的时间和写进时间轴的时间迟早会分家。
     */
    private class BatchSolution(
        val items: List<PlanItem>,
        val rows: List<AgentBatchPreviewRow>,
        val unplaced: List<PlanItem>,
        val style: SchedulingStyle,
        val date: LocalDate,
    )

    /** 卡上点过的风格写在参数里（跟着这一次确认走）；没点过才用存下来的那套。 */
    private fun styleOf(args: JSONObject): SchedulingStyle =
        args.optString("style").takeIf { it.isNotBlank() }
            ?.let(SchedulingStyle::fromKey)
            ?: schedulingStyle()

    private fun solveBatch(args: JSONObject): BatchSolution {
        val date = dateOf(args)
        val occupied = occupiedOn(date)
        val items = batchItems(args).map { item ->
            item.copy(name = item.name.ifBlank { resolveTaskLabel(item.taskId.orEmpty()) })
        }
        if (items.isEmpty()) return BatchSolution(emptyList(), emptyList(), emptyList(), styleOf(args), date)
        val style = styleOf(args)
        val plan = ChatDayPlanner.plan(items.map(::candidateOf), occupied, style = style)
        val rows = items.mapIndexedNotNull { index, item ->
            val placement = plan.placementFor(index) ?: return@mapIndexedNotNull null
            AgentBatchPreviewRow(
                index = index,
                name = item.name,
                durationMinutes = item.durationMinutes ?: DEFAULT_PLAN_MINUTES,
                startMinute = placement.startMinute,
                endMinute = placement.endMinute.coerceAtMost(1_440),
                trackId = placement.trackId,
                taskId = item.taskId,
                userPickedStartMinute = placement.startMinute.takeIf { placement.fromUser },
                // 用户点名那一刻也算嵌套：卡上必须写明挂在谁里面，否则只看到两件同时开始。
                nestedUnderTitle = placement.nestedUnderTitle
                    ?: ChatDayPlanner.nestedHostTitleAt(placement.startMinute, placement.endMinute, occupied, item.taskId),
            )
        }
        return BatchSolution(items, rows, items.filterIndexed { index, _ -> plan.placementFor(index) == null }, style, date)
    }

    /** 跨天批量时，卡上逐件挑时间得看那一天的实况；拿界面那天的空档去挑另一天，挑出来的必撞。 */
    override fun batchOccupied(action: AgentAction): List<TimelineEntry> =
        if (action.toolName != "plan_tasks") emptyList()
        else runCatching { occupiedOn(dateOf(JSONObject(action.argumentsJson))) }.getOrDefault(emptyList())

    /** 卡上只写"14:00-15:00"而不说哪天，用户会以为排在今天，确认完才发现排到了明天。 */
    override fun batchDayLabel(action: AgentAction): String? =
        if (action.toolName != "plan_tasks") null
        else runCatching { dateOf(JSONObject(action.argumentsJson)) }.getOrNull()
            ?.takeIf { it != activeDate }?.let(::taskDateLabel)

    override fun batchPreview(action: AgentAction): List<AgentBatchPreviewRow> =
        if (action.toolName != "plan_tasks") emptyList()
        else runCatching { solveBatch(JSONObject(action.argumentsJson)).rows }.getOrDefault(emptyList())

    override fun batchStyle(action: AgentAction): SchedulingStyle =
        if (action.toolName != "plan_tasks") SchedulingStyle.PACKED
        else runCatching { styleOf(JSONObject(action.argumentsJson)) }.getOrDefault(SchedulingStyle.PACKED)

    private fun summarizeBatch(args: JSONObject): String {
        val solution = solveBatch(args)
        if (solution.items.isEmpty()) return "批量排期（没解析出任务）"
        val parts = solution.items.mapIndexed { index, item ->
            val row = solution.rows.firstOrNull { it.index == index }
            if (row == null) "「${item.name}」放不下"
            else "「${row.name}」${span(row.startMinute, row.endMinute)}${nestingNote(row.nestedUnderTitle)}" +
                reminderNote(item.reminderEnabled, item.leadMinutes)
        }
        val created = solution.items.count { it.taskId == null }
        val head = (if (created == solution.items.size) "建 ${solution.items.size} 项并排期" else "排 ${solution.items.size} 项（新建 $created 项）") +
            // 排在界面那天时不必再报一次日期；换了天就必须写出来，否则卡上只有时段。
            solution.date.takeIf { it != activeDate }?.let { "到 ${taskDateLabel(it)}" }.orEmpty()
        return "$head：" + parts.joinToString("；")
    }

    private fun batchRejected(args: JSONObject): String? {
        dateRejected(args)?.let { return it }
        dayRejected(dateOf(args))?.let { return it }
        when (val raw = args.opt("items")) {
            null, JSONObject.NULL -> return "items 要一个任务数组，例如 {\"items\":[{\"name\":\"复习高数\",\"durationMinutes\":90}]}"
            is String -> if (runCatching { JSONArray(raw.trim()) }.getOrNull() == null) return "items 不是合法 JSON 数组：$raw"
        }
        val items = batchItems(args)
        if (items.isEmpty()) return "items 没解析出任何任务；每项要么给 name 新建，要么给 taskId 排已有任务"
        rawItemsOf(args)?.let { array ->
            (0 until array.length()).forEach { index ->
                val item = array.optJSONObject(index) ?: return@forEach
                reminderRejected(item)?.let { return "第 ${index + 1} 项：$it" }
                leadRejected(item)?.let { return "第 ${index + 1} 项：$it" }
            }
        }
        items.forEachIndexed { index, item ->
            if (item.name.isBlank() && item.taskId == null) return "第 ${index + 1} 项既没有 name 也没有 taskId"
            item.taskId?.let { taskRefRejected(it) }?.let { return it }
        }
        val plan = ChatDayPlanner.plan(items.map(::candidateOf), occupiedOn(dateOf(args)), style = styleOf(args))
        val missing = items.filterIndexed { index, _ -> plan.placementFor(index) == null }
        if (missing.isEmpty()) return null
        return "${dayPhrase(dateOf(args))}放不下：" + missing.joinToString("、") { "「${it.name.ifBlank { resolveTaskLabel(it.taskId.orEmpty()) }}」${it.durationMinutes ?: "?"} 分钟" } +
            "；缩短时长、去掉指定时间、建议挪到明天，或先跟用户说明再重试——夜里留给睡眠，别用熬夜换完成率"
    }

    private fun createdDurationOf(taskId: String): Int? =
        createdTasks.firstOrNull { it.taskId == taskId }?.durationMinutes

    private fun durationOf(taskId: String): Int? =
        tasks.firstOrNull { it.id == taskId }?.totalDurationMinutes ?: createdDurationOf(taskId)

    private fun resolveTask(idOrName: String): Task? =
        tasks.firstOrNull { it.id == idOrName }
            ?: tasks.firstOrNull { it.displayName == idOrName }
            ?: tasks.firstOrNull { idOrName.isNotBlank() && it.displayName.contains(idOrName) }

    /** 模型可能拿名字或编号引用任务；编号（含本轮新建）原样通过，名字解析成真实 id。 */
    private fun resolveTaskReference(idOrName: String): String = when {
        idOrName.isBlank() -> idOrName
        tasks.any { it.id == idOrName } || createdTasks.any { it.taskId == idOrName } -> idOrName
        else -> resolveTask(idOrName)?.id
            ?: createdTasks.firstOrNull { it.name == idOrName || it.taskId == idOrName }?.taskId
            ?: idOrName
    }

    private fun resolveTaskLabel(idOrName: String): String = resolveTask(idOrName)?.displayName ?: idOrName.ifBlank { "未知任务" }

    /** 日期写法只有一个口径：解析交给 AutonomousCaptureDate，它给的理由原样还给模型。 */
    private fun dateRejected(args: JSONObject, key: String = "date"): String? {
        val raw = args.optString(key).trim()
        if (raw.isEmpty()) return null
        val failure = runCatching { AutonomousCaptureDate.resolve(raw, activeDate) }.exceptionOrNull() ?: return null
        return failure.message ?: "日期「$raw」没看懂，请用 2026-10-28、明天 这样的写法"
    }

    /** 相对说法按正在看的那天算：卡上写着"明天"，落点就得是那一天的明天。没写这一项就是没写。 */
    private fun dateField(args: JSONObject, key: String): LocalDate? {
        val raw = args.optString(key).trim()
        if (raw.isEmpty()) return null
        return runCatching { AutonomousCaptureDate.resolve(raw, activeDate).date }.getOrNull()
    }

    /** 不写 date 就是界面正在显示的那天；这一条是整个跨天能力的默认值，只能有一个解释。 */
    private fun dateOf(args: JSONObject): LocalDate = dateField(args, "date") ?: activeDate

    /** 哪一天在文案里只有一个口径：界面那天就叫"今天"，换了天才报具体日期。 */
    private fun dayPhrase(date: LocalDate): String = if (date == activeDate) "今天" else taskDateLabel(date)

    /** 排在界面那天时不重复报日期（沿用原来的说法）；换了天，回执里必须带上是哪一天。 */
    private fun LocalDate.dayNotePrefix(): String = if (this == activeDate) "" else "${taskDateLabel(this)} "

    /** 看不到那一天的实况就不许换天：拿"空的一天"去排，排出来的每一段都会撞车。 */
    private fun dayRejected(date: LocalDate): String? {
        if (date == activeDate || timelineForDate(date) != null) return null
        return "看不到 ${taskDateLabel(date)} 那天的安排；先让用户切到那一天，今天照常排。"
    }

    /** 换天的意图必须写在卡上：只说"14:00 起"，用户会以为排在今天。 */
    private fun dayNote(args: JSONObject): String? =
        dateOf(args).takeIf { it != activeDate }?.let { "，${taskDateLabel(it)}" }

    private fun resolveCategory(raw: String): TaskCategory? = raw.takeIf { it.isNotBlank() }
        ?.let { runCatching { TaskCategory.valueOf(it.trim().uppercase()) }.getOrNull() }

    /** 用户点名要改的那几项；一个都没给就不算改动，摘要和准入共用这一段。 */
    private fun updatedFields(args: JSONObject): String? = listOfNotNull(
        args.optString("name").trim().takeIf { it.isNotEmpty() }?.let { "名称改「$it」" },
        args.optIntOrNull("durationMinutes")?.let { "时长 $it 分钟" },
        dateField(args, "scheduledDate")?.let { "计划 ${dayPhrase(it)}" },
        dateField(args, "dueDate")?.let { "截止 ${dayPhrase(it)}" },
        resolveCategory(args.optString("category"))?.let { "类别换成 ${it.name}" },
    ).ifEmpty { null }?.joinToString("、")

    private suspend fun updateTask(args: JSONObject): AgentActionResult {
        val task = resolveTask(args.optString("taskId"))
            ?: return AgentActionResult(false, "任务不在当前清单里，先核对名称或编号")
        val draft = TaskDraft(
            id = task.id,
            displayName = args.optString("name").trim().ifBlank { task.displayName },
            description = task.description,
            category = resolveCategory(args.optString("category")) ?: task.category,
            userPriority = task.userPriority,
            estimatedDays = task.estimatedDays,
            totalDurationMinutes = args.optIntOrNull("durationMinutes") ?: task.totalDurationMinutes,
            dueDate = dateField(args, "dueDate") ?: task.dueDate,
            scheduledForDate = dateField(args, "scheduledDate") ?: task.scheduledForDate,
        )
        val failure = onUpdateTask(draft)
        if (failure != null) return AgentActionResult(false, "任务信息没改成：$failure")
        // 本轮的待排账本记的是建卡那一刻的时长；清单改了而账本不改，下一句"把剩下的排上"会排出错过的长度。
        val ledgerIndex = createdTasks.indexOfFirst { it.taskId == task.id }
        if (ledgerIndex >= 0) {
            createdTasks[ledgerIndex] = createdTasks[ledgerIndex].copy(
                name = draft.displayName,
                durationMinutes = draft.totalDurationMinutes,
                category = draft.category,
            )
            persistLedger()
        }
        return AgentActionResult(true, "已改「${draft.displayName}」：${updatedFields(args).orEmpty()}")
    }

    /**
     * 日常安排按星期展开成多条周模板；同名、同星期、同时间的已存在就不再重复写。
     * replace=true 时，同名但不在新星期集合里或时间不同的旧块在同一次写入里一起清掉，
     * 用户说「改成周末」「改成 19 点」不需要先删一次再排一次。
     */
    private suspend fun saveRoutine(args: JSONObject): AgentActionResult {
        val attempt = routinePlan(args)
        val plan = when (attempt) {
            is RoutineAttempt.Ready -> attempt.plan
            is RoutineAttempt.Rejected -> return AgentActionResult(false, attempt.message)
        }
        val input = plan.input
        val existing = routines()
        val replace = args.optBoolean("replace", false)
        val stale = if (replace) {
            existing.filter { it.title == input.name && (!dayIn(input.days, it.dayOfWeek) || !routineCovered(it, input)) }
        } else {
            emptyList()
        }
        val fresh = input.days.mapNotNull { day ->
            val covered = existing.any { it.dayOfWeek == day && routineCovered(it, input) }
            if (covered) null else WeeklyTimeBlockDraft(
                title = input.name,
                kind = input.kind,
                dayOfWeek = day,
                startMinute = input.startMinute,
                endMinute = input.endMinute,
                trackId = if (input.kind == TimeBlockKind.COURSE) "course" else "fixed",
            )
        }
        if (fresh.isEmpty() && stale.isEmpty()) {
            return AgentActionResult(false, "「${input.name}」${daysLabel(input.days)}这段已经是固定安排了")
        }
        val failure = onSaveRoutine(stale.map { it.id }, fresh)
        if (failure != null) return AgentActionResult(false, "固定安排没写进去：$failure")
        return AgentActionResult(
            true,
            buildString {
                append("已把「${input.name}」设为${daysLabel(input.days)} ${span(input.startMinute, input.endMinute)} 的固定安排")
                if (plan.autoPicked) append("（按空档挑的）")
                if (stale.isNotEmpty()) append("（替换掉 ${stale.size} 段旧安排）")
            },
        )
    }

    private suspend fun removeRoutine(args: JSONObject): AgentActionResult {
        val matches = routineMatches(args)
        if (matches.isEmpty()) {
            return AgentActionResult(false, "没找到这项固定安排；先调用 query_routines 核对原名，可能已经不在日常清单里了")
        }
        val failure = onSaveRoutine(matches.map { it.id }, emptyList())
        if (failure != null) return AgentActionResult(false, "固定安排没取消：$failure")
        val days = matches.map { it.dayOfWeek }.distinct().sortedBy { it.value }
        return AgentActionResult(
            true,
            "已取消「${matches.first().title}」${daysLabel(days)}的固定安排（${matches.size} 段）",
        )
    }

    private fun parseMinute(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt().takeIf { it in 0..1_440 }
        is String -> raw.trim().let { text ->
            text.toIntOrNull()?.takeIf { it in 0..1_440 } ?: run {
                val parts = text.split(':', '：')
                val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 0..23 }
                val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 0..59 }
                if (parts.size == 2 && hour != null && minute != null) hour * 60 + minute else null
            }
        }
        else -> null
    }

    /** 逐事件提醒的两个口径全应用共用：reminder 缺省=不响铃（沿用表单默认），lead=提前分钟数。 */
    private fun reminderOf(args: JSONObject): Boolean? = when (val raw = args.opt("reminder")) {
        is Boolean -> raw
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun reminderRejected(args: JSONObject, key: String = "reminder"): String? {
        val present = args.has(key) && !args.isNull(key)
        return if (present && reminderOf(args) == null) "reminder 要填 true 或 false" else null
    }

    private fun leadOf(args: JSONObject, key: String = "lead"): Int? =
        args.optIntOrNull(key)?.takeIf { it in 1..1_440 }

    private fun leadRejected(args: JSONObject, key: String = "lead"): String? {
        if (!args.has(key) || args.isNull(key)) return null
        return if (leadOf(args, key) == null) "lead 要是 1–1440 的提前分钟数" else null
    }

    private fun reminderNote(reminderEnabled: Boolean?, leadMinutes: Int?): String = when {
        reminderEnabled != true -> ""
        leadMinutes != null -> "·提前${leadMinutes}分钟响铃"
        else -> "·响铃"
    }

    /** place 侧口径：只写 lead 没写 reminder 就按响铃处理；显式 false 就真不响。 */
    private fun placeReminderOf(args: JSONObject): Boolean =
        reminderOf(args) ?: (leadOf(args) != null)

    private fun placeReminderRejected(args: JSONObject): String? {
        reminderRejected(args)?.let { return it }
        leadRejected(args)?.let { return it }
        if (reminderOf(args) == false && leadOf(args) != null) return "reminder=false 时不用给 lead"
        return null
    }

    private fun freeWindows(occupied: List<TimelineEntry>): List<IntRange> {
        val hardBusy = occupied
            .filter {
                it.kind in setOf(
                    TimelineKind.COURSE,
                    TimelineKind.REST,
                    TimelineKind.COMMITMENT,
                    TimelineKind.TASK,
                )
            }
            .sortedBy(TimelineEntry::startMinute)
        val windows = mutableListOf<IntRange>()
        var cursor = ChatDayPlanner.DEFAULT_DAY_START_MINUTE
        hardBusy.forEach { entry ->
            if (entry.startMinute > cursor) windows += cursor until entry.startMinute
            if (entry.endMinute > cursor) cursor = entry.endMinute
        }
        if (cursor < ChatDayPlanner.DEFAULT_DAY_END_MINUTE) {
            windows += cursor until ChatDayPlanner.DEFAULT_DAY_END_MINUTE
        }
        return windows.filter { it.count() >= 30 }
    }

    private fun statusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.NOT_STARTED -> "未开始"
        TaskStatus.IN_PROGRESS -> "进行中"
        TaskStatus.COMPLETED -> "已完成"
        TaskStatus.POSTPONED -> "已顺延"
        TaskStatus.CANCELLED -> "已取消"
        TaskStatus.REPLACED -> "已替换"
    }

    private fun kindLabel(kind: TimelineKind): String = when (kind) {
        TimelineKind.COURSE -> "课程"
        TimelineKind.REST -> "休息"
        TimelineKind.COMMITMENT -> "固定"
        TimelineKind.TASK -> "任务"
    }

    private fun span(startMinute: Int, endMinute: Int): String =
        "${formatMinute(startMinute)}-${formatMinute(endMinute)}"

    /** 落点挂进已有安排时，卡上必须写明挂在谁里面——否则用户只看到两件同时开始的事，不知道为什么会这样。 */
    private fun nestingNote(nestedUnderTitle: String?): String =
        nestedUnderTitle?.let { "（挂进「$it」）" }.orEmpty()

    private fun formatMinute(minute: Int): String = String.format("%02d:%02d", minute / 60, minute % 60)

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    }

    private fun writeSpec(
        name: String,
        label: String,
        description: String,
        params: List<AgentToolParam> = emptyList(),
        summarize: (JSONObject) -> String,
        validate: (JSONObject) -> String? = { null },
        draftSlot: String? = null,
    ): AgentToolSpec = AgentToolSpec(
        name = name,
        label = label,
        description = description,
        params = params,
        kind = AgentToolKind.WRITE,
        summarize = summarize,
        validate = validate,
        draftSlot = draftSlot,
    )

    // ------------------------------------------------------------------ admission checks
    // 排队前的确定性校验，全部以 provider 给出的实时数据为准：历史快照、模型记忆都可能过期。

    private fun timeRejected(raw: Any?, field: String): String? =
        if (parseMinute(raw) == null) "${field}没看懂，请用 HH:mm（例如 14:30）或当天分钟数" else null

    private fun taskRefRejected(idOrName: String): String? =
        if (idOrName.isBlank() || (resolveTask(idOrName) == null &&
                createdTasks.none { it.taskId == idOrName || it.name == idOrName })
        ) {
            "任务「$idOrName」不在当前清单里；先调用读取工具核对，或跟用户确认名称"
        } else {
            null
        }

    private fun segmentRefRejected(idOrName: String, alsoOn: LocalDate? = null): String? {
        val id = idOrName.removePrefix("segment:")
        if (id.isBlank()) return "安排「$idOrName」不在安排里；先调用读取工具核对"
        val days = listOfNotNull(activeDate, alsoOn)
        if (days.any { day -> occupiedOn(day).any { it.id == id } }) return null
        return "安排「$idOrName」不在 ${days.joinToString("、") { dayPhrase(it) }} 里；" +
            "先调用读取工具核对，或把它所在/要挪去的那天写进 date"
    }

    /** 改任务只动被点名的字段：先把给出的每个字段判一遍，再看有没有至少给一个。 */
    private fun updateTaskRejected(args: JSONObject): String? {
        val duration = args.opt("durationMinutes")
        val gaveDuration = duration != null && duration != JSONObject.NULL
        return when {
            taskRefRejected(args.optString("taskId")) != null -> taskRefRejected(args.optString("taskId"))
            gaveDuration && args.optIntOrNull("durationMinutes") == null -> "时长要填分钟数，例如 45"
            args.optIntOrNull("durationMinutes")?.let { it !in 5..720 } == true -> "时长需要 5–720 分钟"
            dateRejected(args, "scheduledDate") != null -> dateRejected(args, "scheduledDate")
            dateRejected(args, "dueDate") != null -> dateRejected(args, "dueDate")
            args.optString("category").isNotBlank() && resolveCategory(args.optString("category")) == null ->
                "类别只认 COURSE、EXTRACURRICULAR、OFFICE、LEISURE"
            updatedFields(args) == null ->
                "没说改什么：给 name、durationMinutes、scheduledDate、dueDate 或 category 里的至少一项"
            else -> null
        }
    }

    private fun overlapRejected(start: Int, end: Int, excludeSegmentId: String?, date: LocalDate = activeDate): String? {
        fun clashes(entry: TimelineEntry) = entry.kind == TimelineKind.TASK && entry.id != excludeSegmentId &&
            entry.startMinute < end && start < entry.endMinute &&
            !((entry.startMinute <= start && end <= entry.endMinute) || (start <= entry.startMinute && entry.endMinute <= end))
        val hit = occupiedOn(date).firstOrNull(::clashes) ?: return null
        return "${dayPhrase(date)}${formatMinute(start)}-${formatMinute(end)} 与「${hit.title}」" +
            "（${span(hit.startMinute, hit.endMinute)}）撞车，换个时段，或先跟用户说明要重叠"
    }

    private companion object {
        /** 用户没说时长时先按一小时排上，卡上再改；追问一轮的代价比猜错一次大。 */
        const val DEFAULT_PLAN_MINUTES = 60

        val ROUTINE_EVERY_DAY = Regex("每天|每日|天天|每一天")
        val ROUTINE_WORKDAYS = Regex("工作日|平日|平时|周一到周五|周一至周五|星期一到星期五|礼拜一到礼拜五")
        val ROUTINE_WEEKEND = Regex("周末|周六周日|周六、周日|星期六日")
        val ROUTINE_WEEKLY_DAY = Regex("每(?:周|星期|礼拜)\\s*([一二三四五六日天])")
        val ROUTINE_DAY_RANGE = Regex(
            "(?:周|星期|礼拜)\\s*([一二三四五六日天])\\s*(?:到|至|-|~|～)\\s*(?:周|星期|礼拜)?\\s*([一二三四五六日天])",
        )
        val ROUTINE_MORNING = Regex("早上|早晨|上午|清晨|一大早|一早")
        val ROUTINE_AFTERNOON = Regex("下午|午后|中午")
        val ROUTINE_EVENING = Regex("晚上|傍晚|夜里|夜间|晚间|今晚")
        val ROUTINE_EARLY = Regex("凌晨|早上|早晨|上午|清早")
        val ROUTINE_LATE = Regex("下午|晚上|傍晚|夜里|夜间|晚间|今晚|中午")
        val ROUTINE_CROSS_MIDNIGHT = Regex("凌晨|半夜|深夜|次日|第二天")
        val ROUTINE_END_ANCHORED = Regex(
            "(?:(?:到|至|直到|截止到|结束于?)\\s*(?:下午|晚上|上午|中午|早上|凌晨)?\\s*" +
                "(?:\\d{1,2}[:：]\\d{2}|\\d{1,2}\\s*(?:点|时)|[一二两三四五六七八九十]{1,3}\\s*(?:点|时)))" +
                "|(?:(?:\\d{1,2}[:：]\\d{2}|\\d{1,2}\\s*(?:点|时)|[一二两三四五六七八九十]{1,3}\\s*(?:点|时))" +
                "\\s*(?:结束|截止|收尾))",
        )
        val ROUTINE_CLOCK_COLON = Regex("(\\d{1,2})[:：](\\d{2})")
        val ROUTINE_CLOCK_WORD = Regex(
            "([一二两三四五六七八九十]{1,3}|\\d{1,2})\\s*(?:点|(?<![小个])时)\\s*" +
                "(半|一刻|三刻|(?:[一二两三四五六七八九十]{1,2}|\\d{1,2})\\s*分?)?",
        )
        val ROUTINE_90_MIN = Regex("一个半小时|一个小时半|一小时半")
        val ROUTINE_150_MIN = Regex("两个半小时|两个小时半|两小时半")
        val ROUTINE_HALF_HOUR = Regex("半小时|半个小时")
        val ROUTINE_MINUTES = Regex("(\\d+(?:\\.\\d+)?|[一二两三四五六七八九十]{1,3})\\s*(?:分钟|分|min)", RegexOption.IGNORE_CASE)
        val ROUTINE_HOURS = Regex("(\\d+(?:\\.\\d+)?|[一二两三四五六七八九十]{1,3})\\s*(?:个?小时|钟头|h)", RegexOption.IGNORE_CASE)
    }
}
