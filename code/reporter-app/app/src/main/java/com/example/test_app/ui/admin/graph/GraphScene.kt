package com.example.test_app.ui.admin.graph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.test_app.admin.HighlightMode
import com.example.test_app.knowledgegraph.admin.graphview.ForceLayout
import com.example.test_app.knowledgegraph.admin.graphview.PathHighlight
import com.example.test_app.knowledgegraph.admin.graphview.VisibleGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The mutable, non-UI parts of the graph canvas: the laid out scene (positions, per-node arrays), the pan and zoom camera, the pixel
 * metrics derived from the theme and the precomputed highlight flags. Nothing here draws; see GraphCanvas.kt. doc/ADMIN_DASHBOARD.md.
 */

/** Side of the square world the layout lives in (the default of [ForceLayout]). */
internal const val WORLD = 1000f

/** Zoom limits (1 = the whole world fits the shorter side of the canvas). */
internal const val MIN_ZOOM = 0.3f
internal const val MAX_ZOOM = 6f

/** Zoom used by "focus" (double-tap, the sheet's Focus button, tapping a neighbour). */
internal const val FOCUS_ZOOM = 2.5f

/** Largest zoom "fit" may choose (so one or two nodes do not fill the screen). */
private const val FIT_MAX_ZOOM = 2.5f

/** Smallest extent (world units) fitted, so a single node gets a sensible zoom. */
private const val MIN_FIT_EXTENT = 120f

/** Above this many nodes the layout steps run off the main thread. */
internal const val HEAVY_NODES = 150

/** Pixel sizes taken from the theme tokens once (density aware), used by drawing and hit testing. */
internal class GraphMetrics(
    val rMin: Float, val rMax: Float, val hair: Float, val strong: Float, val arrow: Float, val ring: Float, val touch: Float,
    val pin: Float, val labelGap: Float, val fitPad: Float, val halo: Float,
)

/** Node radius factor of a zoom level: nodes grow a little when zooming in, shrink a little when zooming out. */
internal fun nodeZoomFactor(zoom: Float): Float = sqrt(zoom).coerceIn(0.6f, 2.2f)

/** Where the camera should end up. */
internal class CamTarget(val zoom: Float, val offX: Float, val offY: Float)

/**
 * Pan and zoom of the canvas. A world point (wx, wy) is drawn at (wx * scale + offX, wy * scale + offY) with `scale = base * zoom`,
 * where `base` makes the whole world fit the shorter side of the view. Animations run through [go] so a touch can cancel them.
 */
@Stable
internal class GraphCamera {
    var zoom by mutableFloatStateOf(1f)
    var offX by mutableFloatStateOf(0f)
    var offY by mutableFloatStateOf(0f)
    var viewW = 0f
        private set
    var viewH = 0f
        private set

    /** Bumped by every touch; an auto-fit that started before a touch must not run after it. */
    var touchEpoch = 0
        private set
    private var job: Job? = null

    val base: Float get() = if (viewW <= 0f || viewH <= 0f) 1f else min(viewW, viewH) / WORLD
    val scale: Float get() = base * zoom

    /** Called with the canvas size; keeps the world point at the view centre where it was (the first call centres the world). */
    fun onSize(w: Int, h: Int) {
        val had = viewW > 0f && viewH > 0f
        val cwx = if (had) (viewW / 2f - offX) / scale else WORLD / 2f
        val cwy = if (had) (viewH / 2f - offY) / scale else WORLD / 2f
        viewW = w.toFloat(); viewH = h.toFloat()
        offX = viewW / 2f - cwx * scale; offY = viewH / 2f - cwy * scale
    }

    /** A touch started: stop any animation and invalidate pending auto-fits. */
    fun stop() { job?.cancel(); touchEpoch++ }

