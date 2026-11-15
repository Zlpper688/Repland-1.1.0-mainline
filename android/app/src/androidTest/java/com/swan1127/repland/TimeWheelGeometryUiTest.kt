package com.swan1127.repland

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swan1127.repland.ui.components.TimeWheelField
import com.swan1127.repland.ui.components.rememberTimeWheelHold
import com.swan1127.repland.ui.theme.ReplandTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * 轮盘的「值」读的是滚动状态，肉眼认的是高亮带。两者必须落在同一格，
 * 否则用户对着色带挑时间，存进去的却是它上面那一档。
 */
@RunWith(AndroidJUnit4::class)
class TimeWheelGeometryUiTest {
    @get:Rule val rule = createComposeRule()

    private fun bandAndValue(tag: String, initialMinute: Int, allowEndOfDay: Boolean) {
        rule.setContent {
            ReplandTheme {
                val hold = rememberTimeWheelHold(initialMinute, allowEndOfDay = allowEndOfDay, key = tag)
                TimeWheelField(hold, tag = tag)
            }
        }
        rule.waitForIdle()
    }

    /** 中间那条色带盖住的档位，必须就是 contentDescription 报出去的值。 */
    @Test fun the_accent_band_covers_the_hour_that_is_actually_read() {
        bandAndValue("band", 11 * 60, allowEndOfDay = false)
        rule.onNodeWithTag("band", useUnmergedTree = true)
            .assertContentDescriptionEquals("轮盘时间 11:00")
        val column = rule.onNodeWithTag("time-wheel-band-hour", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val itemHeight = rule.onNodeWithTag("time-wheel-band-hour-item-11", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height
        val bandCenter = (column.top + column.bottom) / 2f
        val rowCenter = { index: Int ->
            val bounds = rule.onNodeWithTag("time-wheel-band-hour-item-$index", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            (bounds.top + bounds.bottom) / 2f
        }
        val read = rowCenter(11)
        val neighbour = rowCenter(12)
        assertTrue(
            "色带中心 $bandCenter 应盖住被读出的那格（11:00 中心 $read），但它离下一档 $neighbour 更近：差值 ${(bandCenter - read)} vs ${(bandCenter - neighbour)}，档位高 $itemHeight",
            abs(bandCenter - read) < abs(bandCenter - neighbour),
        )
    }

    /** 允许 24:00 的结束轮盘，最后一档必须真的选得到——存不进去的 24:00 等于没有当日结束。 */
    @Test fun the_last_hour_stop_is_reachable_by_tapping_it_on_an_end_of_day_wheel() {
        bandAndValue("edge", 23 * 60, allowEndOfDay = true)
        rule.onNodeWithTag("time-wheel-edge-hour-item-24", useUnmergedTree = true)
            .assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithTag("edge", useUnmergedTree = true).assertContentDescriptionEquals("轮盘时间 24:00")
    }

    /** 分轮盘的最后一档（55）同理：滚得到、点得中，55 分才不是画在屏幕外的假档位。 */
    @Test fun the_last_minute_stop_is_reachable_by_tapping_it() {
        bandAndValue("stop", 11 * 60, allowEndOfDay = false)
        // 轮盘一次只显示三档；55 分离 11:00 六档，先像用户一样滚过去再点。
        rule.onNodeWithTag("time-wheel-stop-minute", useUnmergedTree = true).performScrollToIndex(11)
        rule.waitForIdle()
        rule.onNodeWithTag("time-wheel-stop-minute-item-11", useUnmergedTree = true)
            .assertIsDisplayed().performClick()
        rule.onNodeWithTag("time-wheel-stop-minute-item-11", useUnmergedTree = true)
            .assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithTag("stop", useUnmergedTree = true).assertContentDescriptionEquals("轮盘时间 11:55")
    }
}
