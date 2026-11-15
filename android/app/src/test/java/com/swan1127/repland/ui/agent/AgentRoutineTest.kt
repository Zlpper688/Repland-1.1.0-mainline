package com.swan1127.repland.ui.agent

import com.swan1127.repland.domain.model.AgentAction
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.domain.model.WeeklyTimeBlockDraft
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 日常安排走 save_routine/remove_routine：一次登记按星期展开，查重和替换都在写入前算清。 */
class AgentRoutineTest {
    private val day = LocalDate.of(2026, 10, 23)
    private val stored = mutableListOf<WeeklyTimeBlock>()
    private var saveFailure: String? = null
    private var idSeed = 0

    @Before
    fun reset() {
        stored.clear()
        saveFailure = null
        idSeed = 0
    }

    private fun toolbox() = AgentAppToolbox(
        activeDate = day,
        tasksProvider = { emptyList() },
        occupiedProvider = { emptyList() },
        onSaveTasks = { false },
        onStartTask = {},
        onCompleteTask = { _, _, _, _ -> },
        onCancelTask = {},
        onPostponeTask = { _, _ -> },
        onRecordFeedback = { _, _ -> },
        onPlaceTask = { _, _, _, _, _, _, _ -> "测试里不排任务" },
        onMovePlacement = { _, _, _, _, _ -> "测试里不挪任务" },
        onRemovePlacement = {},
        onSetSegmentReminder = { _, _, _ -> },
        onStartFocus = {},
        weeklyBlocksProvider = { stored.toList() },
        onSaveRoutine = { removeIds, drafts ->
            saveFailure ?: run {
                stored.removeAll { it.id in removeIds }
                drafts.forEach { draft ->
                    stored += WeeklyTimeBlock(
                        id = "blk-${idSeed++}",
                        title = draft.title,
                        kind = draft.kind,
                        dayOfWeek = draft.dayOfWeek,
                        startMinute = draft.startMinute,
                        endMinute = draft.endMinute,
                        weekPattern = draft.weekPattern,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        trackId = draft.trackId,
                    )
                }
                null
            }
        },
    )

    private fun action(name: String, args: JSONObject) =
        AgentAction(id = "action-1", toolName = name, label = "", summary = "", argumentsJson = args.toString())

    private fun spec(toolbox: AgentAppToolbox, name: String) = toolbox.specs().first { it.name == name }

    private fun args(name: String, start: String, end: String, weekdays: String, replace: Boolean? = null) =
        JSONObject().put("name", name).put("start", start).put("end", end).put("weekdays", weekdays).also {
            if (replace != null) it.put("replace", replace)
        }

    @Test
    fun dailyRoutineExpandsToSevenDaysAndIsNotATask() = runTest {
        val toolbox = toolbox()
        val result = toolbox.runWrite(action("save_routine", args("玩王者荣耀", "18:00", "22:00", "每天")))
        assertTrue(result.summary, result.ok)
        assertEquals(7, stored.size)
        assertEquals((1..7).map(DayOfWeek::of), stored.map { it.dayOfWeek }.sortedBy { it.value })
        assertTrue(stored.all { it.kind == TimeBlockKind.OTHER })
        assertTrue(stored.all { it.startMinute == 18 * 60 && it.endMinute == 22 * 60 })
        assertEquals("把「玩王者荣耀」设为每天 18:00-22:00 的固定安排", spec(toolbox, "save_routine").summarize?.invoke(
            args("玩王者荣耀", "18:00", "22:00", "每天"),
        ))
    }

    @Test
    fun weekdaysAndWorkdaysAreParsed() = runTest {
        val toolbox = toolbox()
        toolbox.runWrite(action("save_routine", args("晨跑", "07:00", "07:30", "工作日")))
        assertEquals(5, stored.size)
        assertTrue(stored.all { it.dayOfWeek.value <= 5 })
        assertEquals(
            "把「晚自习」设为周末 19:00-21:00 的固定安排",
            spec(toolbox, "save_routine").summarize?.invoke(args("晚自习", "19:00", "21:00", "周末")),
        )
    }