    /** Starts [block] as the one running camera animation (the previous one is cancelled). */
    fun go(scope: CoroutineScope, block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    /** Pinch and drag: zoom by [zoomChange] around ([cx], [cy]) then move by ([panX], [panY]) (all in pixels). */
    fun transformBy(cx: Float, cy: Float, panX: Float, panY: Float, zoomChange: Float) {
        val z = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = z / zoom
        offX = cx - (cx - offX) * f + panX
        offY = cy - (cy - offY) * f + panY
        zoom = z
    }

    /** Zoom to [z] keeping the world point under pixel ([cx], [cy]) fixed. */
    fun zoomTargetAround(cx: Float, cy: Float, z: Float): CamTarget {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = nz / zoom
        return CamTarget(nz, cx - (cx - offX) * f, cy - (cy - offY) * f)
    }

    /** Centre world point ([wx], [wy]) in the view at zoom [z]. */
    fun focusTarget(wx: Float, wy: Float, z: Float): CamTarget {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val s = base * nz
        return CamTarget(nz, viewW / 2f - wx * s, viewH / 2f - wy * s)
    }

    /** Fit the world box into the view with [pad] pixels around it. */
    fun fitTarget(minX: Float, minY: Float, maxX: Float, maxY: Float, pad: Float): CamTarget {
        val bw = max(maxX - minX, MIN_FIT_EXTENT); val bh = max(maxY - minY, MIN_FIT_EXTENT)
        val zx = (viewW - 2f * pad) / (bw * base); val zy = (viewH - 2f * pad) / (bh * base)
        val z = min(zx, zy).coerceIn(MIN_ZOOM, FIT_MAX_ZOOM)
        return focusTarget((minX + maxX) / 2f, (minY + maxY) / 2f, z)
    }

    /** Moves to [t]: at once when [durationMs] is 0 (reduce motion), else animated. */
    suspend fun animateTo(t: CamTarget, durationMs: Int) {
        if (durationMs <= 0) { zoom = t.zoom; offX = t.offX; offY = t.offY; return }
        val z0 = zoom; val x0 = offX; val y0 = offY
        Animatable(0f).animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) {
            zoom = z0 + (t.zoom - z0) * value; offX = x0 + (t.offX - x0) * value; offY = y0 + (t.offY - y0) * value
        }
    }
}

/**
 * One laid out subgraph: the [ForceLayout] and the flat per-node and per-edge arrays the canvas reads every frame. [tick] changes
 * whenever positions change so the canvas redraws. Layout steps run under `synchronized(layout)` because pins and drags come from
 * the main thread while heavy graphs step on a background thread; drawing reads the position arrays without a lock (a torn frame
 * is harmless).
 */
@Stable
internal class GraphScene(val graph: VisibleGraph, initial: Map<String, Pair<Double, Double>>, seed: Long, runNow: Boolean) {
    val n: Int = graph.nodes.size
    val ids: Array<String> = Array(n) { graph.nodes[it].id }
    private val index = HashMap<String, Int>(n * 2).also { map -> for (i in 0 until n) map[ids[i]] = i }
    val layout = ForceLayout(ids.asList(), graph.edgeIndexPairs(), initial = initial, seed = seed)

    val edgeA: IntArray
    val edgeB: IntArray
    val edgeRelation: IntArray
    val edgeIds: Array<String>
    val m: Int

    val kindIdx: IntArray = IntArray(n) { kindIndex(graph.nodes[it].kind) }
    val sizeT: FloatArray
    val nodeActive: BooleanArray = BooleanArray(n) { graph.nodes[it].active }
    val labels: Array<String> = Array(n) { shortLabel(graph.nodes[it].label) }

    /** Screen positions of the current frame (filled by the canvas). */
    val sx = FloatArray(n)
    val sy = FloatArray(n)

    var tick by mutableIntStateOf(0)
        private set

    /** False while a reduce-motion layout is still being computed (the canvas draws nothing until it is true). */
    var ready by mutableStateOf(true)
        private set

