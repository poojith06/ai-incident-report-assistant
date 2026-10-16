package com.example.test_app.ui.admin.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.example.test_app.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

// Drawing constants (alphas, thresholds and canvas math; sizes come from GraphMetrics, which come from the theme tokens).
private const val ALPHA_EDGE_DIM = 0.10f
private const val ALPHA_EDGE = 0.40f
private const val ALPHA_EDGE_FOCUS = 0.95f
private const val ALPHA_NODE_DIM = 0.25f
private const val ALPHA_NODE_INACTIVE = 0.55f
private const val ALPHA_LABEL_DIM = 0.45f
private const val GLOW_EDGE_WIDTH = 3f
private const val GLOW_EDGE_BASE = 0.12f
private const val GLOW_EDGE_PULSE = 0.20f
private const val GLOW_NODE_BASE = 0.15f
private const val GLOW_NODE_PULSE = 0.20f
private const val HALO_MIN = 0.5f
private const val HALO_RANGE = 1.0f
private const val LABEL_ZOOM = 1.5f
private const val ARROW_MIN_ZOOM = 0.7f
private const val ARROW_COS = 0.8988f // wing angle 26 degrees
private const val ARROW_SIN = 0.4384f
private const val MAX_LABELS = 90
private const val TEXT_CACHE = 512
private const val PIN_OFFSET = 0.7f

private const val OUT_PENDING = 0
private const val OUT_UP = 1
private const val OUT_DRAG = 2
private const val OUT_MULTI = 3
private const val OUT_CANCEL = 4

/**
 * The graph drawing surface. Edges are lines coloured by relation (with an arrowhead near the target), nodes are circles coloured by
 * kind and sized by degree, labels appear when zoomed in or for emphasised nodes. [style] decides what is dimmed, glowing (pulses with
 * [pulse]) or warning-coloured. Gestures: drag the background to pan, pinch to zoom, tap a node to select it (tap the background to
 * clear), long-press a node to pin or unpin it, drag a node to move it (it stays pinned), double-tap a node to focus it.
 * See doc/ADMIN_DASHBOARD.md.
 */
