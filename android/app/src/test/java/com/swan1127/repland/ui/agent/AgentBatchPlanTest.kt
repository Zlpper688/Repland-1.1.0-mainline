package com.swan1127.repland.ui.agent

import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.AgentChatMessage
import com.swan1127.repland.domain.model.AgentConversationEngine
import com.swan1127.repland.domain.model.AgentConversationEvent
import com.swan1127.repland.domain.model.AgentSessionState
import com.swan1127.repland.domain.model.AgentToolCall
import com.swan1127.repland.domain.model.AgentToolSpec
import com.swan1127.repland.domain.model.AgentTurn
import com.swan1127.repland.domain.model.AgentTurnContext
import com.swan1127.repland.domain.model.AgentTurnModel
import com.swan1127.repland.domain.model.ChatDayPlanner
import com.swan1127.repland.domain.model.MemoryReference
import com.swan1127.repland.domain.model.MemorySignal
import com.swan1127.repland.domain.model.QuickAvailability
import com.swan1127.repland.domain.model.SchedulingStyle
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskDraft
import com.swan1127.repland.domain.model.TaskPriority
import com.swan1127.repland.domain.model.TaskStatus
import com.swan1127.repland.domain.model.TimelineEntry
import com.swan1127.repland.domain.model.TimelineKind
import com.swan1127.repland.ui.components.taskDateLabel
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 群聊批量流程：一次意图 = 一个工具调用 = 一张确认卡 = 一次写入。
 * 跑的是真实 toolbox，所以卡上列出的时间就是最终落到时间轴上的时间。
 */
class AgentBatchPlanTest {
    private val day = LocalDate.of(2026, 10, 23)
    private val mathClass = TimelineEntry("course-math", "数学课", TimelineKind.COURSE, day, 720, 780)
    private val allDay = listOf(TimelineEntry("course-all", "全天课", TimelineKind.COURSE, day, 480, 1_320))
    private val lab = Task(
        id = "task-lab",
        description = "实验报告",
        displayName = "实验报告",
        category = TaskCategory.COURSE,
        userPriority = TaskPriority.UNSPECIFIED,
        estimatedDays = 1,
        totalDurationMinutes = 90,
        dueDate = null,
        status = TaskStatus.NOT_STARTED,
        completionSummary = null,
        actualDurationMinutes = null,
        progressPercent = null,
        postponeCount = 0,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
    )

    private val savedDrafts = mutableListOf<TaskDraft>()
    /** 落点带日期：跨天批量只断言时刻，看不出这一批到底排到了哪一天。 */
    private data class Placed(val taskId: String, val date: LocalDate, val start: Int, val end: Int)

    /** 挪动记录：date 为空表示用户没点名换天，那一件就该留在原来那一天。 */
    private data class Moved(val segmentId: String, val date: LocalDate?, val start: Int, val end: Int)

    private val placed = mutableListOf<Placed>()
    private val placedSegments: List<Triple<String, Int, Int>>
        get() = placed.map { Triple(it.taskId, it.start, it.end) }
    private val movedPlacements = mutableListOf<Moved>()
    private val updatedDrafts = mutableListOf<TaskDraft>()
    /** 助手登记下来的每一档可用时间：周重复还是只这一天、空闲还是占用，都按写下去的原样记。 */
    private val registrations = mutableListOf<QuickAvailability>()
    private var availabilityFailure: String? = null
    private val writeOrder = mutableListOf<String>()
    /** 清单是真的会刷新的：建完的下一轮就该能在任务里读到，不然改不了也排不了。 */
    private val liveTasks = mutableListOf<Task>()
    /** 假装是仓储：挂了这条的任务，写入时被拒并给人话理由。 */
    private val rejectedFailures = mutableMapOf<String, String>()
    private val rejectedNames = mutableSetOf<String>()
    private var rejectAll: String? = null
    private var updateFailure: String? = null

    @Before
    fun clearSessionLedger() {
        GroupChatSession.createdTasks = emptyList()
        GroupChatSession.scheduledTaskIds = emptySet()
        savedDrafts.clear()
        placed.clear()
        movedPlacements.clear()
        updatedDrafts.clear()
        registrations.clear()
        writeOrder.clear()
        liveTasks.clear()
        rejectedFailures.clear()
        rejectedNames.clear()
        rejectAll = null
        updateFailure = null
        availabilityFailure = null
    }

    private fun toolbox(
        occupied: List<TimelineEntry>,
        tasks: List<Task> = emptyList(),
        style: SchedulingStyle = SchedulingStyle.PACKED,
        /** 另一天的实况；没给的日子等于"看不到"，助手必须挡回去而不是当成空一天。 */
        otherDays: Map<LocalDate, List<TimelineEntry>> = emptyMap(),
        /** 登记下来的可用档（空闲模板/当日例外）；没给就是"没登记"，不许当成全天有空。 */
        available: Map<LocalDate, List<IntRange>> = emptyMap(),
        facts: List<Pair<String, String>> = emptyList(),
        episodes: List<String> = emptyList(),
        profile: MemoryReference = MemoryReference.empty(day),
    ) = AgentAppToolbox(
        activeDate = day,
        tasksProvider = { tasks + liveTasks },
        occupiedProvider = { occupied },
        onSaveTasks = { drafts ->
            writeOrder += "save"
            savedDrafts += drafts
            liveTasks += drafts.map { draft ->
                Task(
                    id = requireNotNull(draft.id),
                    description = draft.description,
                    displayName = draft.displayName,
                    category = draft.category,
                    userPriority = draft.userPriority,
                    estimatedDays = draft.estimatedDays,
                    totalDurationMinutes = draft.totalDurationMinutes,
                    dueDate = draft.dueDate,
                    status = TaskStatus.NOT_STARTED,
                    completionSummary = null,
                    actualDurationMinutes = null,
                    progressPercent = null,
                    postponeCount = 0,
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                    scheduledForDate = draft.scheduledForDate,
                )
            }
            drafts.isNotEmpty()
        },
        onStartTask = {},
        onCompleteTask = { _, _, _, _ -> },
        onCancelTask = {},
        onPostponeTask = { _, _ -> },
        onRecordFeedback = { _, _ -> },
        onPlaceTask = { taskId, date, start, end, _, _, _ ->
            writeOrder += "place"
            // 时间轴引用的是清单里的任务 id：清单没落库就排期，Room 那边只会读不到任务。
            val known = savedDrafts.mapNotNull(TaskDraft::id) + tasks.map(Task::id)
            assertTrue("place 早于 save：$taskId", taskId in known)
            val byName = savedDrafts.firstOrNull { it.id == taskId }?.description
            val failure = rejectedFailures.remove(taskId) ?: rejectAll
                ?: byName?.takeIf { it in rejectedNames }?.let { "这个时段刚好被别的安排占满，请换一个。" }
            if (failure == null) placed += Placed(taskId, date, start, end)
            failure
        },
        onMovePlacement = { segmentId, date, start, end, _ ->
            writeOrder += "move"
            movedPlacements += Moved(segmentId, date, start, end)
            null
        },
        onRemovePlacement = {},
        onSetSegmentReminder = { _, _, _ -> },
        onStartFocus = {},
        schedulingStyle = { style },
        timelineForDate = { date -> otherDays[date] },
        onUpdateTask = { draft ->
            writeOrder += "update"
            updatedDrafts += draft
            updateFailure
        },
        availableRangesForDate = { date ->
            // 登记完就该在容量账里看到：假仓储把两半接起来，才测得出「登记 → 读到 → 排得下」这条链。
            available[date].orEmpty() + registrations
                .filter { it.date == date || (it.repeatWeekly && it.date.dayOfWeek == date.dayOfWeek) }
                .map { it.startMinute until it.endMinute }
        },
        memoryFacts = { facts },
        memoryProfile = { profile },
        memoryEpisodes = { episodes },
        onSaveAvailability = { value ->
            writeOrder += "availability"
            registrations += value
            availabilityFailure
        },
    )

