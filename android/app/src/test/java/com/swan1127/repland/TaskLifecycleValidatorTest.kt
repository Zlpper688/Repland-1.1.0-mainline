package com.swan1127.repland

import com.swan1127.repland.domain.model.TaskFeedback
import com.swan1127.repland.domain.model.TaskLifecycleValidator
import com.swan1127.repland.domain.model.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLifecycleValidatorTest {
    @Test
    fun `only user lifecycle transitions preserve all supported task states`() {
        assertTrue(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.NOT_STARTED,
            TaskStatus.IN_PROGRESS,
        ))
        assertTrue(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.IN_PROGRESS,
            TaskStatus.POSTPONED,
        ))
        assertTrue(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.POSTPONED,
            TaskStatus.POSTPONED,
        ))
        assertTrue(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.NOT_STARTED,
            TaskStatus.COMPLETED,
        ))
        assertTrue(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.IN_PROGRESS,
            TaskStatus.CANCELLED,
        ))
        assertTrue(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.CANCELLED,
            TaskStatus.NOT_STARTED,
        ))
        assertFalse(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.REPLACED,
            TaskStatus.NOT_STARTED,
        ))
        assertFalse(TaskLifecycleValidator.isValidStatusConfirmation(
            TaskStatus.COMPLETED,
            TaskStatus.POSTPONED,
        ))
    }

    /** 撤销按钮的武装判据就是这条逆向迁移；写死成恒 true 会留下点了没反应、还会撤回别件事的按钮。 */
    @Test
    fun `the undo button is armed exactly where the reverse transition is legal`() {
        fun reversible(from: TaskStatus, to: TaskStatus) =
            TaskLifecycleValidator.isValidStatusConfirmation(from, to) &&
                TaskLifecycleValidator.isValidStatusConfirmation(to, from)
        // 一键点错了也一键撤回：开始、顺延、恢复。
        assertTrue(reversible(TaskStatus.NOT_STARTED, TaskStatus.IN_PROGRESS))
        assertTrue(reversible(TaskStatus.NOT_STARTED, TaskStatus.POSTPONED))
        assertTrue(reversible(TaskStatus.IN_PROGRESS, TaskStatus.POSTPONED))
        assertTrue(reversible(TaskStatus.COMPLETED, TaskStatus.NOT_STARTED))
        // 完成/取消之后只能恢复成「未开始」，回不到「进行中」：
        // 这两条没有原样回路，界面必须收掉撤销按钮，而不是给一个会把上一件无关改动当成这次撤回的空按钮。
        assertFalse(reversible(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED))
        assertFalse(reversible(TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED))
        assertFalse(reversible(TaskStatus.REPLACED, TaskStatus.NOT_STARTED))
    }

    @Test
    fun `completion and partial feedback enforce their respective confirmations`() {
        assertTrue(TaskLifecycleValidator.isValidCompletion(
            TaskFeedback(completedContent = "完成题目", progressPercent = 100),
        ))
        assertFalse(TaskLifecycleValidator.isValidCompletion(
            TaskFeedback(completedContent = "完成题目", progressPercent = 80),
        ))
        assertTrue(TaskLifecycleValidator.isValidCompletion(TaskFeedback(progressPercent = 100)))
        assertTrue(TaskLifecycleValidator.isValidCompletion(TaskFeedback()))

        assertTrue(TaskLifecycleValidator.isValidPartialCompletion(
            TaskFeedback(completedContent = "完成第一章", progressPercent = 40, actualDurationMinutes = 30),
        ))
        // 部分完成允许进度未知、内容留空：胶囊路径"未完成"一键落账。
        assertTrue(TaskLifecycleValidator.isValidPartialCompletion(TaskFeedback(progressPercent = null)))
        assertTrue(TaskLifecycleValidator.isValidPartialCompletion(
            TaskFeedback(completedContent = "做了一点", progressPercent = null),
        ))
        assertFalse(TaskLifecycleValidator.isValidPartialCompletion(
            TaskFeedback(completedContent = "完成第一章", progressPercent = 100),
        ))
        assertFalse(TaskLifecycleValidator.isValidPartialCompletion(
            TaskFeedback(completedContent = "完成第一章", progressPercent = 20, actualDurationMinutes = 0),
        ))
    }
}
