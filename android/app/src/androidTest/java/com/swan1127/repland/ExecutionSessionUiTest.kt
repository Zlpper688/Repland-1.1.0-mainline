package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swan1127.repland.domain.model.ExecutionSession
import com.swan1127.repland.ui.schedule.ExecutionSessionDialog
import com.swan1127.repland.ui.theme.ReplandTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutionSessionUiTest {
    @get:Rule val rule = createComposeRule()
    private val session = ExecutionSession("s", "t", "复习高数", "p", "seg", 1500, System.currentTimeMillis(), runningSinceEpochMillis = null)
    private val running = session.copy(runningSinceEpochMillis = System.currentTimeMillis() - 10_000)

    @Test fun paused_session_can_resume_and_return_without_finishing() {
        var resumed = false; var closed = false
        rule.setContent { ReplandTheme { ExecutionSessionDialog(session, false, null, { closed = true }, {}, {}, { resumed = true }) } }
        rule.onNodeWithTag("execution-pause-resume").performClick()
        assertTrue(resumed)
        rule.onNodeWithTag("execution-return").performClick()
        assertTrue(closed)
    }
    @Test fun paused_session_shows_paused_caption_and_full_countdown() {
        rule.setContent { ReplandTheme { ExecutionSessionDialog(session, false, null, {}, {}, {}, {}) } }
        rule.onNodeWithText("复习高数 · 已暂停").assertExists()
        rule.onNodeWithText("25:00").assertExists()
    }
    @Test fun tapping_scene_pauses_running_round() {
        var paused = false; var resumed = false
        // 计时中的轮盘每秒重算一次剩余时间：自动推进时钟时 Compose 永远不空闲，Espresso 会等到超时。
        // 关掉自动推进，让这一轮停在一帧上，断言的文本才不会在手里变。
        rule.mainClock.autoAdvance = false
        rule.setContent { ReplandTheme { ExecutionSessionDialog(running, false, null, {}, {}, { paused = true }, { resumed = true }) } }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("轻触楼房暂停 · 返回后计时不停").assertExists()
        rule.onNodeWithTag("execution-pause-resume").performClick()
        rule.mainClock.advanceTimeBy(100)
        assertTrue(paused); assertFalse(resumed)
    }
    @Test fun busy_disables_controls_and_saved_errors_are_visible() {
        rule.setContent { ReplandTheme { ExecutionSessionDialog(session, true, "本轮未保存，请重试", {}, {}, {}, {}) } }
        rule.onNodeWithTag("execution-pause-resume").assertIsNotEnabled()
        rule.onNodeWithTag("execution-return").assertIsNotEnabled()
        rule.onNodeWithTag("execution-error").assertExists()
    }
    @Test fun dark_mode_with_large_text_keeps_scene_and_exit_reachable() {
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                ReplandTheme(darkTheme = true) { ExecutionSessionDialog(session, false, null, {}, {}, {}, {}) }
            }
        }
        rule.onNodeWithTag("execution-pause-resume").assertIsDisplayed()
        rule.onNodeWithTag("execution-return").assertIsDisplayed()
    }
}