    private fun batchSpec(
        occupied: List<TimelineEntry> = listOf(mathClass),
        tasks: List<Task> = emptyList(),
        otherDays: Map<LocalDate, List<TimelineEntry>> = emptyMap(),
    ): AgentToolSpec = toolbox(occupied, tasks, otherDays = otherDays).specs().first { it.name == "plan_tasks" }

    private fun batchArgs(items: String) = JSONObject("""{"items":$items}""")

    private fun toolSpec(
        name: String,
        occupied: List<TimelineEntry> = listOf(mathClass),
        tasks: List<Task> = listOf(lab),
        otherDays: Map<LocalDate, List<TimelineEntry>> = emptyMap(),
    ) = toolbox(occupied, tasks, otherDays = otherDays).specs().first { it.name == name }

    private val timeRange = Regex("""\d{2}:\d{2}-\d{2}:\d{2}""")

    @Test
    fun three_items_become_one_card_that_already_carries_every_slot() {
        val args = batchArgs(
            """[{"name":"复习高数","durationMinutes":120},""" +
                """{"name":"英语真题","durationMinutes":60},""" +
                """{"name":"整理笔记","durationMinutes":90}]""",
        )
        val spec = batchSpec()
        assertNull(spec.validate!!.invoke(args))
        val summary = spec.summarize!!.invoke(args)
        assertTrue(summary, summary.startsWith("建 3 项并排期："))
        listOf("复习高数", "英语真题", "整理笔记").forEach { name ->
            assertTrue("$summary 少了 $name", name in summary)
        }
        assertEquals(3, timeRange.findAll(summary).count())
    }

    @Test
    fun confirmed_batch_saves_the_list_once_and_places_around_the_class() = runTest {
        val args = """[{"name":"复习高数","durationMinutes":120},{"name":"英语真题"},{"name":"整理笔记","durationMinutes":90}]"""
        val result = toolbox(listOf(mathClass)).runWrite(
            AgentAction("a1", "plan_tasks", "批量建与排", "批量", batchArgs(args).toString()),
        )

        assertTrue(result.summary, result.ok)
        assertTrue(result.followUpActions.isEmpty())
        // 还有空档时绝不嵌套：挂在课旁边只是把时间排开，不需要动已有安排。
        assertFalse(result.summary, "挂进" in result.summary)
        // 一次确认：清单只写一次，三项各自排在课程之外的时段，不再甩出第二轮待排卡片。
        assertEquals(listOf("save", "place", "place", "place"), writeOrder)
        assertEquals(3, savedDrafts.size)
        assertEquals(day, savedDrafts.first().scheduledForDate)
        assertEquals(60, savedDrafts[1].totalDurationMinutes)
        assertEquals(3, placedSegments.size)
        placedSegments.forEach { (_, start, end) ->
            assertTrue("$start-$end 压到了 12:00-13:00 的课", end <= 720 || start >= 780)
            assertTrue(end > start)
        }
    }

    /**
     * 真机踩过的一次：08:00–11:00 已有一段锁定的任务安排，规划器只把它当成"占一条轨"，
     * 三项全压在它身上另开轨；确认时要么被硬规矩整批拒绝，要么一件弹一次「确认调整」，
     * 一次确认的承诺当场作废。
     */
    @Test
    fun a_locked_block_is_not_offered_again_even_on_another_lane() = runTest {
        val locked = TimelineEntry(
            id = "segment-lab", title = "实验报告", kind = TimelineKind.TASK, date = day,
            startMinute = 480, endMinute = 660, taskId = "task-lab", trackId = "track-lab",
            locked = true,
        )
        val args = batchArgs(
            """[{"name":"写实验报告","durationMinutes":90},{"name":"复习高数","durationMinutes":60},{"name":"背单词","durationMinutes":30}]""",
        )
        assertNull(batchSpec(occupied = listOf(locked), tasks = listOf(lab)).validate!!.invoke(args))

        val result = toolbox(listOf(locked), tasks = listOf(lab)).runWrite(
            AgentAction("a4", "plan_tasks", "批量建与排", "批量", args.toString()),
        )
        assertTrue(result.summary, result.ok)
        assertEquals(3, placedSegments.size)
        placedSegments.forEach { (_, start, end) ->
            assertTrue("$start-$end 压到了 08:00-11:00 的锁定段", end <= 480 || start >= 660)
        }
    }

    /**
     * 用户要的嵌套：课把一天铺满、连 5 分钟空档都不剩时，agent 不该把"放不下"退回去，
     * 而是把事项完整放进当天已有的一块任务安排里当下属——日时间轴本来就这么画，写入端也把
     * 这种重叠当成"同一时间做事"，不需要第二次确认。
     */
    @Test
    fun a_packed_day_nests_the_items_inside_an_existing_block() = runTest {
        val host = TimelineEntry(
            id = "segment-mid", title = "期中复习", kind = TimelineKind.TASK, date = day,
            startMinute = 540, endMinute = 900, taskId = "task-mid", trackId = "track-mid",
        )
        val packed = listOf(
            TimelineEntry("course-morning", "早自习", TimelineKind.COURSE, day, 480, 540),
            host,
            TimelineEntry("course-afternoon", "下午连排", TimelineKind.COURSE, day, 900, 1_320),
        )
        val args = batchArgs(
            """[{"name":"整理错题","durationMinutes":45},{"name":"背单词","durationMinutes":30},{"name":"练听力","durationMinutes":30}]""",
        )
        assertNull(batchSpec(occupied = packed).validate!!.invoke(args))
        val card = batchSpec(occupied = packed).summarize!!.invoke(args)
        assertTrue(card, "挂进「期中复习」" in card)

        val result = toolbox(packed).runWrite(
            AgentAction("a5", "plan_tasks", "批量建与排", "批量", args.toString()),
        )
        assertTrue(result.summary, result.ok)
        assertTrue(result.summary, "挂进「期中复习」" in result.summary)
        assertEquals(3, placedSegments.size)
        placedSegments.forEach { (_, start, end) ->
            assertTrue("$start-$end 没挂进 09:00-15:00 这块", start >= 540 && end <= 900)
            assertTrue("$start-$end 与父块完全同时，会被写成需要单独确认的并列事件", start > 540 || end < 900)
        }
        // 三件下属各占父块里一段时间，挤在同一段上日视图就看不出谁在做什么。
        val spans = placedSegments.map { it.second to it.third }.sortedWith(compareBy({ it.first }, { it.second }))
        spans.zipWithNext().forEach { (earlier, later) ->
            assertTrue("下属互相压到了：$earlier $later", earlier.second <= later.first)
        }
    }

    /** 锁定、不可避免、已结束的块不能当父事件：写入端对这种重叠是直接拒绝的，卡上承诺了也落不了地。 */
    @Test
    fun a_protected_block_is_never_offered_as_a_host() = runTest {
        val guarded = TimelineEntry(
            id = "segment-guard", title = "导师约谈", kind = TimelineKind.TASK, date = day,
            startMinute = 540, endMinute = 900, taskId = "task-guard", trackId = "track-guard",
            unavoidable = true,
        )
        val packed = listOf(
            TimelineEntry("course-morning", "早自习", TimelineKind.COURSE, day, 480, 540),
            guarded,
            TimelineEntry("course-afternoon", "下午连排", TimelineKind.COURSE, day, 900, 1_320),
        )
        val args = batchArgs("""[{"name":"背单词","durationMinutes":30}]""")
        val rejection = batchSpec(occupied = packed).validate!!.invoke(args)
        assertTrue(rejection.orEmpty(), "今天放不下" in rejection.orEmpty())

        val result = toolbox(packed).runWrite(AgentAction("a6", "plan_tasks", "批量建与排", "批量", args.toString()))
        assertFalse(result.ok)
        assertTrue(result.summary, "背单词" in result.summary)
        assertTrue(placedSegments.toString(), placedSegments.isEmpty())
    }