    /** Signals the layout loop that the layout was reheated. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    val heavy: Boolean get() = n > HEAVY_NODES

    init {
        val a = ArrayList<Int>(graph.edges.size); val b = ArrayList<Int>(graph.edges.size)
        val r = ArrayList<Int>(graph.edges.size); val eid = ArrayList<String>(graph.edges.size)
        for (e in graph.edges) {
            val ia = index[e.sourceId] ?: continue
            val ib = index[e.targetId] ?: continue
            a += ia; b += ib; r += e.relation.ordinal; eid += e.id
        }
        m = a.size
        edgeA = a.toIntArray(); edgeB = b.toIntArray(); edgeRelation = r.toIntArray(); edgeIds = eid.toTypedArray()
        val maxDeg = max(1, graph.nodes.maxOfOrNull { it.degree } ?: 1)
        sizeT = FloatArray(n) { sqrt(graph.nodes[it].degree.toFloat() / maxDeg) }
        val known = ids.count { it in initial }
        if (n > 0 && known * 2 >= n) layout.reheat(0.3) // most positions come from the cache: relax gently instead of scrambling the picture
        if (runNow) {
            if (n <= HEAVY_NODES) layout.run() else ready = false // a heavy layout is computed off the main thread (see AdminGraphTab)
        }
    }

    fun indexOf(id: String): Int = index[id] ?: -1

    /** Call after positions changed so the canvas redraws. */
    fun bump() { tick++ }

    /** Reads [tick] from a draw scope so the canvas is invalidated when positions change. */
    fun observe(): Int = tick

    fun markReady() { ready = true; tick++ }

    /** Runs up to [steps] layout iterations (stops when cooled). */
    fun step(steps: Int) {
        synchronized(layout) { repeat(steps) { if (!layout.finished) layout.step() } }
    }

    /** Runs the layout to completion. */
    fun runToEnd() { synchronized(layout) { layout.run() } }

    /** Reheats a little (or fully) and wakes the layout loop. */
    fun reheat(fraction: Double) {
        synchronized(layout) { layout.reheat(fraction) }
        wake.trySend(Unit)
    }

    fun togglePin(i: Int) {
        synchronized(layout) {
            if (layout.pinned[i]) layout.unpin(i) else layout.pin(i, layout.x[i], layout.y[i])
        }
        bump()
    }

    fun unpinAll() { synchronized(layout) { for (i in 0 until n) layout.unpin(i) }; bump() }

    fun beginDrag(i: Int) { synchronized(layout) { layout.pin(i, layout.x[i], layout.y[i]) } }

    fun dragTo(i: Int, wx: Double, wy: Double) { synchronized(layout) { layout.pin(i, wx, wy) }; bump() }

    /** A dragged node stays pinned where it was dropped; the others relax around it. */
    fun endDrag() { reheat(DRAG_REHEAT) }

    /** Remembers the current positions (for the next scene of the same graph version). */
    fun savePositions(cache: MutableMap<String, Pair<Double, Double>>) {
        synchronized(layout) { for (i in 0 until n) cache[ids[i]] = layout.x[i] to layout.y[i] }
    }

    /** World bounding box (minX, minY, maxX, maxY) of the nodes in [indices] (all nodes when null), or null when empty. */
    fun bounds(indices: Collection<Int>? = null): FloatArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var any = false
        fun add(i: Int) {
            val x = layout.x[i].toFloat(); val y = layout.y[i].toFloat()
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); any = true
        }
        if (indices == null) for (i in 0 until n) add(i) else for (i in indices) if (i in 0 until n) add(i)
        return if (any) floatArrayOf(minX, minY, maxX, maxY) else null
    }

    fun radius(i: Int, metrics: GraphMetrics, zoomFactor: Float): Float = (metrics.rMin + (metrics.rMax - metrics.rMin) * sizeT[i]) * zoomFactor

    /** Nearest node whose circle (or a touch-sized disc, whichever is larger) contains the pixel ([px], [py]); -1 when none. */
    fun hit(camera: GraphCamera, px: Float, py: Float, metrics: GraphMetrics): Int {
        val sc = camera.scale; val ox = camera.offX; val oy = camera.offY
        val zf = nodeZoomFactor(camera.zoom)
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val dx = (layout.x[i] * sc + ox).toFloat() - px; val dy = (layout.y[i] * sc + oy).toFloat() - py
            val d2 = dx * dx + dy * dy
            val reach = max(metrics.touch, radius(i, metrics, zf))
            if (d2 <= reach * reach && d2 < bestD) { best = i; bestD = d2 }
        }
        return best
    }

    private companion object {
        const val DRAG_REHEAT = 0.2
    }
}

