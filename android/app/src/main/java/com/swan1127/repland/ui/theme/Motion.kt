package com.swan1127.repland.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

object ReplandMotion {
    const val Press = 90
    const val Release = 140
    /** Pages swap as a cross-fade: the new one fades in over the old one leaving. */
    const val Page = 320
    const val PageClose = 180
    const val Detail = 340
    const val Panel = 280
    const val PanelClose = 200
    const val Expand = 260
    const val Collapse = 180
    const val Feedback = 200
    /** Per-item cascade delay; first appearance only, at most six items. */
    const val Stagger = 30
    /** A hand-drawn stroke finishing its line. */
    const val Ink = 480
    /** An estimate becoming a fact: the dashed resolves into solid. */
    const val Settle = 520
    /** The only loop in the app: the focused card's breath. Everything else stays still. */
    const val Breath = 4000
}

/** Page-scale motion: accelerates hard, lands soft. */
val ReplandEmphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Exits leave faster than entrances arrive. */
val ReplandExit = CubicBezierEasing(0.3f, 0f, 1f, 1f)

/** Confirmations land without overshoot. */
fun <T> snapSpec(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy)

/** Panels arrive with one soft landing, no second bounce. */
fun <T> settleSpec(): SpringSpec<T> = spring(dampingRatio = 0.85f)

val LocalReducedMotion = staticCompositionLocalOf { false }

/** One observer for the themed tree; changes take effect without restarting the app. */
@Composable
fun ReplandMotionProvider(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    fun reduced() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduce by remember(resolver) { mutableStateOf(reduced()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = reduced() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduce = reduced()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalReducedMotion provides reduce, content = content)
}

/**
 * Both pages are composed during the swap, so leaving never reads as a cut.
 * Frames update graphics layers only; the pages themselves keep their own state.
 */
@Composable
fun MotionPage(
    targetKey: Any,
    modifier: Modifier = Modifier,
    lateral: Boolean = false,
    order: Int = 0,
    durationMillis: Int = ReplandMotion.Page,
    content: @Composable (key: Any) -> Unit,
) {
    val reduce = LocalReducedMotion.current
    if (reduce) {
        // 省掉的是动画，不是布局：调用方把 weight/verticalScroll 挂在这个 modifier 上，
        // 直出内容会让弹窗中间那段既拿不到剩余高度、也没有可滚祖先，内容一长就无处可去。
        Box(modifier = modifier, contentAlignment = Alignment.TopCenter) { content(targetKey) }
        return
    }
    var previousOrder by remember { mutableIntStateOf(order) }
    var direction by remember { mutableFloatStateOf(1f) }
    val drift = with(LocalDensity.current) { (if (lateral) 26.dp else 14.dp).toPx() }
    LaunchedEffect(targetKey) {
        direction = if (order < previousOrder) -1f else 1f
        previousOrder = order
    }
    AnimatedContent(
        targetState = targetKey,
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
        label = "page transition",
        transitionSpec = {
            val enter = fadeIn(
                tween(durationMillis, delayMillis = durationMillis / 5, easing = ReplandEmphasized),
            ) + slideInVertically(tween(durationMillis, easing = ReplandEmphasized)) { h ->
                if (lateral) 0 else (h * 0.012f).toInt()
            } + slideInHorizontally(tween(durationMillis, easing = ReplandEmphasized)) { w ->
                if (lateral) (w * 0.03f * direction).toInt() else 0
            }
            val exit = fadeOut(tween(ReplandMotion.PageClose, easing = ReplandExit))
            enter togetherWith exit
        },
    ) { pageKey ->
        content(pageKey)
    }
}

@Composable
fun MotionDisclosure(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val reduce = LocalReducedMotion.current
    if (reduce) {
        if (visible) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(tween(ReplandMotion.Expand, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(ReplandMotion.Release)),
            exit = shrinkVertically(tween(ReplandMotion.Collapse, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(ReplandMotion.Press)),
        ) { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

/** Visual compression does not change the 48dp layout or the native ripple/semantics. */
@Composable
fun pressMotion(source: MutableInteractionSource, enabled: Boolean, pressedScale: Float = .98f): Modifier {
    val reduce = LocalReducedMotion.current
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed && enabled && !reduce) pressedScale else 1f,
        animationSpec = tween(
            if (reduce) 0 else if (pressed) ReplandMotion.Press else ReplandMotion.Release,
            easing = FastOutSlowInEasing,
        ),
        label = "press feedback",
    )
    return Modifier.graphicsLayer {
        scaleX = if (reduce) 1f else scale.value
        scaleY = if (reduce) 1f else scale.value
    }
}
