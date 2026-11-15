package com.swan1127.repland.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.swan1127.repland.ui.theme.toward
import java.time.LocalTime
import kotlinx.coroutines.delay

enum class LandscapeStyle { Dawn, Sage, Dusk }

/** Which light the day is in right now; the theme only decides light/dark surfaces. */
enum class LandscapeLight { Dawn, Day, Dusk, Night }

fun landscapeLightAt(time: LocalTime): LandscapeLight = when (time.hour) {
    in 5..6 -> LandscapeLight.Dawn
    in 7..16 -> LandscapeLight.Day
    in 17..19 -> LandscapeLight.Dusk
    else -> LandscapeLight.Night
}

/** 显式指定的画风折算成白天里最像它的那个时辰，配色只有一套口径。 */
private fun LandscapeStyle.asLight(): LandscapeLight = when (this) {
    LandscapeStyle.Dawn -> LandscapeLight.Dawn
    LandscapeStyle.Sage -> LandscapeLight.Day
    LandscapeStyle.Dusk -> LandscapeLight.Dusk
}

private data class LandscapeColors(
    val sky: Color,
    val horizon: Color,
    val light: Color,
    val distant: Color,
    val middle: Color,
    val near: Color,
)

private fun landscapeColors(light: LandscapeLight, dark: Boolean): LandscapeColors = when {
    dark && light == LandscapeLight.Night -> LandscapeColors(
        Color(0xFF101C2E), Color(0xFF2C3F57), Color(0xFFE9E6D8),
        Color(0xFF3A4A64), Color(0xFF26344B), Color(0xFF16202F),
    )
    dark && light == LandscapeLight.Dusk -> LandscapeColors(
        Color(0xFF343B58), Color(0xFF6A6683), Color(0xFFE4DEEA),
        Color(0xFF6D6E93), Color(0xFF434F70), Color(0xFF253C4B),
    )
    dark && light == LandscapeLight.Dawn -> LandscapeColors(
        Color(0xFF304750), Color(0xFF82675D), Color(0xFFEBC49D),
        Color(0xFF796D6D), Color(0xFF4F6265), Color(0xFF263E45),
    )
    dark -> LandscapeColors(
        Color(0xFF2D484C), Color(0xFF627D76), Color(0xFFD9DFCB),
        Color(0xFF6A8882), Color(0xFF456660), Color(0xFF253F40),
    )
    light == LandscapeLight.Night -> LandscapeColors(
        Color(0xFFA8B4CB), Color(0xFFD8D3E0), Color(0xFFF5F2E6),
        Color(0xFF8F9CB6), Color(0xFF6E7C97), Color(0xFF4A5872),
    )
    light == LandscapeLight.Dusk -> LandscapeColors(
        Color(0xFFB7B4DF), Color(0xFFF0DCE1), Color(0xFFFFF8EF),
        Color(0xFFAFA4D8), Color(0xFF898BAE), Color(0xFF4D697D),
    )
    light == LandscapeLight.Dawn -> LandscapeColors(
        Color(0xFFDFE7DF), Color(0xFFF3D6AF), Color(0xFFFFF7DC),
        Color(0xFFD7AF93), Color(0xFFC77D61), Color(0xFF526C6D),
    )
    else -> LandscapeColors(
        Color(0xFFD8E8E5), Color(0xFFF4EDD9), Color(0xFFFFFCED),
        Color(0xFFA9BCB0), Color(0xFF7E9891), Color(0xFF3E6264),
    )
}

/**
 * 一天里的光，每 [LIGHT_POLL_MS] 问一次钟：停在页面上不动，天色也会跟着时辰走。
 * 冷启动算一次就冻住，是「都傍晚了背景还是正午」的直接原因。
 */
@Composable
fun rememberLandscapeLight(): LandscapeLight {
    var light by remember { mutableStateOf(landscapeLightAt(LocalTime.now())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(LIGHT_POLL_MS)
            landscapeLightAt(LocalTime.now()).let { if (it != light) light = it }
        }
    }
    return light
}

/**
 * 纸面跟着时辰走：白天几乎就是纸的本色，入夜往暖沙压。
 * 拉的是灯色不是天光——窗外是蓝的，手里这张纸是暖的，才是夜里那张真实书桌；
 * 睡前一小时再挨着蓝白光，压下去的是入睡本身。类别色仍由事项自己决定，这里只动底色。
 */