/** Flags of the precomputed highlight state, one byte per node and per edge. */
internal const val F_DIM = 1
internal const val F_FOCUS = 2
internal const val F_GLOW = 4
internal const val F_SUPPRESSED = 8
internal const val F_LABEL = 16

/**
 * How every node and edge of a [GraphScene] is emphasised right now: search matches, the incident or preview path, or the selected
 * node with its neighbours; everything outside the emphasis is dimmed. [hasGlow] is true when something pulses.
 */
internal class GraphStyle(val nodeFlags: ByteArray, val edgeFlags: ByteArray, val selected: Int, val hasGlow: Boolean)

internal fun isPathMode(mode: HighlightMode): Boolean =
    mode == HighlightMode.INCIDENT_PATH || mode == HighlightMode.PREVIEW_REPORTER || mode == HighlightMode.PREVIEW_ADMIN

internal fun buildStyle(
    scene: GraphScene, mode: HighlightMode, highlight: PathHighlight, selectedId: String?, matched: Set<String>, queryActive: Boolean, showSuppressed: Boolean,
): GraphStyle {
    val path = isPathMode(mode)
    val sel = selectedId?.let { scene.indexOf(it) } ?: -1
    val neighbourhood = !path && sel >= 0
    val anyFocus = path || neighbourhood || queryActive
    val nf = IntArray(scene.n)
    val ef = IntArray(scene.m)
    var glow = false
    for (i in 0 until scene.n) {
        val id = scene.ids[i]
        var f = 0
        if (path && id in highlight.nodeIds) { f = f or F_FOCUS or F_GLOW or F_LABEL; glow = true }
        if (showSuppressed && id in highlight.suppressedNodeIds) f = f or F_SUPPRESSED or F_FOCUS or F_LABEL
        if (queryActive && id in matched) f = f or F_FOCUS or F_LABEL
        if (i == sel) f = f or F_FOCUS or F_LABEL
        nf[i] = f
    }
    for (e in 0 until scene.m) {
        val id = scene.edgeIds[e]
        val a = scene.edgeA[e]; val b = scene.edgeB[e]
        var f = 0
        if (path && id in highlight.edgeIds) { f = f or F_FOCUS or F_GLOW; glow = true }
        if (showSuppressed && id in highlight.suppressedEdgeIds) f = f or F_SUPPRESSED or F_FOCUS
        if (neighbourhood && (a == sel || b == sel)) {
            f = f or F_FOCUS
            val other = if (a == sel) b else a
            nf[other] = nf[other] or F_FOCUS or F_LABEL
        }
        if (queryActive && scene.ids[a] in matched && scene.ids[b] in matched) f = f or F_FOCUS
        ef[e] = f
    }
    if (anyFocus) {
        for (i in 0 until scene.n) if (nf[i] and F_FOCUS == 0) nf[i] = nf[i] or F_DIM
        for (e in 0 until scene.m) if (ef[e] and F_FOCUS == 0) ef[e] = ef[e] or F_DIM
    }
    return GraphStyle(ByteArray(scene.n) { nf[it].toByte() }, ByteArray(scene.m) { ef[it].toByte() }, sel, glow)
}