    /**
     * 排期是替活人排的：白天铺满课、只剩深夜一块任务时，助手不能说"那就挂进凌晨那件事里"。
     * 宁可回一句今天放不下、明天再排——把复习压到睡眠窗口里，削掉的正是这个工具承诺的记忆效果。
     */
    @Test
    fun a_full_day_does_not_nest_work_into_the_sleep_hours() = runTest {
        val nightHost = TimelineEntry(
            id = "segment-night", title = "赶作业", kind = TimelineKind.TASK, date = day,
            startMinute = 1_380, endMinute = 1_440, taskId = "task-night", trackId = "track-night",
        )
        val packed = listOf(
            TimelineEntry("course-day", "全天课", TimelineKind.COURSE, day, 480, 1_320),
            nightHost,
        )
        val args = batchArgs("""[{"name":"背单词","durationMinutes":30}]""")
        val rejection = batchSpec(occupied = packed).validate!!.invoke(args)
        assertTrue(rejection.orEmpty(), "今天放不下" in rejection.orEmpty())

        val result = toolbox(packed).runWrite(
            AgentAction("a-night-nest", "plan_tasks", "批量建与排", "批量", args.toString()),
        )
        assertFalse(result.ok)
        assertTrue(placedSegments.toString(), placedSegments.isEmpty())
    }

    /**
     * 真机踩过的一次：模型把「每天跑步20分钟」自己标成 08:00 点名时间，而 08:00–11:00 是一段
     * 不可避免的安排。写入端当场拒绝那一段，卡上却已经把它算进「排入 4 项」——
     * 用户看到的成功是假的。压到锁定/不可避免的点名时间退回找空档，卡上跟着改口成助手挑的，
     * 写进去的和卡上写的才是同一件事。
     */
    @Test
    fun a_pinned_start_on_a_protected_block_falls_back_to_a_real_gap() = runTest {
        val guarded = TimelineEntry(
            id = "segment-guard", title = "实验报告", kind = TimelineKind.TASK, date = day,
            startMinute = 480, endMinute = 660, taskId = "task-guard", trackId = "track-guard",
            unavoidable = true,
        )
        val args = batchArgs("""[{"name":"每天跑步20分钟","durationMinutes":20,"start":"08:00"}]""")
        val action = AgentAction("p1", "plan_tasks", "批量建与排", "批量", args.toString())
        val rows = toolbox(listOf(guarded)).batchPreview(action)

        assertEquals(1, rows.size)
        assertNull("压到不可避免还标成用户点名：${rows[0].userPickedStartMinute}", rows[0].userPickedStartMinute)
        assertTrue("没退回空档：${rows[0].startMinute}", rows[0].startMinute >= 660)

        val result = toolbox(listOf(guarded)).runWrite(action)
        assertTrue(result.summary, result.ok)
        assertEquals(1, placedSegments.size)
        assertEquals(rows[0].startMinute to rows[0].endMinute, placedSegments[0].second to placedSegments[0].third)
        assertTrue(result.summary, "排入 1 项" in result.summary)
    }

    /**
     * 用户要的嵌套还有一种：点名时间本来就完整落在某个已有块里面。这种落点不只要写得进去，
     * 卡上还必须写明挂进了谁——不然只看到两件同一时间开始，读不出谁是父谁是下属。
     */
    @Test
    fun a_pinned_start_inside_an_existing_block_is_listed_as_nested() {
        val host = TimelineEntry(
            id = "segment-mid", title = "期中复习", kind = TimelineKind.TASK, date = day,
            startMinute = 540, endMinute = 900, taskId = "task-mid", trackId = "track-mid",
        )
        val args = batchArgs("""[{"name":"听写练习","durationMinutes":10,"start":"10:00"}]""")
        val rows = toolbox(listOf(mathClass, host)).batchPreview(
            AgentAction("p2", "plan_tasks", "批量建与排", "批量", args.toString()),
        )

        assertEquals(1, rows.size)
        assertEquals(600, rows[0].startMinute)
        assertEquals("点名时间没标成用户挑的", 600, rows[0].userPickedStartMinute)
        assertEquals("期中复习", rows[0].nestedUnderTitle)
    }

    /**
     * 08:00–22:00 是助手替「没点名时间」的事项挑空档的搜索区间，不是一天的合法边界。
     * 用户说 01:00 睡觉，被抬到 08:00 之后卡片还写着「你点名的时间 08:00」——
     * 同一张卡上自相矛盾，而且用户看不出任何一处出错。
     */
    @Test
    fun a_pinned_start_outside_the_searching_window_stays_where_the_user_said() {
        val rows = toolbox(listOf(mathClass)).batchPreview(
            AgentAction(
                "p-night", "plan_tasks", "批量建与排", "批量",
                batchArgs("""[{"name":"睡眠","durationMinutes":60,"start":"01:00"}]""").toString(),
            ),
        )

        assertEquals(1, rows.size)
        assertEquals("凌晨点名的时间被搜索区间抬走了", 60, rows[0].startMinute)
        assertEquals(120, rows[0].endMinute)
        assertEquals("抬走之后还冒充用户点名", 60, rows[0].userPickedStartMinute)
    }

    /** 深夜同理：23:00 起的一小时要完整留在当天 24:00 之前，而不是被 22:00 判成放不下。 */
    @Test
    fun a_pinned_start_late_in_the_night_is_not_pushed_back_into_the_window() {
        val rows = toolbox(listOf(mathClass)).batchPreview(
            AgentAction(
                "p-late", "plan_tasks", "批量建与排", "批量",
                batchArgs("""[{"name":"夜跑","durationMinutes":60,"start":"23:00"}]""").toString(),
            ),
        )

        assertEquals(1, rows.size)
        assertEquals(23 * 60, rows[0].startMinute)
        assertEquals(ChatDayPlanner.MINUTES_IN_DAY, rows[0].endMinute)
    }

    @Test
    fun one_card_can_create_two_and_place_an_existing_task_in_the_same_pass() = runTest {
        val args = batchArgs(
            """[{"name":"复习高数","durationMinutes":120},{"name":"英语真题"},{"taskId":"实验报告"}]""",
        )
        val spec = batchSpec(tasks = listOf(lab))
        assertNull(spec.validate!!.invoke(args))
        val summary = spec.summarize!!.invoke(args)
        assertTrue(summary, "「实验报告」" in summary)
        assertTrue(summary, summary.startsWith("排 3 项（新建 2 项）："))
        assertEquals(3, timeRange.findAll(summary).count())

        val result = toolbox(listOf(mathClass), tasks = listOf(lab)).runWrite(
            AgentAction("a3", "plan_tasks", "批量建与排", "批量", args.toString()),
        )
        assertTrue(result.summary, result.ok)
        // 已有的那一件只排不建：清单只写进 2 项，时间轴上落 3 段。
        assertEquals(2, savedDrafts.size)
        assertEquals(3, placedSegments.size)
        assertTrue(placedSegments.any { it.first == "task-lab" })
    }

