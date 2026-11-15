package com.swan1127.repland.ui.schedule

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.swan1127.repland.R
import com.swan1127.repland.domain.model.ExecutionSession
import com.swan1127.repland.ui.components.LandscapeLight
import com.swan1127.repland.ui.components.rememberLandscapeLight
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
@Composable
fun ExecutionSessionDialog(
    session: ExecutionSession,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onFinish: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    canOperate: Boolean = true,
    readNotice: (@Composable () -> Unit)? = null,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.id, session.runningSinceEpochMillis) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val elapsedSeconds = session.elapsedMillis(now) / 1_000
    val remaining = (session.targetSeconds - elapsedSeconds).coerceAtLeast(0)
    // 封顶 = 楼盖满了：不再有「暂停」可言，只等收工。
    val toppedOut = session.targetSeconds > 0 && remaining == 0L
    val toggle: () -> Unit = { if (session.isPaused) onResume() else onPause() }
    val status = when {
        session.isPaused -> "已暂停"
        toppedOut -> "已封顶"
        else -> "专注中"
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        // Compose 1.7's non-default measurement uses screen dimensions even
        // when the native dialog is inset. Honor native constraints instead.
        usePlatformDefaultWidth = true,
        decorFitsSystemWindows = false,
    )) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            // Expand the native window, then let the platform pass its real
            // measure specs to Compose. Never substitute screen dimensions.
            val match = WindowManager.LayoutParams.MATCH_PARENT
            if (window.attributes.width != match || window.attributes.height != match) window.setLayout(match, match)
        }
        Surface(Modifier.fillMaxSize().testTag("execution-session"), color = MaterialTheme.colorScheme.surface) {
            // 入夜后这块字压在暗天上，还写深墨就是看不清。夜里改成把天空再压暗一层、字提亮：
            // 不拿一张白布盖在夜空上——那正是睡前一小时该避开的白光。
            val night = rememberLandscapeLight() == LandscapeLight.Night
            val scheme = MaterialTheme.colorScheme
            val headerVeilTop = if (night) scheme.onSurface.copy(alpha = .62f) else scheme.surface.copy(alpha = .92f)
            val headerInk = if (night) scheme.surface else scheme.onSurface
            val headerMutedInk = if (night) scheme.surface.copy(alpha = .8f) else scheme.onSurfaceVariant
            val headerAccentInk = if (night) scheme.surface.copy(alpha = .92f) else scheme.primary
            Box(Modifier.fillMaxSize()) {
                // 整幅画面就是背景：楼从地面长出来，不再套一圈白边相框。
                BuildingScene(
                    seed = session.id.hashCode(),
                    completedFloors = (elapsedSeconds / 300).toInt(),
                    floorProgress = (elapsedSeconds % 300) / 300f,
                    plannedFloors = ceil(session.targetSeconds / 300.0).toInt().coerceAtLeast(1),
                    paused = session.isPaused,
                    enabled = !busy && canOperate && !toppedOut,
                    onToggle = toggle,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(headerVeilTop, Color.Transparent),
                            ),
                        )
                        .safeDrawingPadding()
                        .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 4.sp),
                        color = headerAccentInk,
                    )
                    Text(
                        when {
                            session.isPaused -> "${session.taskTitle} · 已暂停"
                            toppedOut -> "${session.taskTitle} · 已封顶"
                            else -> session.taskTitle
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif,
                            letterSpacing = 1.sp,
                        ),
                        color = headerInk,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        String.format("%02d:%02d", remaining / 60, remaining % 60),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontFeatureSettings = "tnum",
                        ),
                        color = headerInk,
                    )
                    Text(
                        when {
                            session.isPaused -> "轻触楼房继续 · 返回后计时不停"
                            toppedOut -> "这栋楼已经盖满 · 点完成收下这一轮"
                            else -> "轻触楼房暂停 · 返回后计时不停"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = headerMutedInk,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                            ),
                        )
                        .safeDrawingPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    error?.let {
                        Text(
                            it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("execution-error"),
                        )
                    }
                    readNotice?.invoke()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("execution-return"),
                        ) { Text("返回") }
                        Button(
                            onClick = onFinish,
                            enabled = !busy && canOperate,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("execution-finish"),
                        ) { Text(if (toppedOut) "完成 · 收工" else "完成") }
                    }
                }
            }
        }
    }
}