    @Test
    fun missingWeekdaysIsRejectedBeforeQueueing() {
        val toolbox = toolbox()
        val rejection = spec(toolbox, "save_routine").validate?.invoke(
            JSONObject().put("name", "玩王者荣耀").put("start", "18:00").put("end", "22:00"),
        )
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("每天"))
    }

    @Test
    fun repeatingTheSameRoutineIsRejectedInsteadOfDuplicated() = runTest {
        val toolbox = toolbox()
        toolbox.runWrite(action("save_routine", args("玩王者荣耀", "18:00", "22:00", "每天")))
        val rejection = spec(toolbox, "save_routine").validate?.invoke(args("玩王者荣耀", "18:00", "22:00", "每天"))
        assertEquals("「玩王者荣耀」这些天已经是固定安排了", rejection)
    }

    @Test
    fun removeOnlyTheNamedWeekdays() = runTest {
        val toolbox = toolbox()
        toolbox.runWrite(action("save_routine", args("玩王者荣耀", "18:00", "22:00", "每天")))
        val result = toolbox.runWrite(
            action("remove_routine", JSONObject().put("name", "玩王者荣耀").put("weekdays", "周末")),
        )
        assertTrue(result.summary, result.ok)
        assertEquals(5, stored.size)
        assertTrue(stored.none { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun replaceSwapsOldTimeInOneWrite() = runTest {
        val toolbox = toolbox()
        toolbox.runWrite(action("save_routine", args("玩王者荣耀", "18:00", "22:00", "每天")))
        val result = toolbox.runWrite(
            action("save_routine", args("玩王者荣耀", "19:00", "21:00", "每天", replace = true)),
        )
        assertTrue(result.summary, result.ok)
        assertTrue(result.summary.contains("替换掉 7 段"))
        assertEquals(7, stored.size)
        assertTrue(stored.all { it.startMinute == 19 * 60 && it.endMinute == 21 * 60 })
    }

    @Test
    fun saveFailureIsReportedHonestly() = runTest {
        val toolbox = toolbox()
        saveFailure = "数据库写入失败"
        val result = toolbox.runWrite(action("save_routine", args("玩王者荣耀", "18:00", "22:00", "每天")))
        assertFalse(result.ok)
        assertTrue(result.summary.contains("数据库写入失败"))
        assertTrue(stored.isEmpty())
    }

    @Test
    fun readListsOnlyFixedCommitments() = runTest {
        stored += WeeklyTimeBlock(
            id = "free-1", title = "每周可用时间", kind = TimeBlockKind.AVAILABLE, dayOfWeek = DayOfWeek.MONDAY,
            startMinute = 600, endMinute = 720, weekPattern = null, createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L,
        )
        stored += WeeklyTimeBlock(
            id = "r-1", title = "玩王者荣耀", kind = TimeBlockKind.OTHER, dayOfWeek = DayOfWeek.MONDAY,
            startMinute = 1080, endMinute = 1320, weekPattern = null, createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L,
        )
        val read = toolbox().runRead("query_routines", JSONObject())
        assertTrue(read.ok)
        assertTrue(read.summary.contains("玩王者荣耀"))
        assertFalse(read.summary.contains("每周可用时间"))
    }

    @Test
    fun removeRoutineNeedsAnExistingName() {
        val toolbox = toolbox()
        val rejection = spec(toolbox, "remove_routine").validate?.invoke(JSONObject().put("name", "不存在"))
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("没找到"))
    }

    private fun block(title: String, day: DayOfWeek, startMinute: Int, endMinute: Int) {
        stored += WeeklyTimeBlock(
            id = "seed-${idSeed++}", title = title, kind = TimeBlockKind.OTHER, dayOfWeek = day,
            startMinute = startMinute, endMinute = endMinute, weekPattern = null,
            createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L,
        )
    }

    /** 原句：只给了时长和「每天」，结束时间晚于开始时间这句错话不该再出现。 */
    @Test
    fun durationOnlyDailyRoutinePicksTheEveningGap() = runTest {
        val toolbox = toolbox()
        toolbox.noteUtterance("给我把玩王者荣耀这件事排成每天四个小时看着空挡和合适时间放")
        val args = JSONObject().put("name", "玩王者荣耀").put("durationMinutes", 240).put("weekdays", "每天")
        val result = toolbox.runWrite(action("save_routine", args))
        assertTrue(result.summary, result.ok)
        assertEquals(7, stored.size)
        assertTrue(stored.all { it.startMinute == 18 * 60 && it.endMinute == 22 * 60 })
        assertTrue(result.summary.contains("按空档挑的"))
    }

    /** 「八点二十分」的分钟是钟点，不能又被读成 20 分钟的时长。 */
    @Test
    fun clockMinutesAreNotMistakenForADuration() = runTest {
        val toolbox = toolbox()
        toolbox.noteUtterance("每天八点二十分读英语两小时")
        val result = toolbox.runWrite(action("save_routine", JSONObject().put("name", "读英语")))
        assertTrue(result.summary, result.ok)
        assertEquals(7, stored.size)
        assertTrue(stored.all { it.startMinute == 8 * 60 + 20 && it.endMinute == 10 * 60 + 20 })
    }

    /** 确认那一下的原话只剩「确认」，方案必须还是卡上承诺的那一个。 */
    @Test
    fun confirmReusesThePlanResolvedFromTheOriginalSentence() = runTest {
        val toolbox = toolbox()
        val args = JSONObject().put("name", "读英语")
        toolbox.noteUtterance("每天八点二十分读英语两小时")
        assertNull(spec(toolbox, "save_routine").validate?.invoke(args))
        toolbox.noteUtterance("确认")
        val result = toolbox.runWrite(action("save_routine", args))
        assertTrue(result.summary, result.ok)
        assertEquals(7, stored.size)
        assertTrue(stored.all { it.startMinute == 8 * 60 + 20 && it.endMinute == 10 * 60 + 20 })
    }

    /** 模型把起止写反了：按用户说的时长去空档里挑，而不是把反的时段原样弹回去。 */
    @Test
    fun reversedModelTimesFallBackToAFreeSlot() = runTest {
        val toolbox = toolbox()
        toolbox.noteUtterance("周末打篮球两小时，看着空档")
        val args = JSONObject().put("name", "打篮球").put("weekdays", "周末")
            .put("durationMinutes", 120).put("start", "22:00").put("end", "20:00")
        val result = toolbox.runWrite(action("save_routine", args))
        assertTrue(result.summary, result.ok)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.endMinute - it.startMinute == 120 })
        assertTrue(result.summary.contains("按空档挑的"))
    }

    /** 模型没给星期几时，从「每天」回读，不能塌成一天。 */
    @Test
    fun missingWeekdaysAreRecoveredFromTheSentence() = runTest {
        val toolbox = toolbox()
        toolbox.noteUtterance("每天背单词一小时，看着空档放")
        val result = toolbox.runWrite(action("save_routine", JSONObject().put("name", "背单词")))
        assertTrue(result.summary, result.ok)
        assertEquals(7, stored.size)
        assertTrue(stored.all { it.endMinute - it.startMinute == 60 })
    }

    /** 用户没点名钟点时，模型给的时段撞车也只是建议，要真的去空档里挑。 */
    @Test
    fun collidingModelSlotIsRepickedFromFreeGaps() = runTest {
        DayOfWeek.values().forEach { block("玩王者荣耀", it, 18 * 60, 22 * 60) }
        val toolbox = toolbox()
        toolbox.noteUtterance("每天跑步一小时，挑个合适的时间")
        val args = JSONObject().put("name", "跑步").put("weekdays", "每天")
            .put("durationMinutes", 60).put("start", "19:00").put("end", "20:00")
        val result = toolbox.runWrite(action("save_routine", args))
        assertTrue(result.summary, result.ok)
        val runs = stored.filter { it.title == "跑步" }
        assertEquals(7, runs.size)
        assertTrue(runs.all { it.startMinute == 17 * 60 && it.endMinute == 18 * 60 })
        assertTrue(result.summary.contains("按空档挑的"))
    }

    /** 真的排不下时，报最长的一段共同空档，让用户有东西可回答。 */
    @Test
    fun noCommonWindowAnswersWithTheLongestGap() {
        DayOfWeek.values().forEach {
            block("玩王者荣耀", it, 18 * 60, 22 * 60)
            block("冥想", it, 8 * 60, 12 * 60)
        }
        val toolbox = toolbox()
        toolbox.noteUtterance("每天拉伸十二个小时，看着空档")
        val rejection = spec(toolbox, "save_routine").validate?.invoke(
            JSONObject().put("name", "拉伸").put("weekdays", "每天").put("durationMinutes", 720),
        )
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("没有共同的长空档"))
        assertTrue(rejection.contains("最长的一段是 12:00-18:00（360 分钟）"))
    }

    /** 反着的两个钟点是问句，不是内部校验行。 */
    @Test
    fun reversedSpokenClocksAskOneClearQuestion() {
        val toolbox = toolbox()
        toolbox.noteUtterance("把打篮球改成周六22点到18点")
        val rejection = spec(toolbox, "save_routine").validate?.invoke(
            JSONObject().put("name", "打篮球").put("weekdays", "6").put("durationMinutes", 120),
        )
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("倒着的"))
        assertTrue(rejection.contains("18:00-22:00"))
    }

    /** 23:00 到 01:00 是跨夜意图，不能回一句「要按 01:00-23:00 设吗」。 */
    @Test
    fun crossMidnightPairAsksHowToSplit() {
        val toolbox = toolbox()
        toolbox.noteUtterance("每天晚上11点到凌晨1点打游戏")
        val rejection = spec(toolbox, "save_routine").validate?.invoke(
            JSONObject().put("name", "打游戏").put("weekdays", "每天"),
        )
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("跨过午夜"))
        assertTrue(rejection.contains("23:00-24:00"))
        assertTrue(rejection.contains("00:00-01:00"))
        assertFalse(rejection.contains("01:00-23:00"))
    }

    /** 点名了开始时间又要跨天：给出「缩到几点」这一个可选答案。 */
    @Test
    fun overflowingSpokenStartAsksInsteadOfFailing() {
        val toolbox = toolbox()
        toolbox.noteUtterance("每天拉伸晚上10点开始，四小时")
        val rejection = spec(toolbox, "save_routine").validate?.invoke(
            JSONObject().put("name", "拉伸").put("durationMinutes", 240),
        )
        assertNotNull(rejection)
        assertTrue(rejection!!.contains("次日 02:00"))
        assertTrue(rejection.contains("120 分钟"))
    }
}