@Composable
internal fun GraphCanvas(
    scene: GraphScene,
    camera: GraphCamera,
    style: GraphStyle,
    metrics: GraphMetrics,
    pulse: State<Float>,
    description: String,
    onTap: (String?) -> Unit,
    onDoubleTap: (String?, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val palette = c.graphPalette
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE)
    val labelStyle = AppTheme.type.caption
    val shadow = remember(c, metrics) { Shadow(color = c.background, offset = Offset.Zero, blurRadius = metrics.halo) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val tap = rememberUpdatedState(onTap)
    val doubleTap = rememberUpdatedState(onDoubleTap)

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { camera.onSize(it.width, it.height) }
            .pointerInput(scene, camera, metrics) {
                graphGestures(scene, camera, metrics, scope, tap, doubleTap) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            }
            .semantics { contentDescription = description },
    ) {
        scene.observe() // positions changed -> redraw
        if (!scene.ready) return@Canvas
        val n = scene.n
        val edgeCount = scene.m
        val zoom = camera.zoom
        val sc = camera.base * zoom
        val ox = camera.offX
        val oy = camera.offY
        val w = size.width
        val h = size.height
        val zf = nodeZoomFactor(zoom)
        val lx = scene.layout.x
        val ly = scene.layout.y
        val sx = scene.sx
        val sy = scene.sy
        for (i in 0 until n) {
            sx[i] = (lx[i] * sc + ox).toFloat()
            sy[i] = (ly[i] * sc + oy).toFloat()
        }
        val nf = style.nodeFlags
        val ef = style.edgeFlags
        val margin = metrics.rMax * 3f
        val pulseValue = if (style.hasGlow) pulse.value else 0f
        val hairStroke = Stroke(metrics.hair)
        val ringStroke = Stroke(metrics.strong)
        val arrowLen = metrics.arrow * zf
        val drawArrows = zoom >= ARROW_MIN_ZOOM

        // edges: dimmed first, emphasised last
        for (pass in 0..2) {
            for (e in 0 until edgeCount) {
                val f = ef[e].toInt()
                val p = if (f and F_FOCUS != 0) 2 else if (f and F_DIM != 0) 0 else 1
                if (p != pass) continue
                val a = scene.edgeA[e]
                val b = scene.edgeB[e]
                val ax = sx[a]
                val ay = sy[a]
                val bx = sx[b]
                val by = sy[b]
                if ((ax < -margin && bx < -margin) || (ax > w + margin && bx > w + margin) || (ay < -margin && by < -margin) || (ay > h + margin && by > h + margin)) continue
                val color = if (f and F_SUPPRESSED != 0) c.warning else palette[scene.edgeRelation[e] % palette.size]
                val from = Offset(ax, ay)
                val to = Offset(bx, by)
                if (f and F_GLOW != 0) {
                    drawLine(color, from, to, strokeWidth = metrics.strong * GLOW_EDGE_WIDTH, cap = StrokeCap.Round, alpha = GLOW_EDGE_BASE + GLOW_EDGE_PULSE * pulseValue)
                }
                val alpha = when (p) { 0 -> ALPHA_EDGE_DIM; 1 -> ALPHA_EDGE; else -> ALPHA_EDGE_FOCUS }
                drawLine(color, from, to, strokeWidth = if (p == 2) metrics.strong else metrics.hair, alpha = alpha)
                if (drawArrows && p > 0) {
                    val dx = bx - ax
                    val dy = by - ay
                    val len = sqrt(dx * dx + dy * dy)
                    val rb = scene.radius(b, metrics, zf)
                    if (len > (rb + arrowLen) * 2f) {
                        val ux = dx / len
                        val uy = dy / len
                        val tip = Offset(bx - ux * (rb + metrics.hair), by - uy * (rb + metrics.hair))
                        val backX = -ux
                        val backY = -uy
                        val left = Offset((backX * ARROW_COS - backY * ARROW_SIN) * arrowLen, (backX * ARROW_SIN + backY * ARROW_COS) * arrowLen)
                        val right = Offset((backX * ARROW_COS + backY * ARROW_SIN) * arrowLen, (-backX * ARROW_SIN + backY * ARROW_COS) * arrowLen)
                        drawLine(color, tip, tip + left, strokeWidth = metrics.hair, alpha = alpha)
                        drawLine(color, tip, tip + right, strokeWidth = metrics.hair, alpha = alpha)
                    }
                }
            }
        }

        // nodes: dimmed and plain first, emphasised on top
        for (pass in 0..1) {
            for (i in 0 until n) {
                val f = nf[i].toInt()
                val focus = f and F_FOCUS != 0
                if ((if (focus) 1 else 0) != pass) continue
                val cx = sx[i]
                val cy = sy[i]
                if (cx < -margin || cx > w + margin || cy < -margin || cy > h + margin) continue
                val r = scene.radius(i, metrics, zf)
                val color = if (f and F_SUPPRESSED != 0) c.warning else palette[scene.kindIdx[i] % palette.size]
                val dim = f and F_DIM != 0
                val alpha = if (dim) ALPHA_NODE_DIM else if (!scene.nodeActive[i]) ALPHA_NODE_INACTIVE else 1f
                val center = Offset(cx, cy)
                if (f and F_GLOW != 0) {
                    drawCircle(color, r + metrics.halo * (HALO_MIN + HALO_RANGE * pulseValue), center, alpha = GLOW_NODE_BASE + GLOW_NODE_PULSE * (1f - pulseValue))
                }
                drawCircle(color, r, center, alpha = alpha)
                if (!dim) drawCircle(c.background, r, center, alpha = alpha, style = hairStroke)
                if (f and F_SUPPRESSED != 0) drawCircle(c.warning, r + metrics.hair * 2f, center, style = ringStroke)
                if (scene.layout.pinned[i]) {
                    val pc = Offset(cx + r * PIN_OFFSET, cy - r * PIN_OFFSET)
                    drawCircle(c.background, metrics.pin + metrics.hair, pc)
                    drawCircle(c.onBackground, metrics.pin, pc)
                }
            }
        }
        if (style.selected >= 0) {
            val i = style.selected
            drawCircle(c.onBackground, scene.radius(i, metrics, zf) + metrics.ring, Offset(sx[i], sy[i]), style = ringStroke)
        }

        // labels: emphasised nodes always, every node once zoomed in
        var drawn = 0
        for (pass in 0..1) {
            if (pass == 1 && zoom < LABEL_ZOOM) break
            for (i in 0 until n) {
                if (drawn >= MAX_LABELS) break
                val f = nf[i].toInt()
                val forced = f and F_LABEL != 0
                if ((pass == 0) != forced) continue
                val cx = sx[i]
                val cy = sy[i]
                if (cx < 0f || cx > w || cy < 0f || cy > h) continue
                val result = measurer.measure(scene.labels[i], labelStyle, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1)
                val top = cy + scene.radius(i, metrics, zf) + metrics.labelGap
                drawText(
                    result, color = c.onSurface, topLeft = Offset(cx - result.size.width / 2f, top),
                    alpha = if (f and F_DIM != 0) ALPHA_LABEL_DIM else 1f, shadow = shadow,
                )
                drawn++
            }
        }
    }
}

