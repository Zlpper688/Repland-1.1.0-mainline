package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「关于我」自述是用户资产：能写、能改、能清空，落盘持久；
 * 但它不是排期输入——写自述绝不改动任务库或当前计划（手动=对话等价之外的第三条边界）。
 */
@RunWith(AndroidJUnit4::class)
class PersonaDocUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun pause_automatic_maintenance() {
        check(rule.activity.packageName == "com.swan1127.repland.qa")
        pauseAutomaticPlanningForLegacyWorkflow(rule.activity)
    }

    private fun waitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitGone(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }

    private fun openProfile() {
        waitTag("navigation-mine")
        rule.onNodeWithTag("navigation-mine").performClick()
        waitTag("mine-profile-page")
    }

    private fun store() = (rule.activity.application as ReplandApplication).appContainer.personaDocStore

    @Test fun edit_save_persists_and_shows_on_profile_without_touching_plan() {
        val app = (rule.activity.application as ReplandApplication).appContainer
        val tasksBefore = runBlocking { app.taskRepository.observeTasks().first() }
        val planBefore = runBlocking { app.planRepository.observeCurrentPlan().first() }

        openProfile()
        rule.onNodeWithTag("persona-doc-card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("persona-doc-card").performClick()
        waitTag("persona-doc-input")

        val doc = "我早上状态最好，想把高数放在上午。"
        rule.onNodeWithTag("persona-doc-input").performScrollTo().performTextReplacement(doc)
        rule.onNodeWithTag("persona-doc-save").performClick()
        waitGone("persona-doc-input")

        rule.onNodeWithText("早上状态最好", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(doc, store().load())

        // 自述只进提示词，不是排期输入：任务与计划原样不动。
        assertEquals(tasksBefore, runBlocking { app.taskRepository.observeTasks().first() })
        assertEquals(planBefore, runBlocking { app.planRepository.observeCurrentPlan().first() })
    }

    @Test fun clear_removes_the_doc_from_store_and_profile() {
        openProfile()
        rule.onNodeWithTag("persona-doc-card").performScrollTo().performClick()
        waitTag("persona-doc-input")
        // 先写一段，确保「清空」按钮出现；清空后保存即从提示词移除。
        rule.onNodeWithTag("persona-doc-input").performScrollTo().performTextReplacement("我在准备蓝桥杯，周末能多投入一点。")
        rule.onNodeWithTag("persona-doc-clear").performClick()
        rule.onNodeWithTag("persona-doc-save").performClick()
        waitGone("persona-doc-input")

        assertEquals("", store().load())
        rule.onNodeWithText("用你自己的话写下", substring = true).performScrollTo().assertIsDisplayed()
    }
}