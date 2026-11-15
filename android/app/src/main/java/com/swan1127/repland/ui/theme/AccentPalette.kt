package com.swan1127.repland.ui.theme

import com.swan1127.repland.domain.model.ThemeAccent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colour tokens a [ThemeAccent] is allowed to own, in one mode.
 *
 * These are stored as plain `0xAARRGGBB` `Long`s rather than as `androidx.compose.ui.graphics.Color`
 * so that this file — and the contrast arithmetic below — is answerable in a JVM unit test without
 * a Compose host. The one place that needs a real `Color` converts at the call site.
 *
 * Thirteen tokens, and the split between them is the point:
 *
 * - `primary*` is the *interactive* colour: buttons, selected rows, the accent underline. It is
 *   the only family the user reads as "this responds to me".
 * - `secondary*` is the quiet counterpart. It is deliberately close to the neutral ramp rather
 *   than a second saturated colour, so it can carry chrome (dividers, inactive chips) without
 *   competing with the brand.
 * - `tertiary*` is the *second emphasis level*: times, counts, streaks. It is a separate family
 *   from the brand so the eye has two places to land instead of one.
 *
 * `on*` tokens are not "the readable colour" in the abstract — each one is pinned to the surface
 * it sits on, and the invariant that they are readable is asserted by `AccentPaletteTest` rather
 * than eyeballed.
 */
data class ThemeRamp(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val inversePrimary: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
)

/** One accent's two modes. Both are authored, not derived: the dark focal colour has to be *luminous* (see below). */
data class ThemePalette(val light: ThemeRamp, val dark: ThemeRamp)

/**
 * The three ramps.
 *
 * [ThemeAccent.BAMBOO] is not a re-pick — it is byte-for-byte the palette the app shipped with, so
 * adding themes cannot silently restyle the default. The two new ones follow the same recipe:
 *
 * - The **light** brand is a deep, desaturated colour (it has to hold white text), its container is
 *   a pale wash of the same hue, and the `onContainer` is a dark shade of it.
 * - The **dark** brand is the *luminous* member of the family, not a darker one. This is the one
 *   non-obvious rule in the file: `#1E7A5A` on `#15130F` is two near-black colours, and the eye
 *   reads them as one flat field — which is exactly the "nothing leads the screen" failure the
 *   palette comments in `Color.kt` were written about. So the dark focal colour is always a light,
 *   higher-chroma version of the light one.
 * - The **second emphasis** colour in dark mode is likewise a lighter version of its light
 *   counterpart, for the same reason.
 */