    @Test
    fun a_full_day_is_refused_before_the_card_is_drawn() = runTest {
        val rejection = batchSpec(allDay).validate!!.invoke(batchArgs("""[{"name":"复习高数","durationMinutes":120}]"""))
        assertTrue(rejection.orEmpty(), rejection.orEmpty().contains("今天放不下"))

        // 空 items 直接执行也不能碰清单和时间轴。
        val forced = toolbox(allDay).runWrite(AgentAction("a2", "plan_tasks", "批量建与排", "批量", """{"items":[]}"""))
        assertFalse(forced.ok)
        assertTrue(writeOrder.isEmpty())
    }

    @Test
    fun malformed_items_are_answered_with_an_editable_hint() {
        val spec = batchSpec()
        assertTrue(spec.validate!!.invoke(batchArgs("\"不是数组\"")).orEmpty().contains("JSON 数组"))
        assertTrue(spec.validate!!.invoke(batchArgs("[]")).orEmpty().contains("没解析出"))
        assertTrue(spec.validate!!.invoke(JSONObject()).orEmpty().contains("items"))
        assertTrue(spec.validate!!.invoke(batchArgs("""[{"durationMinutes":60}]""")).orEmpty().contains("既没有 name"))
        assertTrue(
            spec.validate!!.invoke(batchArgs("""[{"taskId":"没听说过的事"}]""")).orEmpty().contains("不在当前清单里"),
        )
    }

    /**
     * 用户要的批量确认形态：卡上把每一件先列出来，逐件挑时间，最后一次确认。
     * 列表必须和摘要、写入同源，否则卡上写的时间和落进时间轴的时间会分家。
     */
    @Test
    fun the_batch_card_lists_every_item_with_the_slot_it_will_get() {
        val args = batchArgs(
            """[{"name":"复习高数","durationMinutes":120},{"name":"英语真题","durationMinutes":60},{"name":"整理笔记","durationMinutes":90}]""",
        )
        val action = AgentAction("b1", "plan_tasks", "批量建与排", "批量", args.toString())
        val rows = toolbox(listOf(mathClass)).batchPreview(action)

        assertEquals(3, rows.size)
        assertEquals(listOf(0, 1, 2), rows.map { it.index })
        assertEquals(listOf("复习高数", "英语真题", "整理笔记"), rows.map { it.name })
        rows.forEach { row ->
            assertTrue("${row.name} 的时长没带上", row.durationMinutes > 0)
            assertTrue("${row.name} 的时段不对", row.endMinute == row.startMinute + row.durationMinutes)
            assertNull("${row.name} 没点名时间却标成了用户挑选", row.userPickedStartMinute)
        }
        // 卡上每一件的时间，就是摘要里那件事的时间。
        val summary = batchSpec().summarize!!.invoke(args)
        rows.forEach { row ->
            assertTrue("${row.name} 在卡上写 ${row.startMinute}", chatFormatMinute(row.startMinute) in summary)
        }
        // 非批量工具不配逐件列表。
        assertTrue(
            toolbox(listOf(mathClass)).batchPreview(AgentAction("b2", "create_task", "建一项", "建", """{"name":"x"}""")).isEmpty(),
        )
    }

    /** 点名一件的时间只改写那一件：其余件由规划器按当天实况重排，取消点名则整套回到助手挑的那套。 */
    @Test
    fun picking_one_item_time_rewrites_only_that_item_and_reshuffles_the_rest() {
        val args = batchArgs(
            """[{"name":"复习高数","durationMinutes":120},{"name":"英语真题","durationMinutes":60}]""",
        )
        val action = AgentAction("b3", "plan_tasks", "批量建与排", "批量", args.toString())
        val before = toolbox(listOf(mathClass)).batchPreview(action)

        val edited = AgentAction(
            action.id, action.toolName, action.label, action.summary,
            argsWithStart(action.argumentsJson, index = 0, startOrNull = "15:30"),
        )
        val after = toolbox(listOf(mathClass)).batchPreview(edited)

        assertEquals("点名的那件没按用户说的落", 930, after[0].startMinute)
        assertEquals(930, after[0].userPickedStartMinute)
        assertEquals("15:30", JSONObject(edited.argumentsJson).getJSONArray("items").getJSONObject(0).getString("start"))
        assertEquals(930 + 120, after[0].endMinute)
        // 没点名的一件排在它后面，而不是留在原地跟它撞车。
        assertTrue("另一件没跟着重排：${after[1].startMinute}", after[1].startMinute >= after[0].endMinute)
        assertNull(after[1].userPickedStartMinute)
        assertTrue("卡上摘要没跟着改", "15:30-17:30" in batchSpec().summarize!!.invoke(JSONObject(edited.argumentsJson)))

        // 取消点名：start 摘掉，两件都回到助手挑的那一套。
        val cleared = argsWithStart(edited.argumentsJson, index = 0, startOrNull = null)
        assertFalse("start 没摘干净", JSONObject(cleared).getJSONArray("items").getJSONObject(0).has("start"))
        val restored = toolbox(listOf(mathClass)).batchPreview(edited.copy(argumentsJson = cleared))
        assertEquals(before.map { it.index to it.startMinute }, restored.map { it.index to it.startMinute })
    }

    /** 时长与开始时间共用同一张卡：改一件只落那一件，另一件不动，结束时间跟着新时长走。 */
    @Test
    fun picking_one_item_duration_rewrites_only_that_item() {
        val args = batchArgs(
            """[{"name":"复习高数","durationMinutes":120},{"name":"英语真题","durationMinutes":60}]""",
        )
        val action = AgentAction("b4", "plan_tasks", "批量建与排", "批量", args.toString())
        val edited = action.copy(
            argumentsJson = argsWithStart(action.argumentsJson, index = 0, startOrNull = null, durationMinutes = 45),
        )
        val items = JSONObject(edited.argumentsJson).getJSONArray("items")
        assertEquals(45, items.getJSONObject(0).getInt("durationMinutes"))
        assertEquals("另一件的时长被顺手动了", 60, items.getJSONObject(1).getInt("durationMinutes"))

        val rows = toolbox(listOf(mathClass)).batchPreview(edited)
        assertEquals(45, rows[0].durationMinutes)
        assertEquals(rows[0].startMinute + 45, rows[0].endMinute)
    }

    /** 卡片列表按 batchItems 的口径对齐：模型写成单个对象或掺进非对象元素时不能数错位置。 */
    @Test
    fun args_rewrite_tolerates_the_shapes_the_model_actually_sends() {
        val single = """{"items":{"name":"复习高数","durationMinutes":60}}"""
        assertEquals(
            "09:00",
            JSONObject(argsWithStart(single, index = 0, startOrNull = "09:00")).getJSONObject("items").getString("start"),
        )
        val asString = """{"items":"[{\"name\":\"复习高数\",\"durationMinutes\":60}]"}"""
        assertTrue(argsWithStart(asString, 0, "09:00").contains("09:00"))
        val withNoise = """{"items":["不是对象",{"name":"复习高数","durationMinutes":60}]}"""
        val rewritten = JSONObject(argsWithStart(withNoise, index = 0, startOrNull = "10:00")).getJSONArray("items")
        assertEquals("10:00", rewritten.getJSONObject(1).getString("start"))
        // 位置越界（比如那件已经排不进今天）就原样返回，不能凭空造一项。
        assertEquals(single, argsWithStart(single, index = 5, startOrNull = "09:00"))
    }

