package com.swan1127.repland.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * The Compose side of [ToneLadder]: the only sanctioned way to paint a background in this app.
 *
 * Callers pass a [Shape] because the gradient has to be clipped to the corners the surface is drawn
 * with — a `background(brush)` without a shape squares off a rounded card — and the hairline edges
 * are drawn inside that same clip, so the lit top edge follows the corner instead of crossing it.
 *
 * The accent is deliberately not a parameter anyone can set freely. A stage pulls toward the live
 * `primaryContainer`, and both the pull and the ink floor are constants in [ToneLadder], so the three
 * [com.swan1127.repland.domain.model.ThemeAccent] families and both modes come out of one recipe and
 * no screen can quietly invent a look of its own.
 */

private val Edge = EdgeHairlineDp.dp
private val InkFloor = Color(InkFloorArgb)

/** True when the surrounding scheme is the night one. Same test [replandAccent] uses. */
val isReplandDark: Boolean
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background.luminance() < .2f

/** The ambient field, behind the Scaffold. Give the Scaffold a transparent container colour. */
@Composable
fun Modifier.replandPage(): Modifier {
    val ramp = toneFor(isReplandDark)
    return background(Brush.verticalGradient(listOf(Color(ramp.pageTop), Color(ramp.pageBottom))))
}

/**
 * A surface that leads a screen — the focal card, a summary, a first object. Tinted toward the accent
 * at the top, grounded in ink at the bottom, lit along the upper edge.
 *
 * [base] is the surface's own colour; the two stops are derived from it so that a paler or more
 * saturated starting point cannot invert the light. [wash] is for the rare surface that should lean
 * harder on the brand and is clamped to [StageAccentWash] either way.
 */
@Composable
fun Modifier.replandStage(
    shape: Shape = RoundedCornerShape(20.dp),
    base: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    wash: Float = StageAccentWash,
): Modifier = shaded(
    top = lerp(base, MaterialTheme.colorScheme.primaryContainer, wash.coerceIn(0f, StageAccentWash)),
    bottom = lerp(base, InkFloor, GroundInkFraction),
    shape = shape,
)

/** An ordinary card: the same light direction as a stage, with no accent pull. */
@Composable
fun Modifier.replandCard(
    shape: Shape = RoundedCornerShape(20.dp),
    base: Color = MaterialTheme.colorScheme.surfaceContainerLow,
): Modifier = shaded(lerp(base, InkFloor, GroundInkFraction * .4f), lerp(base, InkFloor, GroundInkFraction), shape)

/** A recessed surface — an input field, a chip tray, a shelf. Reads as cut into the page. */
@Composable
fun Modifier.replandWell(shape: Shape = RoundedCornerShape(14.dp)): Modifier {
    val floor = Color(toneFor(isReplandDark).well)
    // A recess is shaded at the top and lit at the bottom — the inverse of a raised surface.
    return shaded(floor, lerp(floor, InkFloor, GroundInkFraction), shape, light = Color(0x14000000))
}

/** The filled action. Both stops clear `onPrimary` at AA, so the gradient cannot break the label. */
@Composable
fun Modifier.replandAction(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    val ramp = toneFor(isReplandDark)
    // A saturated object needs a much quieter top edge than a paper card does; at 70% white the
    // hairline would read as a stroke drawn around the button.
    return shaded(Color(ramp.actionTop), Color(ramp.actionBottom), shape, light = Color(0x2EFFFFFF))
}

@Composable
private fun Modifier.shaded(top: Color, bottom: Color, shape: Shape, light: Color? = null): Modifier {
    val ramp = toneFor(isReplandDark)
    val edgeLight = light ?: Color(ramp.edgeLight)
    return clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .hairline(edgeLight, Color(ramp.edgeShade))
}

private fun Modifier.hairline(top: Color, bottom: Color): Modifier = drawWithContent {
    drawContent()
    val edge = Edge.toPx()
    drawRect(top, Offset.Zero, Size(size.width, edge))
    drawRect(bottom, Offset(0f, size.height - edge), Size(size.width, edge))
}