/** 每专注 5 分钟起一层：已投入的分钟数由楼房本身表达，界面只有楼、时间和退出。 */
@Composable
private fun BuildingScene(
    seed: Int,
    completedFloors: Int,
    floorProgress: Float,
    plannedFloors: Int,
    paused: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 昼夜只问钟，不问深浅色主题：开着浅色模式熬夜，楼不该还亮着正午那盏灯。
    // 和首页背景共用同一个时辰，一小时的专注跨过入夜那一刻，场景会当场暗下来。
    val night = rememberLandscapeLight() == LandscapeLight.Night
    val sprites = rememberTowerSprites()
    // 楼高按计划封顶：超时继续跑不该把楼压成一根细杆，多出的层记在「我的」总成就里。
    val renderedFloors = min(completedFloors, plannedFloors.coerceAtLeast(1))
    val floorImages = remember(seed, renderedFloors, sprites) {
        List(renderedFloors) { sprites.floors[floorVariant(seed, it)] }
    }
    val building = completedFloors < plannedFloors

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 比例按「计划封顶时的高度」一次定死：楼往上长，画框不动，不会越盖越小。
    val targetScale = towerScale(
        width = canvasSize.width.toFloat(),
        height = canvasSize.height.toFloat(),
        plannedFloors = plannedFloors.coerceAtLeast(1),
    )
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "tower-scale",
    )

    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(seed, paused) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val t = withFrameNanos { it }
            clock += (t - last) / 1_000_000_000f
            last = t
        }
    }

    // 新楼层落成：白墙自下而上盖住木构，同时迸一团灰。
    val build = remember { Animatable(1f) }
    var knownFloors by remember { mutableIntStateOf(completedFloors) }
    LaunchedEffect(completedFloors) {
        val grew = completedFloors > knownFloors
        knownFloors = completedFloors
        if (grew) {
            build.snapTo(0f)
            build.animateTo(1f, tween(durationMillis = 1500, easing = FastOutSlowInEasing))
        } else {
            build.snapTo(1f)
        }
    }

    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .testTag("execution-pause-resume")
            .clickable(enabled = enabled, onClick = onToggle),
    ) {
        drawTowerScene(
            sprites = sprites,
            floorImages = floorImages,
            scale = scale,
            floorProgress = floorProgress.coerceIn(0f, 1f),
            building = building,
            paused = paused,
            night = night,
            clock = clock,
            build = build.value,
        )
    }
}

// ------------------------------------------------------------------ 精灵

private const val FLOOR_SRC_W = 540f
private const val BASE_SRC_W = 648f
private const val BASE_SRC_H = 682f
private const val WIP_SRC_H = 470f
private const val ROOF_SRC_W = 567f
private const val ROOF_SRC_H = 259f

private const val BG_SRC_W = 1080f
private const val BG_SRC_H = 1920f
private const val BG_GROUND_Y = 1690f
private const val BG_BAND_TOP_Y = 300f

private const val CRANE_BASE_W = 165f
private const val CRANE_BASE_H = 163f
private const val CRANE_SEG_W = 110f
private const val CRANE_SEG_H = 204f
private const val CRANE_SEG_STEP = 186f
private const val CRANE_CAP_W = 150f
private const val CRANE_CAP_H = 147f

private const val JIB_SRC_W = 1188f
private const val JIB_SRC_H = 331f
private const val JIB_PIVOT_X = 65f
private const val JIB_PIVOT_Y = 81f
private const val JIB_CHORD_Y = 125f
private const val HOOK_SRC_W = 133f
private const val HOOK_SRC_H = 290f

/** 楼体最大宽度占画布宽的比例：再宽就顶到左右留白了。 */
private const val TOWER_MAX_WIDTH_RATIO = 0.30f

private val CableColor = Color(0xFFA5967A)
private val TrolleyColor = Color(0xFF5E4A38)
private val PauseVeil = Color(0x1C0B1A1C)

