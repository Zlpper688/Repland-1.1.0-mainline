package com.swan1127.repland.domain.model

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊的确定性地板：模型不可用时，普通句子也要变成同一套工具调用，
 * 走同一道准入和确认卡。这里只验映射结果，不验写入。
 */
class AgentRulePlannerTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val active = LocalDate.of(2026, 11, 15)
    private val tasks = listOf(
        RuleTask("t1", "高数", 90, LocalDate.of(2026, 11, 15)),
        RuleTask("t2", "吃饭", 30, null),
    )

    private fun plan(utterance: String): AgentRuleOutcome? =
        AgentRulePlanner.plan(utterance, today, active, tasks)

    private fun queue(utterance: String): AgentRuleOutcome.Queue =
        plan(utterance) as? AgentRuleOutcome.Queue ?: error("「$utterance」该被规则层接住")

    @Test
    fun a_daily_routine_becomes_one_weekly_fixed_block() {
        val call = queue("每天下午四点玩王者荣耀两个小时").calls.single()
        assertEquals("save_routine", call.name)
        val args = call.arguments()
        assertEquals("玩王者荣耀", args.getString("name"))
        assertEquals("1,2,3,4,5,6,7", args.getString("weekdays"))
        assertEquals("16:00", args.getString("start"))
        assertEquals("18:00", args.getString("end"))
    }

    @Test
    fun a_busy_window_blocks_the_time_instead_of_creating_a_task() {
        val call = queue("明天下午三点到五点别排事").calls.single()
        assertEquals("set_available_time", call.name)
        val args = call.arguments()
        assertEquals("2026-11-16", args.getString("date"))
        assertEquals("15:00", args.getString("startMinute"))
        assertEquals("17:00", args.getString("endMinute"))
        assertEquals(false, args.getBoolean("free"))
        assertTrue(!args.has("repeatWeekly"))
    }

    @Test
    fun a_weekly_busy_window_repeats_on_every_named_day() {
        val calls = queue("每周二和周四下午三点到五点别排事").calls
        assertEquals(2, calls.size)
        val days = calls.map { JSONObject(it.argumentsJson).getString("date") }
        assertEquals(listOf("2026-11-17", "2026-11-19"), days)
        assertTrue(calls.all { JSONObject(it.argumentsJson).getBoolean("repeatWeekly") })
    }

    @Test
    fun an_existing_task_is_moved_not_duplicated() {
        val call = queue("高数推迟到周五").calls.single()
        assertEquals("plan_tasks", call.name)
        val args = call.arguments()
        assertEquals("2026-11-20", args.getString("date"))
        val item = args.getJSONArray("items").getJSONObject(0)
        assertEquals("t1", item.getString("taskId"))
        assertTrue(!item.has("name"))
    }

    @Test
    fun an_already_placed_task_is_moved_instead_of_duplicated() {
        val outcome = AgentRulePlanner.plan("高数推迟到周五", today, active, tasks) { id ->
            if (id == "t1") AgentRulePlacement("seg-1", today, 9 * 60, 10 * 60 + 30) else null
        } as AgentRuleOutcome.Queue
        val call = outcome.calls.single()
        assertEquals("move_placement", call.name)
        val args = call.arguments()
        assertEquals("seg-1", args.getString("segmentId"))
        assertEquals("2026-11-20", args.getString("date"))
        assertEquals("09:00", args.getString("startMinute"))
        assertEquals("10:30", args.getString("endMinute"))
    }

    @Test
    fun a_clock_only_move_keeps_the_day_in_view() {
        val call = queue("吃饭改到下午三点").calls.single()
        val args = call.arguments()
        assertEquals("plan_tasks", call.name)
        assertEquals("2026-11-15", args.getString("date"))
        val item = args.getJSONArray("items").getJSONObject(0)
        assertEquals("t2", item.getString("taskId"))
        assertEquals("15:00", item.getString("start"))
    }

    @Test
    fun a_placed_capture_carries_date_and_clock() {
        val call = queue("下周一上午十点组会").calls.single()
        assertEquals("plan_tasks", call.name)
        val args = call.arguments()
        assertEquals("2026-11-16", args.getString("date"))
        val item = args.getJSONArray("items").getJSONObject(0)
        assertEquals("组会", item.getString("name"))
        assertEquals("10:00", item.getString("start"))
    }

    @Test
    fun a_day_only_capture_keeps_the_planning_day_without_a_block() {
        val call = queue("明天写报告").calls.single()
        assertEquals("create_task", call.name)
        val args = call.arguments()
        assertEquals("写报告", args.getString("name"))
        assertEquals("2026-11-16", args.getString("date"))
        assertTrue(!args.has("start"))
    }

    @Test
    fun a_deadline_is_a_due_date_not_a_time_block() {
        val call = queue("周五前交作业").calls.single()
        assertEquals("create_task", call.name)
        val args = call.arguments()
        assertTrue(args.getString("name").contains("交作业"))
        assertEquals("2026-11-20", args.getString("dueDate"))
        assertTrue(!args.has("start"))
    }

    @Test
    fun progress_is_a_feedback_row_not_a_new_task() {
        val call = queue("高数完成三分之二了").calls.single()
        assertEquals("record_feedback", call.name)
        val args = call.arguments()
        assertEquals("t1", args.getString("taskId"))
        assertEquals(67, args.getInt("progressPercent"))
    }

    @Test
    fun a_missed_task_keeps_its_block_and_gets_extra_time() {
        val calls = queue("高数没完成，明天下午再给我两个小时").calls
        assertEquals(2, calls.size)
        val feedback = calls[0].arguments()
        assertEquals("record_feedback", calls[0].name)
        assertEquals("t1", feedback.getString("taskId"))
        val extra = calls[1].arguments()
        assertEquals("plan_tasks", calls[1].name)
        assertEquals("2026-11-16", extra.getString("date"))
        val item = extra.getJSONArray("items").getJSONObject(0)
        assertEquals("t1", item.getString("taskId"))
        assertEquals(120, item.getInt("durationMinutes"))
    }

    @Test
    fun two_actions_ask_instead_of_collapsing_into_one() {
        val outcome = plan("明天写报告，后天交作业")
        assertTrue(outcome is AgentRuleOutcome.Ask)
    }

    @Test
    fun an_unsupported_repeat_asks_for_real_days() {
        assertTrue(plan("隔天跑步半小时") is AgentRuleOutcome.Ask)
    }

    @Test
    fun a_query_and_english_are_left_to_the_model() {
        assertNull(plan("今天还有什么没做"))
        assertNull(plan("tomorrow 3pm meeting"))
    }

    // ------------------------------------------------------------ engine wiring

    private class RuleToolbox : AgentToolbox {
        val queued = mutableListOf<AgentAction>()
        var rejection: String? = null

        override fun specs(): List<AgentToolSpec> = listOf(
            writeSpec("plan_tasks"),
            writeSpec("create_task"),
            writeSpec("record_feedback"),
            writeSpec("save_routine"),
            writeSpec("set_available_time"),
        )

        private fun writeSpec(name: String) = AgentToolSpec(
            name = name, label = name, description = name,
            kind = AgentToolKind.WRITE,
            validate = { rejection },
        )

        override fun ruleTasks(): List<RuleTask> = listOf(RuleTask("t1", "高数", 90, null))

        override suspend fun runRead(name: String, arguments: JSONObject) = AgentToolResult(false, "不该有读取")

        override suspend fun runWrite(action: AgentAction): AgentActionResult {
            queued += action
            return AgentActionResult(true, "已登记")
        }
    }

    private fun context() = AgentTurnContext(date = LocalDate.of(2026, 11, 15), occupiedEntries = emptyList())

    @Test
    fun an_offline_placed_capture_queues_a_confirmation_card() = runTest {
        val toolbox = RuleToolbox()
        val events = mutableListOf<AgentConversationEvent>()
        val session = AgentConversationEngine(NoOpAgentTurnModel, toolbox)
            .runOfflineTurn(AgentSessionState(), "高数改到下午三点", context()) { events += it }
            ?: error("该被规则层接住")
        val action = session.pendingActions.single()
        assertEquals("plan_tasks", action.toolName)
        assertEquals("t1", JSONObject(action.argumentsJson).getJSONArray("items").getJSONObject(0).getString("taskId"))
        assertTrue(events.filterIsInstance<AgentConversationEvent.ActionQueued>().isNotEmpty())
    }

    @Test
    fun an_ambiguous_offline_sentence_asks_instead_of_failing() = runTest {
        val toolbox = RuleToolbox()
        val events = mutableListOf<AgentConversationEvent>()
        val session = AgentConversationEngine(NoOpAgentTurnModel, toolbox)
            .runOfflineTurn(AgentSessionState(), "明天写报告，后天交作业", context()) { events += it }
            ?: error("该由规则层反问")
        assertTrue(session.pendingActions.isEmpty())
        val said = events.filterIsInstance<AgentConversationEvent.TextOutput>().single().text
        assertTrue(said.contains("两件事"))
    }

    @Test
    fun a_rejected_rule_call_does_not_become_a_card() = runTest {
        val toolbox = RuleToolbox().apply { rejection = "这一项不在清单里" }
        val session = AgentConversationEngine(NoOpAgentTurnModel, toolbox)
            .runOfflineTurn(AgentSessionState(), "高数改到下午三点", context()) { }
        assertNull(session)
    }
}