    /**
     * 逐件挑时间的校验必须和写入端同一口径：批量卡只确认一次，
     * 挑出来却还要第二次「确认调整」或直接被拒的时间，就不能让它保存。
     */
    @Test
    fun a_chosen_time_that_would_need_a_second_confirmation_is_refused_on_the_spot() {
        val host = TimelineEntry(
            id = "segment-mid", title = "期中复习", kind = TimelineKind.TASK, date = day,
            startMinute = 540, endMinute = 900, taskId = "task-mid", trackId = "track-mid",
        )
        val occupied = listOf(mathClass, host)

        assertTrue(
            ChatDayPlanner.chosenTimeRejected(750, 810, occupied, emptyList()).orEmpty().contains("数学课"),
        )
        assertTrue(
            ChatDayPlanner.chosenTimeRejected(870, 930, occupied, emptyList()).orEmpty().contains("再确认一次"),
        )
        // 完整落进父块里面 = 同一时间做事，写入端不再要第二次确认；卡上要说明挂在谁里。
        assertNull(ChatDayPlanner.chosenTimeRejected(600, 660, occupied, emptyList()))
        assertEquals("期中复习", ChatDayPlanner.nestedHostTitleAt(600, 660, occupied, null))
        assertNull(ChatDayPlanner.nestedHostTitleAt(600, 660, occupied, excludeTaskId = "task-mid"))
        // 跟这一批里另一件点名的时间撞了，也是当场拦住。
        assertTrue(
            ChatDayPlanner.chosenTimeRejected(600, 660, occupied, listOf(630 to 700)).orEmpty().contains("这一批里的另一件事"),
        )
        // 22:00 之后不再是禁区：那是助手的搜索区间，不是合法边界；越过午夜才是。
        assertNull(ChatDayPlanner.chosenTimeRejected(1_300, 1_380, occupied, emptyList()))
        assertTrue(
            ChatDayPlanner.chosenTimeRejected(1_400, 1_450, occupied, emptyList()).orEmpty().contains("当天之内"),
        )
    }

    /**
     * 行事风格预设：换一套就整批重排，而且两套都把每个事件排上——
     * 用户在卡上点「宽松」后列表凭空少几项，比没有这个开关更糟。
     */
    @Test
    fun a_style_shifts_the_whole_plan_without_dropping_anything() {
        val args = batchArgs(
            """[{"name":"复习高数","durationMinutes":120},{"name":"英语真题","durationMinutes":60}]""",
        )
        val packed = AgentAction("s1", "plan_tasks", "批量建与排", "批量", args.toString())
        val calm = AgentAction(packed.id, packed.toolName, packed.label, packed.summary, argsWithStyle(packed.argumentsJson, SchedulingStyle.CALM))

        val packedRows = toolbox(listOf(mathClass)).batchPreview(packed)
        val calmRows = toolbox(listOf(mathClass)).batchPreview(calm)

        assertEquals(2, packedRows.size)
        assertEquals("宽松那套丢了事件", packedRows.map { it.name }, calmRows.map { it.name })
        assertEquals(packedRows[0].startMinute, calmRows[0].startMinute)
        assertEquals(
            "宽松没把整套往后挪",
            packedRows[1].startMinute + SchedulingStyle.CALM.gapMinutes - SchedulingStyle.PACKED.gapMinutes,
            calmRows[1].startMinute,
        )
        assertEquals(SchedulingStyle.PACKED, toolbox(listOf(mathClass)).batchStyle(packed))
        assertEquals(SchedulingStyle.CALM, toolbox(listOf(mathClass)).batchStyle(calm))
        assertTrue("style 没写进参数", argsWithStyle(args.toString(), SchedulingStyle.CALM).contains("\"style\":\"calm\""))
    }

    /** 没点过风格时按存下来的那套排——预设不改变输出就只是装饰。 */
    @Test
    fun the_stored_style_is_the_default_for_a_fresh_batch() {
        val action = AgentAction(
            "s2", "plan_tasks", "批量建与排", "批量",
            batchArgs("""[{"name":"复习高数","durationMinutes":120},{"name":"英语真题","durationMinutes":60}]""").toString(),
        )
        val calm = toolbox(listOf(mathClass), style = SchedulingStyle.CALM).batchPreview(action)
        val packed = toolbox(listOf(mathClass)).batchPreview(action)

        assertTrue("存了宽松还是排满：${calm.map { it.startMinute }}", calm[1].startMinute > packed[1].startMinute)
        assertEquals(SchedulingStyle.CALM, toolbox(listOf(mathClass), style = SchedulingStyle.CALM).batchStyle(action))
    }

    @Test
    fun one_model_round_queues_exactly_one_batch_action() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(
                    text = "两件我一次排好",
                    toolCall = AgentToolCall(
                        "plan_tasks",
                        """{"items":[{"name":"复习高数","durationMinutes":120},{"name":"英语真题","durationMinutes":60}]}""",
                    ),
                ),
                AgentTurn.Reply(text = "确认卡上核对时间就行"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = AgentConversationEngine(
            chat = chat,
            toolbox = toolbox(listOf(mathClass)),
            turnTimeoutMillis = 5_000,
        ).runTurn(
            AgentSessionState(),
            "帮我加高数复习和英语真题",
            AgentTurnContext(day, listOf(mathClass)),
        ) { events += it }

        val queued = events.filterIsInstance<AgentConversationEvent.ActionQueued>().single()
        assertEquals("plan_tasks", queued.action.toolName)
        assertEquals(2, timeRange.findAll(queued.action.summary).count())
        assertEquals(listOf(queued.action), session.pendingActions)
        assertTrue(savedDrafts.isEmpty())
        assertEquals(2, chat.index)
    }

    /**
     * 真机踩过的一次假成功：单件「放入时段」的回执是当场念出来的，写入却在后面异步跑，
     * 仓储把这条拒了（任务已结束、时间放不下）也没人回头改口——卡片打勾、时间轴啥也没多。
     * 回执必须等仓储说话。
     */
    @Test
    fun a_rejected_write_is_reported_as_a_failure_instead_of_a_receipt() = runTest {
        val host = TimelineEntry(
            id = "segment-mid", title = "期中复习", kind = TimelineKind.TASK, date = day,
            startMinute = 540, endMinute = 900, taskId = "task-mid", trackId = "track-mid",
        )
        rejectAll = "任务不存在或已结束，请刷新。"
        val result = toolbox(listOf(mathClass, host), tasks = listOf(lab)).runWrite(
            AgentAction(
                "w1", "place_task", "放入时段", "把任务放到 10:00 起",
                JSONObject().put("taskId", "实验报告").put("startMinute", 600).put("endMinute", 620).toString(),
            ),
        )

        assertFalse(result.ok)
        assertTrue(result.summary, "任务不存在或已结束" in result.summary)
        assertTrue(placedSegments.toString(), placedSegments.isEmpty())
    }

    /**
     * 批量也一样不能整批背书：三件里仓储拒了一件，回执就得改口成两件，「其中几项挂进已有安排」
     * 那一句也要跟着减。用户是按卡上的数字去时间轴数的，多一件就是假成功。
     */
    @Test
    fun a_partial_batch_counts_only_the_slots_the_repository_accepted() = runTest {
        val host = TimelineEntry(
            id = "segment-mid", title = "期中复习", kind = TimelineKind.TASK, date = day,
            startMinute = 540, endMinute = 900, taskId = "task-mid", trackId = "track-mid",
        )
        val packed = listOf(
            TimelineEntry("course-morning", "早自习", TimelineKind.COURSE, day, 480, 540),
            host,
            TimelineEntry("course-afternoon", "下午连排", TimelineKind.COURSE, day, 900, 1_320),
        )
        val args = batchArgs(
            """[{"name":"整理错题","durationMinutes":45},{"name":"背单词","durationMinutes":30},{"name":"练听力","durationMinutes":30}]""",
        )
        rejectedNames += "背单词"

        val result = toolbox(packed).runWrite(AgentAction("a6", "plan_tasks", "批量建与排", "批量", args.toString()))

        assertTrue(result.summary, result.ok)
        assertEquals(2, placedSegments.size)
        assertTrue(result.summary, "排入 2 项" in result.summary)
        assertFalse(result.summary, "排入 3 项" in result.summary)
        assertTrue(result.summary, "没排上：「背单词」" in result.summary)
        assertTrue(result.summary, "其中 2 项挂进已有安排里同时做" in result.summary)
    }