/** 夜景：整体压暗偏青。楼体压暗后窗光会被一起摁灭，所以另加一轮 drawWindowGlow。 */
private val NightTint = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.54f, 0.05f, 0.05f, 0f, -10f,
            0.04f, 0.52f, 0.07f, 0f, -4f,
            0.05f, 0.10f, 0.58f, 0f, 4f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/**
 * 只留下「红远高于蓝」的像素：白墙各通道接近，红蓝相减后归零；
 * 琥珀窗 R 比 B 高一截，相减后还是热的。叠 Plus 混合就是窗里透出的灯光。
 */
private val WindowGlow = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            1.10f, 0f, -1.10f, 0f, 0f,
            0.52f, 0f, -0.52f, 0f, 0f,
            0.10f, 0f, -0.10f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** 光晕：把同一套窗光放大一圈再加一遍，让灯光溢出窗框。单位是图源像素。 */
private const val GLOW_BLOOM_SRC_PX = 14f

private class TowerSprites(
    val bgDay: ImageBitmap,
    val bgNight: ImageBitmap,
    val base: ImageBitmap,
    val floors: List<ImageBitmap>,
    val wip: ImageBitmap,
    val roof: ImageBitmap,
    val craneBase: ImageBitmap,
    val craneSegment: ImageBitmap,
    val craneCap: ImageBitmap,
    val craneJib: ImageBitmap,
    val craneHook: ImageBitmap,
    val pine: ImageBitmap,
    val bush: ImageBitmap,
    val dust: ImageBitmap,
)

@Composable
private fun rememberTowerSprites(): TowerSprites {
    val bgDay = ImageBitmap.imageResource(R.drawable.tower_bg_day)
    val bgNight = ImageBitmap.imageResource(R.drawable.tower_bg_night)
    val base = ImageBitmap.imageResource(R.drawable.tower_base)
    val floorA = ImageBitmap.imageResource(R.drawable.tower_floor_a)
    val floorB = ImageBitmap.imageResource(R.drawable.tower_floor_b)
    val floorC = ImageBitmap.imageResource(R.drawable.tower_floor_c)
    val floorD = ImageBitmap.imageResource(R.drawable.tower_floor_d)
    val floorE = ImageBitmap.imageResource(R.drawable.tower_floor_e)
    val wip = ImageBitmap.imageResource(R.drawable.tower_floor_wip)
    val roof = ImageBitmap.imageResource(R.drawable.tower_roof)
    val craneBase = ImageBitmap.imageResource(R.drawable.tower_crane_base)
    val craneSegment = ImageBitmap.imageResource(R.drawable.tower_crane_segment)
    val craneCap = ImageBitmap.imageResource(R.drawable.tower_crane_cap)
    val craneJib = ImageBitmap.imageResource(R.drawable.tower_crane_jib)
    val craneHook = ImageBitmap.imageResource(R.drawable.tower_crane_hook)
    val pine = ImageBitmap.imageResource(R.drawable.tower_fg_pine)
    val bush = ImageBitmap.imageResource(R.drawable.tower_fg_bush)
    val dust = ImageBitmap.imageResource(R.drawable.tower_fx_dust)
    return remember(
        bgDay, bgNight, base, floorA, floorB, floorC, floorD, floorE, wip, roof,
        craneBase, craneSegment, craneCap, craneJib, craneHook, pine, bush, dust,
    ) {
        TowerSprites(
            bgDay = bgDay,
            bgNight = bgNight,
            base = base,
            floors = listOf(floorA, floorB, floorC, floorD, floorE),
            wip = wip,
            roof = roof,
            craneBase = craneBase,
            craneSegment = craneSegment,
            craneCap = craneCap,
            craneJib = craneJib,
            craneHook = craneHook,
            pine = pine,
            bush = bush,
            dust = dust,
        )
    }
}

/** 楼层样式按种子轮换，幅面不变，因此摞起来高度只差几像素。 */
private fun floorVariant(seed: Int, index: Int): Int {
    val base = ((seed / 7919 % 5) + 5) % 5
    return (base + index * 2) % 5
}

