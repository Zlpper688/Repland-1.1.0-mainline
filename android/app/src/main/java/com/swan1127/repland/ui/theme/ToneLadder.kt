package com.swan1127.repland.ui.theme

/**
 * The tone ladder — the one place that says how light falls across this app.
 *
 * Everything here is a plain `0xAARRGGBB` `Long`, for the same reason as [ThemeRamp]: these rules
 * are answerable in a JVM unit test without a Compose host. `Depth.kt` is the only consumer that
 * turns them into `Color`s.
 *
 * **The design rule, in one sentence:** depth comes from tint and a light direction, never from drop
 * shadows. Every surface is one of four things — the page, a stage, a well, or an action — and each
 * is a short vertical gradient: brighter and faintly accented where the light lands at the top,
 * grounded by a whisper of ink at the bottom. A gradient strong enough to be *noticed as a gradient*
 * reads as decoration and flattens the hierarchy, so the step between the two stops of any one
 * surface is held inside a band that `DepthToneTest` enforces numerically.
 *
 * **Why grounding is relative, not an absolute hex.** A stage's lower stop is its own base colour
 * pulled a few percent toward [InkFloorArgb], not a fixed "floor" colour. With a fixed floor, a
 * surface that starts out paler than the floor — the focal card, in any accent — would gradient
 * *upward* and the light would come from below.
 *
 * **Why only the page and the action are authored hexes.** The page is the ambient field, so it is
 * allowed its own hue; the filled action is the one saturated object in the app and both of its stops
 * are pinned against `onPrimary` by test. Cards and wells start from the live Material scheme, which
 * is what makes all three [com.swan1127.repland.domain.model.ThemeAccent] families and both modes
 * follow one recipe.
 */
data class ToneRamp(
    /** The ambient field behind everything. Two stops; the bottom is cooler and deeper. */
    val pageTop: Long,
    val pageBottom: Long,
    /** An inset surface — a field, a chip tray, a shelf. Always below the page. */
    val well: Long,
    /** The 1px edge that makes a surface read as an object: lit above, shaded below. */
    val edgeLight: Long,
    val edgeShade: Long,
    /** The filled action colour, so a button can carry the same light direction as a surface. */
    val actionTop: Long,
    val actionBottom: Long,
)

val LightTone = ToneRamp(
    pageTop = 0xFFFDFDFE,
    pageBottom = 0xFFEDF1F7,
    well = 0xFFE5EAF2,
    edgeLight = 0xB3FFFFFF,
    edgeShade = 0x140B1B22,
    actionTop = 0xFF3E7C88,
    actionBottom = 0xFF2A5A64,
)

val DarkTone = ToneRamp(
    pageTop = 0xFF1D2029,
    pageBottom = 0xFF11131A,
    well = 0xFF12151C,
    edgeLight = 0x1AFFFFFF,
    edgeShade = 0x59000000,
    actionTop = 0xFF93D3D8,
    actionBottom = 0xFF6FB8BF,
)

/** A cool near-black. Every surface's lower stop is this, at [GroundInkFraction]. */
const val InkFloorArgb: Long = 0xFF0A0F14

/**
 * How much ink a surface's lower stop carries. Deliberately small: this is what stops a card from
 * looking like a flat sticker, and at twice this it starts looking like a shadow instead of a body.
 */
const val GroundInkFraction: Float = .035f

/**
 * How far a stage moves toward the live accent container at its top, before it stops being a surface
 * and starts being a coloured panel. Capped, and asserted as capped, because this is the only part
 * of the ladder that follows the accent — too much of it and the brand dominates the screen instead
 * of leading within it.
 */
const val StageAccentWash: Float = .45f

/** The thinnest edge Compose can draw on a 1x screen; the ladder would be invisible for less. */
const val EdgeHairlineDp: Float = 1f

fun toneFor(dark: Boolean): ToneRamp = if (dark) DarkTone else LightTone