    /**
     * 用户说的"明天"必须真的落到明天：卡上的日期、写进清单的计划日、时间轴上那一段所在的那一天，
     * 三处得是同一天，而且让开的要是明天的课，不是今天这节。
     */
    @Test
    fun a_batch_planned_for_another_day_lands_on_that_day() = runTest {
        val tomorrow = day.plusDays(1)
        val englishClass = TimelineEntry("course-eng", "英语课", TimelineKind.COURSE, tomorrow, 600, 660)
        val other = mapOf(tomorrow to listOf(englishClass))
        val args = batchArgs("""[{"name":"复习高数","durationMinutes":120}]""").put("date", "明天")
        val action = AgentAction("d1", "plan_tasks", "批量建与排", "批量", args.toString())
        val tb = toolbox(listOf(mathClass), otherDays = other)

        val spec = batchSpec(otherDays = other)
        assertNull(spec.validate!!.invoke(args))
        val card = spec.summarize!!.invoke(args)
        assertTrue("卡上没写落在哪天：$card", "到 ${taskDateLabel(tomorrow)}" in card)
        assertEquals(taskDateLabel(tomorrow), tb.batchDayLabel(action))
        assertEquals(listOf(englishClass), tb.batchOccupied(action))
        // 排在界面那天时不报日期，实况也读的是界面这天。
        val plain = action.copy(argumentsJson = batchArgs("""[{"name":"复习高数","durationMinutes":120}]""").toString())
        assertNull("排在界面那天还多报一个日期", tb.batchDayLabel(plain))
        assertEquals(listOf(mathClass), tb.batchOccupied(plain))

        val rows = tb.batchPreview(action)
        assertEquals(1, rows.size)
        assertTrue("没让开明天的课：${rows[0].startMinute}-${rows[0].endMinute}",
            rows[0].endMinute <= 600 || rows[0].startMinute >= 660)

        val result = tb.runWrite(action)
        assertTrue(result.summary, result.ok)
        assertEquals(1, placed.size)
        assertEquals(tomorrow, placed[0].date)
        assertEquals(rows[0].startMinute to rows[0].endMinute, placed[0].start to placed[0].end)
        assertEquals(tomorrow, savedDrafts.single().scheduledForDate)
        assertTrue(result.summary, taskDateLabel(tomorrow) in result.summary)
        assertTrue(result.summary, "已建 1 项、排入 1 项" in result.summary)
        assertFalse(result.summary, "今天" in result.summary)
    }

    /** 看不见的那一天不能当成空的一天：拿空一天去排，排出来的每一段都以为是自由的。 */
    @Test
    fun a_day_that_cannot_be_seen_is_refused_rather_than_treated_as_empty() = runTest {
        val tomorrow = day.plusDays(1)
        val args = batchArgs("""[{"name":"复习高数","durationMinutes":60}]""").put("date", "明天")
        val rejection = batchSpec().validate!!.invoke(args)

        assertTrue(rejection.orEmpty(), "看不到" in rejection.orEmpty())
        assertTrue(rejection.orEmpty(), taskDateLabel(tomorrow) in rejection.orEmpty())

        // 绕过准入直接执行也不许写：卡是按另一天早先算的，写入时看不见就得停手。
        val forced = toolbox(listOf(mathClass)).runWrite(
            AgentAction("d2", "plan_tasks", "批量建与排", "批量", args.toString()),
        )
        assertFalse(forced.ok)
        assertTrue(writeOrder.isEmpty())
    }

    /** 撞不撞车要按被点名那天的课表判：今天排不下的时段，明天可能正好空着。 */
    @Test
    fun a_slot_taken_here_can_still_be_used_on_the_day_the_user_named() = runTest {
        val tomorrow = day.plusDays(1)
        val midday = TimelineEntry(
            id = "segment-mid", title = "期中复习", kind = TimelineKind.TASK, date = day,
            startMinute = 720, endMinute = 780, taskId = "task-mid", trackId = "track-mid",
        )
        val englishHomework = TimelineEntry(
            id = "segment-eng", title = "英语作业", kind = TimelineKind.TASK, date = tomorrow,
            startMinute = 600, endMinute = 660, taskId = "task-eng", trackId = "track-eng",
        )
        val tb = toolbox(listOf(midday), tasks = listOf(lab), otherDays = mapOf(tomorrow to listOf(englishHomework)))
        val place = tb.specs().first { it.name == "place_task" }

        val here = JSONObject("""{"taskId":"实验报告","startMinute":"12:30","endMinute":"13:30"}""")
        val squeezed = place.validate!!.invoke(here)
        assertTrue(squeezed.orEmpty(), "期中复习" in squeezed.orEmpty())
        assertTrue(squeezed.orEmpty(), "今天" in squeezed.orEmpty())

        val there = JSONObject("""{"taskId":"实验报告","startMinute":"12:30","endMinute":"13:30","date":"明天"}""")
        assertNull(place.validate!!.invoke(there))
        val alsoTaken = JSONObject("""{"taskId":"实验报告","startMinute":"10:30","endMinute":"11:30","date":"明天"}""")
        val clash = place.validate!!.invoke(alsoTaken)
        assertTrue(clash.orEmpty(), "英语作业" in clash.orEmpty())
        assertTrue(clash.orEmpty(), taskDateLabel(tomorrow) in clash.orEmpty())

        val result = tb.runWrite(AgentAction("d3", "place_task", "放入时段", "放到明天", there.toString()))
        assertTrue(result.summary, result.ok)
        assertEquals(tomorrow, placed.single().date)
        assertTrue(result.summary, "已把任务放入 ${taskDateLabel(tomorrow)} 12:30-13:30" in result.summary)
    }

    /**
     * 换天只在用户点名时发生：没写 date 就是把同一天里的这一段改个时刻，
     * 把界面那天当成目标日会把别的日子的安排拽过来。
     */
    @Test
    fun a_move_only_changes_the_day_when_the_user_names_one() = runTest {
        val dayAfter = day.plusDays(2)
        val placement = TimelineEntry(
            id = "placement-mid", title = "期中复习", kind = TimelineKind.TASK, date = day,
            startMinute = 720, endMinute = 780, taskId = "task-mid", trackId = "track-mid",
        )
        val tb = toolbox(listOf(placement), otherDays = mapOf(dayAfter to emptyList()))
        val move = tb.specs().first { it.name == "move_placement" }
        val sameDay = JSONObject("""{"segmentId":"segment:placement-mid","startMinute":"14:00","endMinute":"15:00"}""")

        assertNull(move.validate!!.invoke(sameDay))
        val kept = tb.runWrite(AgentAction("m1", "move_placement", "移动时段", "挪一挪", sameDay.toString()))
        assertTrue(kept.summary, kept.ok)
        assertTrue(kept.summary, "已挪到 14:00-15:00" in kept.summary)
        assertEquals(840, movedPlacements.single().start)
        assertNull("没写 date 却换了天：${movedPlacements.single().date}", movedPlacements.single().date)

        val crossDay = JSONObject(sameDay.toString()).put("date", "后天")
        assertNull(move.validate!!.invoke(crossDay))
        val moved = tb.runWrite(AgentAction("m2", "move_placement", "移动时段", "挪到后天", crossDay.toString()))
        assertTrue(moved.summary, moved.ok)
        assertEquals(dayAfter, movedPlacements[1].date)
        assertTrue(moved.summary, "已挪到 ${taskDateLabel(dayAfter)} 14:00-15:00" in moved.summary)

        // 看不见后天，就别说挪得动。
        val blind = toolSpec("move_placement", occupied = listOf(placement))
        assertTrue("看不到" in blind.validate!!.invoke(crossDay).orEmpty())
    }