/**
 * One touch gesture at a time: tap (selects, delayed until it cannot be a double-tap), double-tap, long-press (pin), drag a node,
 * drag the background (pan) and pinch (zoom around the fingers). A touch cancels any running camera animation.
 */
private suspend fun PointerInputScope.graphGestures(
    scene: GraphScene,
    camera: GraphCamera,
    metrics: GraphMetrics,
    scope: CoroutineScope,
    onTap: State<(String?) -> Unit>,
    onDoubleTap: State<(String?, Offset) -> Unit>,
    onPinned: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
    var lastTapTime = -1L
    var lastTapPos = Offset.Zero
    var pending: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        camera.stop()
        val start = down.position
        val hit = scene.hit(camera, start.x, start.y, metrics)
        val hitId = if (hit >= 0) scene.ids[hit] else null
        var outcome = OUT_PENDING
        var upTime = down.uptimeMillis
        val beforeLongPress = withTimeoutOrNull(longPressMs) {
            while (outcome == OUT_PENDING) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.count { it.pressed } > 1) outcome = OUT_MULTI
                else if (change == null || change.isConsumed) outcome = OUT_CANCEL
                else if (change.changedToUp()) { outcome = OUT_UP; upTime = change.uptimeMillis }
                else if ((change.position - start).getDistance() > slop) outcome = OUT_DRAG
            }
            true
        }
        if (beforeLongPress == null) {
            if (hit >= 0) {
                scene.togglePin(hit)
                onPinned()
                consumeUntilUp()
            } else {
                transformLoop(camera)
            }
        } else when (outcome) {
            OUT_UP -> {
                val isDouble = lastTapTime >= 0 && down.uptimeMillis - lastTapTime <= doubleTapMs && (start - lastTapPos).getDistance() <= metrics.touch * 2f
                pending?.cancel()
                if (isDouble) {
                    lastTapTime = -1L
                    onDoubleTap.value(hitId, start)
                } else {
                    lastTapTime = upTime
                    lastTapPos = start
                    pending = scope.launch { delay(doubleTapMs); onTap.value(hitId) }
                }
            }
            OUT_DRAG -> if (hit >= 0) dragNode(scene, camera, hit, down.id, start) else transformLoop(camera)
            OUT_MULTI -> transformLoop(camera)
            else -> Unit
        }
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) break
    }
}

/** Pan with one finger, pinch-zoom around the fingers with two. */
private suspend fun AwaitPointerEventScope.transformLoop(camera: GraphCamera) {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        val centroid = event.calculateCentroid(useCurrent = false)
        if (centroid.isSpecified && (zoom != 1f || pan != Offset.Zero)) {
            camera.transformBy(centroid.x, centroid.y, pan.x, pan.y, zoom)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    }
}

/** Moves node [index] with the finger (it keeps its offset to the finger) and leaves it pinned where it was dropped. */
private suspend fun AwaitPointerEventScope.dragNode(scene: GraphScene, camera: GraphCamera, index: Int, pointerId: PointerId, start: Offset) {
    val startX = scene.layout.x[index]
    val startY = scene.layout.y[index]
    val sc = camera.scale
    scene.beginDrag(index)
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
        if (!change.pressed) break
        if (change.positionChanged()) {
            scene.dragTo(index, startX + (change.position.x - start.x) / sc, startY + (change.position.y - start.y) / sc)
            change.consume()
        }
    }
    scene.endDrag()
}
