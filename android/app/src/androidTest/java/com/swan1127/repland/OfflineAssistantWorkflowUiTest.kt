package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.swan1127.repland.domain.model.DateOverrideType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * 没有密钥的机器上，用户授权后本地兜底仍然把一句话变成真实写入——
 * 但确认卡、准入校验、批量撤销一道都不能少。
 */
class OfflineAssistantWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = (rule.activity.application as ReplandApplication).appContainer
    private val tomorrow get() = LocalDate.now().plusDays(1)
    private var originalAiEnabled = false

    @Before fun arm_offline_writes() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        resetAssistantSessionForTest()
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
        originalAiEnabled = runBlocking { app.aiSettingsRepository.observe().first().isEnabled }
        // 授权 + 打开，但没有密钥：云端永远不可用，能接住那句话的只剩本地兜底。
        runBlocking { app.aiSettingsRepository.grantConsentAndEnable() }
    }

    @After fun restore_ai_settings() {
        runBlocking { app.aiSettingsRepository.setEnabled(originalAiEnabled) }
        resetAssistantSessionForTest()
    }

    private fun waitTag(tag: String) =
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun local_fallback_registers_availability_only_after_confirmation_and_undo_takes_it_back() {
        val before = runBlocking { app.timeRepository.observeDateOverrides().first() }
        rule.openScheduleAssistant()
        rule.onNodeWithTag("group-input").performTextReplacement("明天下午两点到四点有空")
        rule.onNodeWithTag("group-send").performClick()
        waitTag("agent-confirm-actions")
        // 还没点确认：这句话只排队，不落库。
        assertEquals(before, runBlocking { app.timeRepository.observeDateOverrides().first() })
        rule.onNodeWithTag("agent-confirm-actions").performClick()
        rule.waitUntil(10_000) {
            runBlocking { app.timeRepository.observeDateOverrides().first() }.size == before.size + 1
        }
        val registered = runBlocking { app.timeRepository.observeDateOverrides().first() }
            .single { it.id !in before.map { old -> old.id } }
        assertEquals(tomorrow, registered.date)
        assertEquals(DateOverrideType.AVAILABLE, registered.type)
        assertEquals(14 * 60, registered.startMinute)
        assertEquals(16 * 60, registered.endMinute)
        waitTag("group-batch-undo")
        rule.onNodeWithTag("group-batch-undo").performClick()
        rule.waitUntil(10_000) {
            runBlocking { app.timeRepository.observeDateOverrides().first() } == before
        }
    }
}