    /**
     * 助手能建、能排，却连「把实验报告改成 45 分钟」都做不到——这条补的就是那个洞：
     * 只动被点名的字段，其余原样保留，回执说清楚改了什么。
     */
    @Test
    fun an_edit_only_touches_the_fields_the_user_named() = runTest {
        val tb = toolbox(listOf(mathClass), tasks = listOf(lab))
        val edit = tb.specs().first { it.name == "update_task" }
        val args = JSONObject("""{"taskId":"实验报告","durationMinutes":45}""")
        assertNull(edit.validate!!.invoke(args))
        val card = edit.summarize!!.invoke(args)
        assertTrue(card, "时长 45 分钟" in card)
        assertTrue(card, "实验报告" in card)

        val result = tb.runWrite(AgentAction("u1", "update_task", "改任务本身", "改时长", args.toString()))
        assertTrue(result.summary, result.ok)
        val draft = updatedDrafts.single()
        assertEquals(45, draft.totalDurationMinutes)
        assertEquals(lab.displayName, draft.displayName)
        assertEquals(lab.description, draft.description)
        assertEquals(lab.category, draft.category)
        assertEquals(lab.scheduledForDate, draft.scheduledForDate)
        assertNull(draft.dueDate)

        // 仓储没保存就不许念成功。
        updateFailure = "任务信息没有保存，原内容仍保留。"
        val failed = tb.runWrite(
            AgentAction("u2", "update_task", "改任务本身", "改名", JSONObject("""{"taskId":"实验报告","name":"实验报告（重写）"}""").toString()),
        )
        assertFalse(failed.ok)
        assertTrue(failed.summary, "没有保存" in failed.summary)
    }

    /** 没说要改什么、日期写歪、时长离谱都要在卡之前挡回去：别让「改好了」变成一次空操作。 */
    @Test
    fun an_edit_that_names_nothing_is_refused_before_the_card() {
        val edit = toolSpec("update_task")
        fun refused(args: String) = edit.validate!!.invoke(JSONObject(args)).orEmpty()

        assertTrue("没说改什么" in refused("""{"taskId":"实验报告"}"""))
        assertTrue("时长需要 5–720" in refused("""{"taskId":"实验报告","durationMinutes":3}"""))
        assertTrue("时长要填分钟数" in refused("""{"taskId":"实验报告","durationMinutes":"一小时"}"""))
        assertTrue("不在当前清单里" in refused("""{"taskId":"没影的事","name":"改个名"}"""))
        assertTrue("具体未来日期" in refused("""{"taskId":"实验报告","dueDate":"前天"}"""))
        assertTrue("类别只认" in refused("""{"taskId":"实验报告","category":"运动"}"""))
    }

    /** 账本记的是建卡那一刻的时长：清单改了、账本不改，下一句「把剩下的排上」会排出错过的长度。 */
    @Test
    fun an_edited_length_is_what_the_next_auto_schedule_offers() = runTest {
        val tb = toolbox(listOf(mathClass))
        val created = JSONObject("""{"name":"复习高数","durationMinutes":120}""")
        assertTrue(tb.runWrite(AgentAction("u3", "create_task", "只建不排", "建", created.toString())).ok)
        val taskId = checkNotNull(savedDrafts.single().id)

        val edited = tb.runWrite(
            AgentAction("u4", "update_task", "改任务本身", "改短", JSONObject("""{"taskId":"$taskId","durationMinutes":45}""").toString()),
        )
        assertTrue(edited.summary, edited.ok)

        val result = tb.runWrite(AgentAction("u5", "schedule_draft", "自动排期", "排", "{}"))
        assertTrue(result.summary, result.ok)
        val followUp = JSONObject(result.followUpActions.single().argumentsJson)
        assertEquals("自动排期还在用改之前的长度：${followUp}", followUp.getInt("startMinute") + 45, followUp.getInt("endMinute"))
    }

    /**
     * 用户问「今天还排得下吗」要的是容量账，不是空档清单：课和任务各压掉多少分钟、
     * 登记的可用档还剩多少整段。挂进父项的下属占的是同一段时间，重复加会把一天算爆。
     */
    @Test
    fun the_budget_counts_overlapping_blocks_once_and_drops_fragments() = runTest {
        val children = listOf(
            TimelineEntry(id = "segment-a", title = "背单词", kind = TimelineKind.TASK, date = day,
                startMinute = 720, endMinute = 750, taskId = "t-a", trackId = "focus"),
            TimelineEntry(id = "segment-b", title = "听力", kind = TimelineKind.TASK, date = day,
                startMinute = 730, endMinute = 760, taskId = "t-b", trackId = "focus"),
        )
        val tb = toolbox(
            listOf(mathClass) + children,
            available = mapOf(day to listOf(600..779, 900..1_019, 1_050..1_069)),
        )

        val result = tb.runRead("query_time_budget", JSONObject())

        assertTrue(result.summary, result.ok)
        assertTrue(result.summary, "已排课或固定安排 60 分钟、任务 40 分钟" in result.summary)
        assertTrue(result.summary, "登记可用 320 分钟" in result.summary)
        // 10:00-13:00 里 12:00 起被课压住，只剩 10:00-12:00；15:00-17:00 整段空着；17:30 那 20 分钟不算整段。
        assertTrue(result.summary, "还能再排 240 分钟" in result.summary)
        assertTrue(result.summary, "10:00-12:00、15:00-17:00" in result.summary)
        assertFalse(result.summary, "17:30" in result.summary)
    }

    /** 没登记可用档的一天不许报成"全天有空"：只能说出已经排掉了多少。 */
    @Test
    fun an_unregistered_day_is_not_reported_as_all_day_free() = runTest {
        val result = toolbox(listOf(mathClass)).runRead("query_time_budget", JSONObject())

        assertTrue(result.summary, result.ok)
        assertTrue(result.summary, "没有登记可用档" in result.summary)
        assertTrue(result.summary, "别把没课当成有空" in result.summary)
    }

    /** 看不见那天排掉了多少，就连容量都不报：拿空实况算出来的"还剩"是假数字。 */
    @Test
    fun a_day_that_cannot_be_seen_has_no_budget_either() = runTest {
        val tomorrow = day.plusDays(1)
        val args = JSONObject("""{"date":"明天"}""")
        val blind = toolbox(listOf(mathClass), available = mapOf(day to listOf(600..779)))
            .runRead("query_time_budget", args)

        assertFalse(blind.summary, blind.ok)
        assertTrue(blind.summary, "看不到" in blind.summary)

        val seen = toolbox(
            listOf(mathClass),
            otherDays = mapOf(tomorrow to listOf(mathClass.copy(date = tomorrow))),
            available = mapOf(tomorrow to listOf(600..779)),
        ).runRead("query_time_budget", args)
        assertTrue(seen.summary, seen.ok)
        assertTrue(seen.summary, taskDateLabel(tomorrow) in seen.summary)
        assertTrue(seen.summary, "登记可用 180 分钟" in seen.summary)
    }

