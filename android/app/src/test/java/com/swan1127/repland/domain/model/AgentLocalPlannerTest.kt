package com.swan1127.repland.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线兜底只该接住形状确定的那句话。这里两半都要验：接住的必须解析成正确的档，
 * 含糊的必须原样退回 null——兜底多猜一次，用户就多一张没打算确认的卡。
 */
class AgentLocalPlannerTest {
    /** 2026-10-27 是星期二。 */
    private val tuesday = LocalDate.of(2026, 10, 27)

    private fun args(utterance: String, today: LocalDate = tuesday): JSONObject? =
        AgentLocalPlanner.plan(utterance, today)?.arguments()

    @Test
    fun weeklyAvailabilityWithChineseNumerals() {
        val parsed = args("以后每周五晚上七点到九点半我都有空") ?: error("该接住这句话")
        assertEquals(AgentLocalPlanner.TOOL, AgentLocalPlanner.plan("以后每周五晚上七点到九点半我都有空", tuesday)?.name)
        assertEquals(19 * 60, parsed.getInt("startMinute"))
        assertEquals(21 * 60 + 30, parsed.getInt("endMinute"))
        assertTrue(parsed.getBoolean("free"))
        assertTrue(parsed.getBoolean("repeatWeekly"))
        val date = LocalDate.parse(parsed.getString("date"))
        assertEquals(DayOfWeek.FRIDAY, date.dayOfWeek)
        assertEquals(tuesday.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)), date)
    }

    @Test
    fun halfPastAndDigitsParseTheSameWay() {
        val chinese = args("每周三下午两点半到五点有空") ?: error("该接住这句话")
        val digits = args("每周三 14:30 到 17:00 有空") ?: error("该接住这句话")
        assertEquals(digits.getInt("startMinute"), chinese.getInt("startMinute"))
        assertEquals(digits.getInt("endMinute"), chinese.getInt("endMinute"))
        assertEquals(14 * 60 + 30, chinese.getInt("startMinute"))
        assertEquals(17 * 60, chinese.getInt("endMinute"))
    }

    /** 后半句常省略「晚上」；不跨到下午去的话算出结束早于开始，这一段永远登记不上。 */
    @Test
    fun endHourWrapsToAfternoonWhenOnlyTheStartSaidPM() {
        val parsed = args("今晚七点到九点有空") ?: error("该接住这句话")
        assertEquals(19 * 60, parsed.getInt("startMinute"))
        assertEquals(21 * 60, parsed.getInt("endMinute"))
        assertEquals("今晚", parsed.getString("date"))
        assertTrue(!parsed.has("repeatWeekly") || !parsed.getBoolean("repeatWeekly"))
    }

    /** 光一个「周六」说的是这周六；没有「每」就不许替用户登记往后每一周。 */
    @Test
    fun bareWeekdayIsOneOffAndTenOClockIsTen() {
        val parsed = args("周六早上十点到十一点半有空") ?: error("该接住这句话")
        assertEquals("周六", parsed.getString("date"))
        assertTrue(!parsed.has("repeatWeekly"))
        assertEquals(10 * 60, parsed.getInt("startMinute"))
        assertEquals(11 * 60 + 30, parsed.getInt("endMinute"))
    }

    @Test
    fun blockedWindowIsFreeFalse() {
        val parsed = args("明天19:00到21:00我没空") ?: error("该接住这句话")
        assertEquals(false, parsed.getBoolean("free"))
        assertEquals("明天", parsed.getString("date"))
        assertEquals(19 * 60, parsed.getInt("startMinute"))
    }

    @Test
    fun recurringWordBeatsAPreviousOccurrenceOfTheSameDay() {
        // 今天就是周五时，「每周五」登记的是往后每周的这一档，落点取本周这天。
        val friday = LocalDate.of(2026, 10, 30)
        val parsed = args("每周五晚上七点到九点半有空", friday) ?: error("该接住这句话")
        assertTrue(parsed.getBoolean("repeatWeekly"))
        assertEquals(friday, LocalDate.parse(parsed.getString("date")))
    }

    @Test
    fun vagueOrOpenEndedSentencesAreLeftToTheModel() {
        assertNull(AgentLocalPlanner.plan("我今天还排得下两小时吗", tuesday))
        assertNull(AgentLocalPlanner.plan("帮我多排点复习", tuesday))
        // 没有起止段：只有「晚上」也猜不出结束时间。
        assertNull(AgentLocalPlanner.plan("今晚有空", tuesday))
        // 没有哪一天：不替用户假定是今天。
        assertNull(AgentLocalPlanner.plan("晚上七点到九点半有空", tuesday))
        // 既没说有空也没说没空。
        assertNull(AgentLocalPlanner.plan("每周五晚上七点到九点半", tuesday))
    }

    @Test
    fun morningPrefixDoesNotShift() {
        val parsed = args("周六早上八点到九点半有时间") ?: error("该接住这句话")
        assertEquals(8 * 60, parsed.getInt("startMinute"))
        assertEquals(9 * 60 + 30, parsed.getInt("endMinute"))
    }

    @Test
    fun timeOnlySentenceIsRecognizedAndCarriesTheClock() {
        assertEquals(13 * 60, AgentLocalPlanner.timeOnlyClock("帮我排在今天中午一点"))
        assertEquals(13 * 60, AgentLocalPlanner.clockMinuteOf("好的，13:00 开始。要排哪个任务？"))
        assertEquals(13 * 60, AgentLocalPlanner.clockMinuteOf("好的，下午一点开始。要排哪个任务？"))
    }

    @Test
    fun sentenceWithATaskIsNotTimeOnly() {
        assertNull(AgentLocalPlanner.timeOnlyClock("吃饭，帮我排在今天中午一点"))
        assertNull(AgentLocalPlanner.timeOnlyClock("今天下午三点有空吗"))
        assertNull(AgentLocalPlanner.timeOnlyClock("每周五晚上七点到九点半我有空"))
        // 没有时刻的纯任务名也不该被反问时间。
        assertNull(AgentLocalPlanner.timeOnlyClock("吃饭"))
    }

    // ----------------------------------------------------------- engine wiring

    private class AvailabilityToolbox : AgentToolbox {
        val queued = mutableListOf<AgentAction>()
        var rejection: String? = null

        override fun specs(): List<AgentToolSpec> = listOf(
            AgentToolSpec(
                name = AgentLocalPlanner.TOOL, label = "登记可用时间", description = "登记一段空闲",
                kind = AgentToolKind.WRITE,
                validate = { rejection },
            ),
        )

        override suspend fun runRead(name: String, arguments: JSONObject) = AgentToolResult(false, "不该有读取")

        override suspend fun runWrite(action: AgentAction): AgentActionResult {
            queued += action
            return AgentActionResult(true, "已登记")
        }
    }

    private object DeadCloud : AgentTurnModel {
        override suspend fun complete(messages: List<AgentChatMessage>): AgentTurn =
            AgentTurn.Failed(AiAdvisorFailureReason.AUTHENTICATION_FAILURE)
    }

    private fun context() = AgentTurnContext(date = tuesday, occupiedEntries = emptyList())

    private suspend fun collect(
        engine: AgentConversationEngine,
        session: AgentSessionState,
        utterance: String,
    ): Pair<AgentSessionState, List<AgentConversationEvent>> {
        val events = mutableListOf<AgentConversationEvent>()
        val next = engine.runTurn(session, utterance, context()) { events += it }
        return next to events
    }

    /** 密钥被拒不等于这句话办不了：形状确定的登记要照样出确认卡，而不是回一张报错卡。 */
    @Test
    fun failedCloudTurnIsRescuedLocallyIntoAConfirmation() = runTest {
        val toolbox = AvailabilityToolbox()
        val (session, events) = collect(
            AgentConversationEngine(DeadCloud, toolbox),
            AgentSessionState(),
            "以后每周五晚上七点到九点半我都有空",
        )
        assertTrue(events.filterIsInstance<AgentConversationEvent.Failed>().isEmpty())
        assertEquals(1, session.pendingActions.size)
        val action = session.pendingActions.single()
        assertEquals(AgentLocalPlanner.TOOL, action.toolName)
        assertEquals(19 * 60, JSONObject(action.argumentsJson).getInt("startMinute"))
        assertTrue(action.summary.isNotBlank())
        // 下一轮模型恢复时得知道这一档是本地登记的，否则会重复登记。
        assertTrue(session.transcript.last().content.contains("本地规则已登记"))
    }

    @Test
    fun offlineTurnQueuesWithoutTouchingTheModel() = runTest {
        val toolbox = AvailabilityToolbox()
        val session = AgentConversationEngine(DeadCloud, toolbox)
            .runOfflineTurn(AgentSessionState(), "明天19:00到21:00我没空", context()) { }
            ?: error("该接住这句话")
        assertEquals(1, session.pendingActions.size)
        assertEquals(false, JSONObject(session.pendingActions.single().argumentsJson).getBoolean("free"))
    }

    /** 准入校验在本地路径上同样生效：被拒的档不能变成确认卡，也不能假装成功。 */
    @Test
    fun offlineTurnStillRunsAdmission() = runTest {
        val toolbox = AvailabilityToolbox().apply { rejection = "这一段不到 15 分钟，登记了也排不下事" }
        val events = mutableListOf<AgentConversationEvent>()
        val session = AgentConversationEngine(DeadCloud, toolbox)
            .runOfflineTurn(AgentSessionState(), "明天19:00到19:10有空", context()) { events += it }
        assertNull(session)
        val failure = events.filterIsInstance<AgentConversationEvent.ToolInvoked>().single()
        assertEquals(ToolOutcome.FAILED, failure.outcome)
        assertTrue(failure.resultSummary.contains("不到 15 分钟"))
    }

    @Test
    fun offlineTurnRefusesWhatItCannotParse() = runTest {
        val events = mutableListOf<AgentConversationEvent>()
        val session = AgentConversationEngine(DeadCloud, AvailabilityToolbox())
            .runOfflineTurn(AgentSessionState(), "我今天还排得下两小时吗", context()) { events += it }
        assertNull(session)
        assertTrue(events.isEmpty())
    }
}