/**
 * 楼越高整组越矮：先卡墙宽上限，再卡竖直余量（楼基到天空留白之间）。
 * 两处常量都来自 SpriteReport 的实测值。层高按计划层数预估，比例整轮不变。
 */
private fun towerScale(width: Float, height: Float, plannedFloors: Int): Float {
    if (width < 1f || height < 1f) return 0f
    val bgScale = max(width / BG_SRC_W, height / BG_SRC_H)
    val bgTop = height - BG_SRC_H * bgScale
    val groundY = bgTop + BG_GROUND_Y * bgScale
    val bandTop = bgTop + BG_BAND_TOP_Y * bgScale
    // 最高就是封顶时：计划层数 + 屋顶；施工中的楼更高不过这个数。
    val storeys = plannedFloors * WIP_SRC_H + ROOF_SRC_H
    val room = (groundY - bandTop).coerceAtLeast(1f)
    return min(width * TOWER_MAX_WIDTH_RATIO / FLOOR_SRC_W, room / (BASE_SRC_H + storeys))
}

// ------------------------------------------------------------------ 绘制

private fun DrawScope.drawTowerScene(
    sprites: TowerSprites,
    floorImages: List<ImageBitmap>,
    scale: Float,
    floorProgress: Float,
    building: Boolean,
    paused: Boolean,
    night: Boolean,
    clock: Float,
    build: Float,
) {
    val w = size.width
    val h = size.height
    if (w < 1f || h < 1f || scale <= 0f) return
    val tint = if (night) NightTint else null

    val bgScale = max(w / BG_SRC_W, h / BG_SRC_H)
    val bgW = BG_SRC_W * bgScale
    val bgH = BG_SRC_H * bgScale
    val bgTop = h - bgH
    drawSprite(
        image = if (night) sprites.bgNight else sprites.bgDay,
        left = (w - bgW) / 2f,
        top = bgTop,
        width = bgW,
        height = bgH,
    )

    val groundY = bgTop + BG_GROUND_Y * bgScale
    val floorW = FLOOR_SRC_W * scale

    var towerH = (BASE_SRC_H + floorImages.fold(0f) { acc, img -> acc + img.height.toFloat() }) * scale
    if (building) towerH += WIP_SRC_H * scale else towerH += ROOF_SRC_H * scale
    val towerTop = groundY - towerH
    val cx = w * 0.5f
    val finishedTop = towerTop + if (building) WIP_SRC_H * scale else ROOF_SRC_H * scale

    drawCrane(
        sprites = sprites,
        scale = scale,
        floorW = floorW,
        centerX = cx,
        groundY = groundY,
        towerTop = towerTop,
        finishedTop = finishedTop,
        clock = clock,
        tint = tint,
    )

    var bottom = groundY
    val baseW = BASE_SRC_W * scale
    val baseH = BASE_SRC_H * scale
    drawSprite(sprites.base, cx - baseW / 2f, bottom - baseH, baseW, baseH, colorFilter = tint)
    bottom -= baseH

    floorImages.forEachIndexed { index, image ->
        val fw = image.width * scale
        val fh = image.height * scale
        val top = bottom - fh
        if (index == floorImages.lastIndex && build < 1f) {
            drawSprite(sprites.wip, cx - fw / 2f, top, fw, fh, colorFilter = tint)
            drawSpriteReveal(image, cx - fw / 2f, top, fw, fh, build, tint)
            drawDust(sprites.dust, cx, bottom, floorW, build, tint)
        } else {
            drawSprite(image, cx - fw / 2f, top, fw, fh, colorFilter = tint)
        }
        bottom = top
    }

    if (building) {
        val slotH = WIP_SRC_H * scale
        val reveal = 0.12f + 0.88f * floorProgress
        drawSpriteReveal(sprites.wip, cx - floorW / 2f, bottom - slotH, floorW, slotH, reveal, tint)
    } else {
        val roofW = ROOF_SRC_W * scale
        val roofH = ROOF_SRC_H * scale
        drawSprite(sprites.roof, cx - roofW / 2f, bottom - roofH, roofW, roofH, colorFilter = tint)
    }

    if (night) drawWindowGlow(sprites, floorImages, scale, groundY, cx, build)

    drawForeground(sprites, floorW, cx, groundY, tint)

    if (paused) drawRect(PauseVeil)
}