@Composable
fun rememberLandscapeSurface(base: Color): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < .2f
    val light = rememberLandscapeLight()
    return remember(base, dark, light) { base.toward(lampTint(light, dark), if (dark) .22f else .35f) }
}

private fun lampTint(light: LandscapeLight, dark: Boolean): Color = when {
    dark -> when (light) {
        LandscapeLight.Night -> Color(0xFF43382B)
        LandscapeLight.Dusk -> Color(0xFF3B3630)
        LandscapeLight.Dawn -> Color(0xFF3A3A34)
        LandscapeLight.Day -> Color(0xFF2F3B39)
    }
    light == LandscapeLight.Night -> Color(0xFFEBCFAC)
    light == LandscapeLight.Dusk -> Color(0xFFF2DCC4)
    light == LandscapeLight.Dawn -> Color(0xFFF6E3C6)
    else -> Color(0xFFF5EFDC)
}

private const val LIGHT_POLL_MS = 20_000L

@Composable
fun LandscapeArtwork(
    modifier: Modifier = Modifier,
    style: LandscapeStyle? = null,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .2f
    val light = if (style != null) style.asLight() else rememberLandscapeLight()
    val colors = landscapeColors(light, dark)
    Box(
        modifier = modifier
            .clearAndSetSemantics { }
            .drawWithCache {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val skyBrush = Brush.verticalGradient(listOf(colors.sky, colors.horizon))
                onDrawBehind {
                    // 只留天光与时辰锚点：层叠山脊收掉后，风景交给真实的远山素材（空态插画），底色回归安静。
                    drawRect(skyBrush)
                    drawTimeAnchor(light, colors)
                }
            },
    )
}

/** One time anchor per light: the disk the day hangs its schedule on. */
private fun DrawScope.drawTimeAnchor(light: LandscapeLight, colors: LandscapeColors) {
    val width = size.width
    val height = size.height
    val min = size.minDimension
    when (light) {
        LandscapeLight.Dawn -> {
            drawCircle(colors.light.copy(alpha = .18f), min * .20f, Offset(width * .30f, height * .52f))
            drawCircle(colors.light, min * .105f, Offset(width * .30f, height * .52f))
            drawOval(colors.light.copy(alpha = .55f), Offset(width * .08f, height * .25f), Size(width * .24f, height * .035f))
            drawOval(colors.light.copy(alpha = .4f), Offset(width * .17f, height * .29f), Size(width * .22f, height * .025f))
        }
        LandscapeLight.Day -> {
            drawCircle(colors.light.copy(alpha = .14f), min * .22f, Offset(width * .75f, height * .25f))
            drawCircle(colors.light, min * .115f, Offset(width * .75f, height * .25f))
            drawOval(colors.light.copy(alpha = .55f), Offset(width * .08f, height * .25f), Size(width * .24f, height * .035f))
            drawOval(colors.light.copy(alpha = .4f), Offset(width * .17f, height * .29f), Size(width * .22f, height * .025f))
        }
        LandscapeLight.Dusk -> {
            drawCircle(colors.light.copy(alpha = .20f), min * .22f, Offset(width * .70f, height * .40f))
            drawCircle(colors.light, min * .11f, Offset(width * .70f, height * .40f))
            drawOval(colors.light.copy(alpha = .35f), Offset(width * .08f, height * .25f), Size(width * .24f, height * .035f))
            drawOval(colors.light.copy(alpha = .22f), Offset(width * .17f, height * .29f), Size(width * .22f, height * .025f))
        }
        LandscapeLight.Night -> {
            drawCircle(colors.light.copy(alpha = .10f), min * .16f, Offset(width * .72f, height * .20f))
            drawCircle(colors.light.copy(alpha = .92f), min * .095f, Offset(width * .72f, height * .20f))
            drawOval(colors.light.copy(alpha = .14f), Offset(width * .08f, height * .25f), Size(width * .24f, height * .035f))
            drawOval(colors.light.copy(alpha = .09f), Offset(width * .17f, height * .29f), Size(width * .22f, height * .025f))
        }
    }
}
