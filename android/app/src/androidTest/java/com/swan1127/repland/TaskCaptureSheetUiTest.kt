package com.swan1127.repland

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.swan1127.repland.domain.model.*
import com.swan1127.repland.ui.components.TaskCaptureSheet
import com.swan1127.repland.ui.theme.ReplandTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TaskCaptureSheetUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun custom_duration_keeps_negative_decimal_and_overflow_raw_and_requires_explicit_correction() {
        var result: TaskDraft? = null
        lateinit var capture: MutableState<TaskCaptureDraft>
        rule.setContent { ReplandTheme {
            capture = remember { mutableStateOf(TaskCaptureDraft(text = "原始时长",
                isCustomDuration = true, customDurationText = "45")) }
            TaskCaptureSheet("", {}, { result = it }, draft = capture.value, onDraftChange = { capture.value = it })
        } }
        rule.onNodeWithTag("task-capture-duration-custom-input")
            .performScrollTo().assertIsDisplayed().performClick().assertIsFocused()
        listOf("-30", "3.5", "+30", "999999999999", "0", "1441").forEach { raw ->
            rule.onNodeWithTag("task-capture-duration-custom-input").performScrollTo().performTextReplacement(raw)
            rule.onNodeWithTag("task-capture-duration-custom-input").assertTextContains(raw)
            rule.onNodeWithTag("task-capture-save-inbox").assertIsNotEnabled().performClick()
            rule.runOnIdle { assertEquals(raw, capture.value.customDurationText); assertNull(result) }
        }
        rule.onNodeWithTag("task-capture-duration-custom-input").performTextReplacement("45")
        rule.onNodeWithTag("task-capture-duration-custom-input").assertTextContains("45")
        // The numeric IME is a separate native window on API 26. Finish editing
        // before tapping the footer, just as a user does when the keyboard covers it.
        if (NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")) {
            // With the IME active, native Back targets the keyboard window.
            NativeSheetTestInput.shell("input keyevent 4")
            rule.waitUntil(10_000) {
                !NativeSheetTestInput.shell("dumpsys input_method").contains("mWindowVisible=true")
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("task-capture-save-inbox").assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(45, result!!.totalDurationMinutes) }
    }
    @Test fun invalid_duration_keeps_selected_deadline_and_unknown_choice_fixes_it() {
        var result: TaskDraft? = null
        lateinit var capture: MutableState<TaskCaptureDraft>
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        rule.setContent { ReplandTheme {
            capture = remember { mutableStateOf(TaskCaptureDraft(text = "截止日期不丢失",
                isCustomDuration = true, customDurationText = "-30")) }
            TaskCaptureSheet("", {}, { result = it }, draft = capture.value, onDraftChange = { capture.value = it })
        } }
        rule.onNodeWithTag("task-capture-deadline").performScrollTo().performClick()
        rule.onNodeWithTag("task-capture-deadline-tomorrow").performClick()
        rule.runOnIdle {
            assertNull(result); assertEquals(tomorrow, capture.value.dueDate)
            assertNull(capture.value.scheduledForDate)
            assertEquals("-30", capture.value.customDurationText)
        }
        rule.onNodeWithTag("task-capture-save-inbox").assertIsNotEnabled()
        rule.onNodeWithTag("task-capture-duration").performScrollTo().performClick()
        rule.onNodeWithTag("task-capture-duration-unknown").performClick()
        rule.onNodeWithTag("task-capture-save-inbox").assertIsEnabled().performClick()
        assertEquals(tomorrow, result!!.dueDate)
        assertNull(result!!.totalDurationMinutes)
        assertNull(result!!.scheduledForDate)
    }

    @Test fun saving_disables_input_save_close_and_voice() {
        var saves = 0
        var closes = 0
        rule.setContent { ReplandTheme {
            TaskCaptureSheet("", { closes++ }, { saves++ }, onVoice = {},
                draft = TaskCaptureDraft(text = "已填写"), saving = true)
        } }
        listOf("task-capture-input", "task-capture-close", "task-capture-save-inbox",
            "voice-capture").forEach {
            rule.onNodeWithTag(it).assertIsNotEnabled()
        }
        assertEquals(0, saves); assertEquals(0, closes)
        rule.onNodeWithTag("task-capture-sheet").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag("task-capture-close").assertIsDisplayed()
        assertEquals(0, closes)
    }

    @Test fun save_failure_retains_custom_input_and_exposes_retry() {
        var result: TaskDraft? = null
        rule.setContent { ReplandTheme {
            TaskCaptureSheet("", {}, { result = it }, draft = TaskCaptureDraft(text = "复习",
                isCustomDuration = true, customDurationText = "45"),
                saveError = "保存未成功，请重试")
        } }
        rule.onNodeWithTag("task-capture-error").assertIsDisplayed()
        rule.onNodeWithTag("task-capture-duration-custom-input").assertTextContains("45")
        rule.onNodeWithTag("task-capture-save-inbox").assertIsEnabled().performClick()
        assertEquals(45, result!!.totalDurationMinutes)
    }

    @Test fun large_text_keeps_primary_operation_visible_and_options_scrollable() {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) { ReplandTheme(darkTheme = true) {
                TaskCaptureSheet("", {}, {}, draft = TaskCaptureDraft(text = "大字号输入"))
            } }
        }
        rule.onNodeWithTag("task-capture-save-inbox").assertIsDisplayed()
        rule.onNodeWithTag("task-capture-close").assertIsDisplayed()
        rule.onNodeWithTag("task-capture-duration").performScrollTo().assertIsDisplayed()
    }
    @Test fun selected_dates_keep_them_and_name_the_save_action_honestly() {
        val due = java.time.LocalDate.now().plusDays(7)
        var result: TaskDraft? = null
        rule.setContent { ReplandTheme {
            TaskCaptureSheet("", {}, { result = it }, draft = TaskCaptureDraft(text = "有截止日期", dueDate = due))
        } }
        rule.onNodeWithText("保存任务").assertIsDisplayed()
        rule.onNodeWithTag("task-capture-save-inbox").performClick()
        assertEquals(due, result!!.dueDate)
        assertNull(result!!.scheduledForDate)
    }
    @Test fun scheduled_today_names_the_save_action_save_to_today() {
        var result: TaskDraft? = null
        val today = java.time.LocalDate.now()
        rule.setContent { ReplandTheme {
            TaskCaptureSheet("", {}, { result = it }, draft = TaskCaptureDraft(text = "今天做", scheduledForDate = today))
        } }
        rule.onNodeWithText("保存到今天").assertIsDisplayed()
        rule.onNodeWithTag("task-capture-save-inbox").performClick()
        assertEquals(today, result!!.scheduledForDate)
    }
    @Test fun category_and_priority_dropdowns_feed_the_draft_with_user_input_source() {
        lateinit var capture: MutableState<TaskCaptureDraft>
        rule.setContent { ReplandTheme {
            capture = remember { mutableStateOf(TaskCaptureDraft(text = "分类优先级")) }
            TaskCaptureSheet("", {}, {}, draft = capture.value, onDraftChange = { capture.value = it })
        } }
        rule.onNodeWithTag("task-capture-category").performClick()
        rule.onNodeWithTag("task-capture-category-COURSE").performClick()
        rule.onNodeWithTag("task-capture-priority").performClick()
        rule.onNodeWithTag("task-capture-priority-HIGH").performClick()
        rule.runOnIdle {
            assertEquals(TaskCategory.COURSE, capture.value.category)
            assertEquals(TaskInputSource.USER_INPUT, capture.value.categorySource)
            assertEquals(TaskPriority.HIGH, capture.value.priority)
            assertEquals(TaskInputSource.USER_INPUT, capture.value.prioritySource)
        }
    }
    @Test fun clear_button_empties_text_and_clean_exit_skips_confirm() {
        var dismissals = 0
        var discards = 0
        rule.setContent { ReplandTheme {
            TaskCaptureSheet("", { dismissals++ }, {}, onDiscard = { discards++ })
        } }
        rule.onNodeWithTag("task-capture-input").performTextInput("写错了的内容")
        rule.onNodeWithTag("task-capture-clear").performClick()
        val editable = rule.onNodeWithTag("task-capture-input").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals("", editable)
        rule.onNodeWithTag("task-capture-close").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("task-capture-confirm-exit").assertCountEquals(0)
        assertEquals(1, dismissals)
        assertEquals(1, discards)
    }
    @Test fun exit_with_unsaved_content_asks_once_and_confirm_discards() {
        var dismissals = 0
        var discards = 0
        rule.setContent { ReplandTheme {
            TaskCaptureSheet("", { dismissals++ }, {}, onDiscard = { discards++ },
                draft = TaskCaptureDraft(text = "未保存的内容"))
        } }
        rule.onNodeWithTag("task-capture-close").performClick()
        rule.onNodeWithTag("task-capture-confirm-exit").assertIsDisplayed()
        rule.onNodeWithText("未保存的内容将消失。").assertIsDisplayed()
        assertEquals(0, dismissals); assertEquals(0, discards)
        rule.onNodeWithTag("task-capture-continue-edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("task-capture-input").assertIsDisplayed()
        assertEquals(0, dismissals)
        rule.onNodeWithTag("task-capture-close").performClick()
        rule.onNodeWithTag("task-capture-confirm-exit").performClick()
        rule.waitForIdle()
        assertEquals(1, dismissals)
        assertEquals(1, discards)
    }
    @Test fun load_failed_stays_editable_and_offers_retry() {
        var retries = 0
        lateinit var capture: MutableState<TaskCaptureDraft>
        rule.setContent { ReplandTheme {
            capture = remember { mutableStateOf(TaskCaptureDraft()) }
            TaskCaptureSheet("", {}, {}, draft = capture.value, onDraftChange = { capture.value = it },
                loadFailed = true, onRetryLoad = { retries++ })
        } }
        rule.onNodeWithTag("task-capture-load-error").assertIsDisplayed()
        rule.onNodeWithTag("task-capture-input").assertIsEnabled().performTextInput("读取失败也能输入")
        rule.runOnIdle { assertEquals("读取失败也能输入", capture.value.text) }
        rule.onNodeWithTag("task-capture-retry-load").performClick()
        assertEquals(1, retries)
    }
    @Test fun real_soft_keyboard_back_hides_ime_before_asking_to_exit() {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val previous = shell("settings get secure show_ime_with_hard_keyboard")
        var dismissals = 0
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            rule.setContent { ReplandTheme {
                TaskCaptureSheet("", { dismissals++ }, {}, draft = TaskCaptureDraft(text = "键盘返回测试"))
            } }
            NativeSheetTestInput.awaitFocusedDialog(rule, "新任务")
            rule.onNodeWithTag("task-capture-input").performClick().assertIsFocused()
            // mIsInputViewShown describes the service's input layout, not window visibility.
            rule.waitUntil(10_000) { shell("dumpsys input_method").contains("mWindowVisible=true") }
            NativeSheetTestInput.back(rule, "新任务")
            rule.waitUntil(10_000) { !shell("dumpsys input_method").contains("mWindowVisible=true") }
            rule.waitForIdle()
            rule.onNodeWithTag("task-capture-input").assertIsDisplayed()
            assertEquals(0, dismissals)
            // 第二次返回触发退出确认，而不是直接消失。
            NativeSheetTestInput.back(rule, "新任务")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("task-capture-confirm-exit").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, dismissals)
            rule.onNodeWithTag("task-capture-confirm-exit").performClick()
            rule.waitUntil(10_000) { dismissals == 1 }
        } finally {
            shell(if (previous == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $previous")
        }
    }
}