/**
 * 夜景第二轮。压暗滤镜不分青红皂白把白墙和窗里的琥珀灯一起摁灭，
 * 这里按同一套几何只把暖光重新加回去：先放大一圈做光晕，再原样点亮窗子。
 */
private fun DrawScope.drawWindowGlow(
    sprites: TowerSprites,
    floorImages: List<ImageBitmap>,
    scale: Float,
    groundY: Float,
    centerX: Float,
    build: Float,
) {
    val pad = GLOW_BLOOM_SRC_PX * scale

    fun glow(image: ImageBitmap, left: Float, top: Float, width: Float, height: Float, reveal: Float) {
        if (reveal >= 1f) {
            drawSprite(image, left - pad, top - pad, width + pad * 2f, height + pad * 2f, .5f, WindowGlow, BlendMode.Plus)
            drawSprite(image, left, top, width, height, 1f, WindowGlow, BlendMode.Plus)
        } else {
            drawSpriteReveal(image, left, top, width, height, reveal, WindowGlow, BlendMode.Plus)
        }
    }

    var bottom = groundY
    val baseW = BASE_SRC_W * scale
    val baseH = BASE_SRC_H * scale
    glow(sprites.base, centerX - baseW / 2f, bottom - baseH, baseW, baseH, 1f)
    bottom -= baseH

    floorImages.forEachIndexed { index, image ->
        val fw = image.width * scale
        val fh = image.height * scale
        val top = bottom - fh
        val reveal = if (index == floorImages.lastIndex && build < 1f) build else 1f
        glow(image, centerX - fw / 2f, top, fw, fh, reveal)
        bottom = top
    }
}

private fun DrawScope.drawCrane(
    sprites: TowerSprites,
    scale: Float,
    floorW: Float,
    centerX: Float,
    groundY: Float,
    towerTop: Float,
    finishedTop: Float,
    clock: Float,
    tint: ColorFilter?,
) {
    val mastCx = centerX - floorW * 0.5f - CRANE_SEG_W * scale * 0.6f
    val baseW = CRANE_BASE_W * scale
    val baseH = CRANE_BASE_H * scale
    val segW = CRANE_SEG_W * scale
    val segH = CRANE_SEG_H * scale
    val step = CRANE_SEG_STEP * scale
    val capW = CRANE_CAP_W * scale
    val capH = CRANE_CAP_H * scale

    val fixed = baseH + capH
    val wanted = (groundY - (towerTop - floorW * 0.62f) - fixed).coerceAtLeast(step)
    var segments = ceil(wanted / step).toInt().coerceAtLeast(1)
    while (segments > 1 && groundY - (fixed + segments * step) < 6f) segments--

    drawSprite(sprites.craneBase, mastCx - baseW / 2f, groundY - baseH, baseW, baseH, colorFilter = tint)
    var mastY = groundY - baseH
    repeat(segments) {
        drawSprite(sprites.craneSegment, mastCx - segW / 2f, mastY - segH, segW, segH, colorFilter = tint)
        mastY -= step
    }
    drawSprite(sprites.craneCap, mastCx - capW / 2f, mastY - capH, capW, capH, colorFilter = tint)
    val mastTop = mastY - capH

    val jibScale = floorW * 2.05f / JIB_SRC_W
    val sway = sin(clock * 2f * PI.toFloat() / 9f) * 3.2f
    val pivot = Offset(mastCx, mastTop)
    rotate(degrees = sway, pivot = pivot) {
        drawSprite(
            sprites.craneJib,
            mastCx - JIB_PIVOT_X * jibScale,
            mastTop - JIB_PIVOT_Y * jibScale,
            JIB_SRC_W * jibScale,
            JIB_SRC_H * jibScale,
            colorFilter = tint,
        )
    }

    // 小车停在楼体正上方，吊着的石料悬在已完工楼顶之上，落层时被新一层"吃"进去。
    val rad = sway * PI.toFloat() / 180f
    val ox = (0.36f * JIB_SRC_W - JIB_PIVOT_X) * jibScale
    val oy = (JIB_CHORD_Y - JIB_PIVOT_Y) * jibScale
    val trolleyX = mastCx + ox * cos(rad) - oy * sin(rad)
    val trolleyY = mastTop + ox * sin(rad) + oy * cos(rad)

    drawRect(
        color = TrolleyColor,
        topLeft = Offset(trolleyX - 11f * scale, trolleyY - 7f * scale),
        size = Size(22f * scale, 14f * scale),
    )

    val hookScale = scale * 0.60f
    val hookW = HOOK_SRC_W * hookScale
    val hookH = HOOK_SRC_H * hookScale
    val bob = sin(clock * 2f * PI.toFloat() / 5.5f)
    val hookBottom = finishedTop - floorW * 0.04f + bob * floorW * 0.05f
    val hookTop = hookBottom - hookH
    if (hookTop > trolleyY + 2f) {
        drawLine(
            color = CableColor,
            start = Offset(trolleyX, trolleyY),
            end = Offset(trolleyX, hookTop + hookH * 0.06f),
            strokeWidth = hookW * 0.20f,
        )
        drawSprite(sprites.craneHook, trolleyX - hookW / 2f, hookTop, hookW, hookH, colorFilter = tint)
    }
}