    /**
     * 注入提示词的只有最近几条习惯和最要紧几条记忆，用户问「你记得我什么」时得能翻到其余的；
     * 按关键词挑过之后必须说清这是子集，不然模型会把挑出来的三条当成全部。
     */
    @Test
    fun memory_read_reaches_beyond_the_briefing_and_labels_a_filtered_subset() = runTest {
        val tb = toolbox(
            listOf(mathClass),
            facts = listOf(
                "偏好·早上" to "习惯把难的事排在早上",
                "偏好·安静" to "自习喜欢去三楼",
                "复习" to "考前只看错题",
            ),
            episodes = listOf("2026-10-20 接受了草案：「复习高数」", "2026-10-21 决定周三晚上不打扰"),
            profile = MemoryReference(
                asOf = day,
                signals = listOf(
                    MemorySignal("task-lab", "实验报告要点", stabilityDays = 5.0, retrievability = 0.4, dueOn = day, pressure = 0.8, observations = 4),
                    MemorySignal("task-eng", "英语单词", stabilityDays = 20.0, retrievability = 0.9, dueOn = day.plusDays(9), pressure = 0.2, observations = 5),
                ),
            ),
        )

        val all = tb.runRead("query_memory", JSONObject())
        assertTrue(all.summary, all.ok)
        assertTrue(all.summary, "已有 3 条习惯、2 条小组结论" in all.summary)
        assertTrue(all.summary, "自习喜欢去三楼" in all.summary)
        assertTrue(all.summary, "实验报告要点（最近容易想不起）" in all.summary)

        val picked = tb.runRead("query_memory", JSONObject("""{"keyword":"早上"}"""))
        assertTrue(picked.summary, picked.ok)
        assertTrue(picked.summary, "从 3 条习惯、2 条结论里挑出 1 条习惯" in picked.summary)
        assertTrue(picked.summary, "习惯把难的事排在早上" in picked.summary)
        assertFalse(picked.summary, "自习喜欢去三楼" in picked.summary)

        val miss = tb.runRead("query_memory", JSONObject("""{"keyword":"游泳"}"""))
        assertTrue(miss.summary, miss.ok)
        assertTrue(miss.summary, "没找到跟「游泳」有关的记忆" in miss.summary)

        val none = toolbox(listOf(mathClass)).runRead("query_memory", JSONObject())
        assertTrue(none.summary, none.ok)
        assertTrue(none.summary, "还没有沉淀任何记忆" in none.summary)
    }

    /** 「以后周五晚上都有空」要落成每周可用时间，而且卡上写的那一段就是登记下去的那一段。 */
    @Test
    fun a_weekly_free_window_lands_as_the_same_slot_the_card_showed() = runTest {
        val args = JSONObject("""{"startMinute":"19:00","endMinute":"21:30","free":true,"repeatWeekly":true,"title":"晚自习"}""")
        val spec = toolSpec("set_available_time")
        assertNull(spec.validate!!.invoke(args))
        val card = spec.summarize!!.invoke(args)
        assertTrue(card, "周五 19:00-21:30" in card)
        assertTrue(card, "每周可用时间" in card)
        assertTrue(card, "晚自习" in card)

        val tb = toolbox(listOf(mathClass))
        val result = tb.runWrite(AgentAction("v1", "set_available_time", "登记可用时间", "周五晚上", args.toString()))

        assertTrue(result.summary, result.ok)
        assertEquals(1, registrations.size)
        assertTrue(registrations.single().repeatWeekly)
        assertTrue(registrations.single().free)
        assertEquals(1_140 to 1_290, registrations.single().startMinute to registrations.single().endMinute)
        assertEquals(day.dayOfWeek, registrations.single().date.dayOfWeek)
        // 回执只比卡上多一个「已」：两处各写一遍，卡上说的和真登记掉的迟早分家。
        assertEquals("已" + card, result.summary)
        // 24:00 只能当结束：说「到半夜」的用户不该被排到第二天去。
        assertNull(spec.validate!!.invoke(JSONObject("""{"startMinute":"21:00","endMinute":"24:00","free":true,"repeatWeekly":true}""")))
    }

    /** 锁死一段时间不会把已有安排挪走：卡上必须写明压到了谁，参数没给全的在卡之前就挡回去。 */
    @Test
    fun a_locked_window_shows_what_it_presses_and_a_half_baked_one_is_refused() = runTest {
        val args = JSONObject("""{"startMinute":"12:00","endMinute":"13:30","free":false,"title":"班会"}""")
        val spec = toolSpec("set_available_time")
        assertNull(spec.validate!!.invoke(args))
        val card = spec.summarize!!.invoke(args)
        assertTrue(card, "今天 12:00-13:30 记为临时占用（班会）" in card)
        assertTrue(card, "数学课" in card)
        assertTrue(card, "仍留在时间轴上" in card)

        val tb = toolbox(listOf(mathClass))
        val result = tb.runWrite(AgentAction("v2", "set_available_time", "登记可用时间", "锁死下午", args.toString()))
        assertTrue(result.summary, result.ok)
        assertEquals(listOf("availability"), writeOrder)
        assertFalse(registrations.single().free)
        assertFalse(registrations.single().repeatWeekly)
        assertTrue(result.summary, "已把今天 12:00-13:30 记为临时占用（班会）" in result.summary)

        fun refused(json: String) = spec.validate!!.invoke(JSONObject(json)).orEmpty()
        assertTrue("没填 free 也该挡回去", "free 要填" in refused("""{"startMinute":"12:00","endMinute":"13:00"}"""))
        assertTrue("15:00-15:10 也算看懂了？", "不到 15 分钟" in refused("""{"startMinute":"15:00","endMinute":"15:10","free":true}"""))
        assertTrue("结束时间必须晚于开始时间" in refused("""{"startMinute":"21:00","endMinute":"20:00","free":true}"""))
        assertTrue("起止时间没看懂" in refused("""{"startMinute":"晚上","endMinute":"21:00","free":true}"""))
        assertTrue("具体未来日期" in refused("""{"startMinute":"19:00","endMinute":"21:00","free":true,"date":"前天"}"""))

        // 仓储没答应就不许念成功，而且拒绝的理由要原样传到人面前。
        availabilityFailure = "时间设置正在读取，稍后再登记。"
        val failed = tb.runWrite(AgentAction("v3", "set_available_time", "登记可用时间", "再登记", args.toString()))
        assertFalse(failed.ok)
        assertTrue(failed.summary, "没登记上" in failed.summary)
        assertTrue(failed.summary, "时间设置正在读取" in failed.summary)
    }

    /** 登记完就得在容量账里看得到：助手给用户排事之前读的就是这份账。 */
    @Test
    fun a_registered_window_shows_up_in_the_capacity_the_next_read_reports() = runTest {
        val tb = toolbox(listOf(mathClass))
        val registered = tb.runWrite(
            AgentAction("v4", "set_available_time", "登记可用时间", "晚上有空",
                JSONObject("""{"startMinute":"19:00","endMinute":"21:30","free":true}""").toString()),
        )
        assertTrue(registered.summary, registered.ok)

        val budget = tb.runRead("query_time_budget", JSONObject())
        assertTrue(budget.summary, budget.ok)
        assertTrue(budget.summary, "登记可用 150 分钟" in budget.summary)
        assertTrue(budget.summary, "还能再排 150 分钟" in budget.summary)
        assertTrue(budget.summary, "19:00-21:30" in budget.summary)
    }

    private class ScriptedChat(private val turns: List<AgentTurn>) : AgentTurnModel {
        var index = 0
        override suspend fun complete(messages: List<AgentChatMessage>): AgentTurn =
            turns.getOrNull(index++) ?: AgentTurn.Reply(text = "（脚本结束）")
    }
}