fun paletteFor(accent: ThemeAccent): ThemePalette = when (accent) {
    ThemeAccent.BAMBOO -> ThemePalette(
        light = ThemeRamp(
            primary = 0xFF1E7A5A,
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFD2F0E0,
            onPrimaryContainer = 0xFF0B4531,
            inversePrimary = 0xFF34A176,
            secondary = 0xFF5C6B5F,
            onSecondary = 0xFFFFFFFF,
            secondaryContainer = 0xFFE5EBE2,
            onSecondaryContainer = 0xFF27352B,
            tertiary = 0xFFB4712C,
            onTertiary = 0xFFFFFFFF,
            tertiaryContainer = 0xFFFBE8D0,
            onTertiaryContainer = 0xFF6A3D12,
        ),
        dark = ThemeRamp(
            primary = 0xFF7FD9B4,
            onPrimary = 0xFF00331F,
            primaryContainer = 0xFF1F7355,
            onPrimaryContainer = 0xFFD6F8E7,
            inversePrimary = 0xFF1E7A5A,
            secondary = 0xFFBFC9BB,
            onSecondary = 0xFF223026,
            secondaryContainer = 0xFF2C3A30,
            onSecondaryContainer = 0xFFDCE7DC,
            tertiary = 0xFFE7B279,
            onTertiary = 0xFF3F2609,
            tertiaryContainer = 0xFF4A3218,
            onTertiaryContainer = 0xFFF8DEBB,
        ),
    )

    ThemeAccent.INDIGO -> ThemePalette(
        light = ThemeRamp(
            primary = 0xFF2E4A8C,
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFDCE3F7,
            onPrimaryContainer = 0xFF16244F,
            inversePrimary = 0xFF5573C8,
            secondary = 0xFF5B6478,
            onSecondary = 0xFFFFFFFF,
            secondaryContainer = 0xFFE3E6EE,
            onSecondaryContainer = 0xFF272C38,
            tertiary = 0xFFB4712C,
            onTertiary = 0xFFFFFFFF,
            tertiaryContainer = 0xFFFBE8D0,
            onTertiaryContainer = 0xFF6A3D12,
        ),
        dark = ThemeRamp(
            primary = 0xFFAFC2F5,
            onPrimary = 0xFF0E1A40,
            primaryContainer = 0xFF34497F,
            onPrimaryContainer = 0xFFDCE3F7,
            inversePrimary = 0xFF2E4A8C,
            secondary = 0xFFBCC3D2,
            onSecondary = 0xFF232936,
            secondaryContainer = 0xFF333A47,
            onSecondaryContainer = 0xFFDDE1EA,
            tertiary = 0xFFE7B279,
            onTertiary = 0xFF3F2609,
            tertiaryContainer = 0xFF4A3218,
            onTertiaryContainer = 0xFFF8DEBB,
        ),
    )

    ThemeAccent.PLUM -> ThemePalette(
        light = ThemeRamp(
            primary = 0xFF8C2F52,
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFF8DCE6,
            onPrimaryContainer = 0xFF54132C,
            inversePrimary = 0xFFC25E82,
            secondary = 0xFF6B5A62,
            onSecondary = 0xFFFFFFFF,
            secondaryContainer = 0xFFEDE2E6,
            onSecondaryContainer = 0xFF32272C,
            tertiary = 0xFF1E7A6E,
            onTertiary = 0xFFFFFFFF,
            tertiaryContainer = 0xFFD2F0EA,
            onTertiaryContainer = 0xFF0B4038,
        ),
        dark = ThemeRamp(
            primary = 0xFFF3B4CA,
            onPrimary = 0xFF4A0E24,
            primaryContainer = 0xFF7A2A48,
            onPrimaryContainer = 0xFFF8DCE6,
            inversePrimary = 0xFF8C2F52,
            secondary = 0xFFD2C2C8,
            onSecondary = 0xFF362A30,
            secondaryContainer = 0xFF45383E,
            onSecondaryContainer = 0xFFE9DEE2,
            tertiary = 0xFF7FD9C9,
            onTertiary = 0xFF00382F,
            tertiaryContainer = 0xFF1E6B60,
            onTertiaryContainer = 0xFFD2F0EA,
        ),
    )
}

// ---------------------------------------------------------------- contrast arithmetic

/**
 * WCAG 2.1 relative luminance of an `0xAARRGGBB` colour, in `0.0..1.0`.
 *
 * The sRGB channels are linearised before they are weighted. That step is the whole reason this
 * function exists rather than a simple brightness average: the sRGB transfer function is strongly
 * non-linear, so an average of the *encoded* channels would call `#808080` "half as bright" as
 * white when it is in fact about a fifth as bright. Any threshold derived from an un-linearised
 * average would be wrong in the direction that matters most — near black.
 *
 * Alpha is ignored on purpose. Every token here is fully opaque; a translucent token would have to
 * be composited against the surface it sits on first, and silently treating alpha as opaque is the
 * quieter failure compared to pretending to composite.
 */
fun relativeLuminance(argb: Long): Double {
    fun linear(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    val red = linear(channelOf(argb, 16))
    val green = linear(channelOf(argb, 8))
    val blue = linear(channelOf(argb, 0))
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

/**
 * WCAG 2.1 contrast ratio between two opaque colours, in `1.0..21.0`.
 *
 * Order-independent, because "contrast between A and B" has no direction — which is also why the
 * lighter of the two is always the numerator, per the specification.
 */
fun contrastRatio(first: Long, second: Long): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

/** One 8-bit sRGB channel as `0.0..1.0`; [shift] is 16 for red, 8 for green, 0 for blue. */
private fun channelOf(argb: Long, shift: Int): Double =
    ((argb shr shift) and 0xFFL).toDouble() / 255.0