private fun DrawScope.drawForeground(
    sprites: TowerSprites,
    floorW: Float,
    centerX: Float,
    groundY: Float,
    tint: ColorFilter?,
) {
    val bushW = floorW * 1.9f
    val bushH = bushW * sprites.bush.height / sprites.bush.width
    drawSprite(
        sprites.bush,
        centerX - bushW / 2f + floorW * 0.16f,
        groundY - bushH * 0.58f,
        bushW,
        bushH,
        colorFilter = tint,
    )
    val pineW = floorW * 0.78f
    val pineH = pineW * sprites.pine.height / sprites.pine.width
    drawSprite(
        sprites.pine,
        centerX - floorW * 1.32f,
        groundY - pineH * 0.88f,
        pineW,
        pineH,
        colorFilter = tint,
    )
}

private fun DrawScope.drawDust(
    image: ImageBitmap,
    centerX: Float,
    baseY: Float,
    floorW: Float,
    build: Float,
    tint: ColorFilter?,
) {
    val t = ((build - 0.32f) / 0.68f).coerceIn(0f, 1f)
    if (t <= 0f) return
    val puffW = floorW * (0.55f + 0.85f * t)
    val puffH = puffW * image.height / image.width
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((centerX - puffW / 2f).roundToInt(), (baseY - puffH * 0.66f).roundToInt()),
        dstSize = IntSize(puffW.roundToInt().coerceAtLeast(1), puffH.roundToInt().coerceAtLeast(1)),
        alpha = (1f - t) * 0.9f,
        colorFilter = tint,
        filterQuality = FilterQuality.High,
    )
}

private fun DrawScope.drawSprite(
    image: ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
    blend: BlendMode = BlendMode.SrcOver,
) {
    if (width < 1f || height < 1f) return
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        colorFilter = colorFilter,
        blendMode = blend,
        filterQuality = FilterQuality.High,
    )
}

/** 自下而上露出：施工动画就是靠它把木构一层层盖成白墙。 */
private fun DrawScope.drawSpriteReveal(
    image: ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    reveal: Float,
    colorFilter: ColorFilter? = null,
    blend: BlendMode = BlendMode.SrcOver,
) {
    val r = reveal.coerceIn(0f, 1f)
    if (r <= 0.002f || width < 1f || height < 1f) return
    val srcH = (image.height * r).roundToInt().coerceIn(1, image.height)
    val dstH = (height * r).coerceAtLeast(1f)
    drawImage(
        image = image,
        srcOffset = IntOffset(0, image.height - srcH),
        srcSize = IntSize(image.width, srcH),
        dstOffset = IntOffset(left.roundToInt(), (top + height - dstH).roundToInt()),
        dstSize = IntSize(width.roundToInt().coerceAtLeast(1), dstH.roundToInt().coerceAtLeast(1)),
        colorFilter = colorFilter,
        blendMode = blend,
        filterQuality = FilterQuality.High,
    )
}
