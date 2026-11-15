package com.swan1127.repland

import com.swan1127.repland.domain.model.ArrangementAssistantInterpreter
import com.swan1127.repland.domain.model.ArrangementExistingTask
import com.swan1127.repland.domain.model.ArrangementExistingTaskLinker
import com.swan1127.repland.domain.model.TaskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrangementExistingTaskLinkerTest {

    private val meeting = ArrangementExistingTask(
        id = "task-meeting",
        title = "开会",
        category = TaskCategory.OFFICE,
        durationMinutes = 60,
    )

    @Test
    fun reschedule_sentence_links_the_scheduled_task_instead_of_creating_a_copy() {
        val utterance = "把开会改到下午3点"
        val linked = ArrangementExistingTaskLinker.link(
            ArrangementAssistantInterpreter.interpret(utterance).candidates,
            listOf(meeting),
            utterance,
        )

        val item = linked.single()
        assertEquals("task-meeting", item.existingTaskId)
        assertEquals("开会", item.title)
        assertEquals(15 * 60, item.timeHint.explicitStartMinute)
        assertEquals(60, item.durationMinutes)
    }

    @Test
    fun spoken_chinese_numeral_time_still_links_and_keeps_the_explicit_hour() {
        val utterance = "把开会挪到下午三点"
        val linked = ArrangementExistingTaskLinker.link(
            ArrangementAssistantInterpreter.interpret(utterance).candidates,
            listOf(meeting),
            utterance,
        )

        assertEquals("task-meeting", linked.single().existingTaskId)
        assertEquals(15 * 60, linked.single().timeHint.explicitStartMinute)
    }

    @Test
    fun a_new_work_session_is_not_treated_as_a_move() {
        val utterance = "再复习一次高数"
        val linked = ArrangementExistingTaskLinker.link(
            ArrangementAssistantInterpreter.interpret(utterance).candidates,
            listOf(ArrangementExistingTask("task-math", "复习高数", TaskCategory.COURSE, 60)),
            utterance,
        )

        assertNull(linked.single().existingTaskId)
    }

    @Test
    fun a_sentence_about_other_work_does_not_steal_the_only_existing_task() {
        val utterance = "把写报告改到晚上八点"
        val linked = ArrangementExistingTaskLinker.link(
            ArrangementAssistantInterpreter.interpret(utterance).candidates,
            listOf(meeting),
            utterance,
        )

        assertNull(linked.single().existingTaskId)
    }
}
