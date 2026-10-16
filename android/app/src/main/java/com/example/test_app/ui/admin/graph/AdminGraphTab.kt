package com.example.test_app.ui.admin.graph

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.knowledgegraph.admin.graphview.GraphKinds
import com.example.test_app.knowledgegraph.admin.graphview.GraphSubgraph
import com.example.test_app.knowledgegraph.admin.graphview.GraphViewOptions
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SEED = 7L
private const val LIGHT_STEPS = 6
private const val HEAVY_STEPS = 3
private const val MAX_WAIT_FRAMES = 600
private const val PANEL_FRACTION = 0.5f
private const val MATCH_CHIPS = 12
private const val FIT_FOCUS_ZOOM = 1.5f
private const val DOUBLE_TAP_ZOOM_STEP = 2f

/**
 * The Graph tab of the admin dashboard (ADR-058): an Obsidian-like interactive picture of the active knowledge graph, drawn on a
 * Compose Canvas (no library). The picture is laid out by the pure force layout (see `ForceLayout`) a few steps per frame, so it
 * settles in front of the admin; with reduce motion it is finished before the first draw.
 *
 * Gestures on the canvas: drag the background to pan; pinch to zoom around the fingers; tap a node to select it and open its sheet;
 * tap the background to clear the selection; long-press a node to pin or unpin it (a pin mark shows); press and drag a node to move
 * it (it stays pinned where it is dropped); double-tap a node to zoom to it and make it the focus of the local graph. A touch stops
 * any running camera animation. The search field, the chips of search matches and the node sheet are the accessible path to every node.
 *
 * Above the canvas: search, a collapsible Filters panel (kinds, relations, only approved, orphans, suppressed candidates, global or
 * local graph with depth), a banner while an incident path or a preview is highlighted, fit-to-screen (also clears pins) and re-layout.
 * Colours: nodes by kind (legend), edges by relation, warning colour for suppressed candidates, a pulsing glow for the highlighted path.
 * See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminGraphTab(state: AdminUiState, vm: AdminViewModel) {
    val pack = state.snapshot?.pack ?: return
    val g = state.graph
    val options = g.options
    val c = AppTheme.colors
    val s = AppTheme.spacing
    val reduceMotion = AppTheme.reduceMotion
    val motionMs = if (reduceMotion) 0 else AppTheme.motion.medium
    val density = LocalDensity.current
    val sizes = AppTheme.sizes
    val scope = rememberCoroutineScope()
    val camera = remember { GraphCamera() }
    val lookup = remember(pack) { PackLookup(pack) }
    val metrics = remember(density, s, sizes) {
        with(density) {
            GraphMetrics(
                rMin = s.xs.toPx(), rMax = (s.sm + s.xs).toPx(), hair = sizes.borderWidth.toPx(), strong = sizes.borderWidthStrong.toPx(),
                arrow = s.sm.toPx(), ring = s.xs.toPx(), touch = (sizes.minTouch / 2).toPx(), pin = (s.xxs * 1.5f).toPx(),
                labelGap = s.xxs.toPx(), fitPad = s.xxl.toPx(), halo = s.sm.toPx(),
            )
        }
    }

    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var legendOpen by rememberSaveable { mutableStateOf(false) }
    var hiddenFor by rememberSaveable { mutableStateOf<String?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    var refit by remember { mutableStateOf(false) }

    // ---- the visible subgraph and its layout ------------------------------------------------------------------
    val pathMode = isPathMode(g.mode)
    val forcedIds = remember(pathMode, g.highlight, g.showSuppressed) {
        if (!pathMode) emptySet() else if (g.showSuppressed) g.highlight.nodeIds + g.highlight.suppressedNodeIds else g.highlight.nodeIds
    }
    val visible = remember(pack, options, forcedIds) { GraphSubgraph.build(pack, options, forcedIds) }
    val totalMatching = remember(pack, options, forcedIds, visible.truncated) {
        if (visible.truncated) GraphSubgraph.build(pack, options.copy(maxNodes = Int.MAX_VALUE), forcedIds).nodes.size else visible.nodes.size
    }
    val cache = remember(pack.packVersion) { HashMap<String, Pair<Double, Double>>() }
    val last = remember { arrayOfNulls<GraphScene>(1) }
    val nodeIds = remember(visible) { visible.nodeIds }
    val edgePairs = remember(visible) { visible.edgeIndexPairs() }
    val scene = remember(pack, nodeIds, edgePairs, epoch) {
        last[0]?.savePositions(cache) // a filter change keeps the picture: the new layout starts from where the old nodes are
        GraphScene(visible, cache, SEED + epoch, reduceMotion).also { last[0] = it }
    }
    LaunchedEffect(scene, reduceMotion) {
        try { runLayout(scene, reduceMotion, cache) } finally { scene.savePositions(cache) }
    }

    val queryActive = options.query.isNotBlank()
    val style = remember(scene, g.mode, g.highlight, g.selectedNodeId, visible.matchedIds, queryActive, g.showSuppressed) {
        buildStyle(scene, g.mode, g.highlight, g.selectedNodeId, visible.matchedIds, queryActive, g.showSuppressed)
    }
    val pulse: State<Float> = if (style.hasGlow && !reduceMotion) {
        rememberInfiniteTransition(label = "graph glow").animateFloat(
            initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(AppTheme.motion.pulse), RepeatMode.Reverse), label = "graph glow pulse",
        )
    } else remember { mutableFloatStateOf(0.5f) }

    // ---- camera helpers ------------------------------------------------------------------------------------------
    fun focusOn(id: String, zoom: Float) {
        val i = scene.indexOf(id)
        if (i < 0) return
        val t = camera.focusTarget(scene.layout.x[i].toFloat(), scene.layout.y[i].toFloat(), zoom)
        camera.go(scope) { camera.animateTo(t, motionMs) }
    }
    fun fitAll() {
        val b = scene.bounds() ?: return
        val t = camera.fitTarget(b[0], b[1], b[2], b[3], metrics.fitPad)
        camera.go(scope) { camera.animateTo(t, motionMs) }
    }

    val sceneNow = rememberUpdatedState(scene)
    val fitAllNow = rememberUpdatedState(::fitAll)
    // an incident path or a preview opens: once the layout settled, zoom to the highlighted nodes
    val highlightKey = if (pathMode && !g.highlight.isEmpty) g.highlight else null
    LaunchedEffect(highlightKey) {
        val hl = highlightKey ?: return@LaunchedEffect
        val epochAtStart = camera.touchEpoch
        var frames = 0
        while ((camera.viewW <= 0f || !sceneNow.value.ready || !sceneNow.value.layout.finished) && frames < MAX_WAIT_FRAMES) { withFrameNanos { }; frames++ }
        if (camera.touchEpoch != epochAtStart) return@LaunchedEffect
        val sc = sceneNow.value
        val wanted = if (g.showSuppressed) hl.nodeIds + hl.suppressedNodeIds else hl.nodeIds
        val b = sc.bounds(wanted.map { sc.indexOf(it) }.filter { it >= 0 }) ?: return@LaunchedEffect
        val t = camera.fitTarget(b[0], b[1], b[2], b[3], metrics.fitPad)
        camera.go(scope) { camera.animateTo(t, motionMs) }
    }
    // the scope (global or local) or the filters were reset: fit the new picture once it settled
    LaunchedEffect(scene, refit) {
        if (!refit) return@LaunchedEffect
        val epochAtStart = camera.touchEpoch
        var frames = 0
        while ((camera.viewW <= 0f || !scene.ready || !scene.layout.finished) && frames < MAX_WAIT_FRAMES) { withFrameNanos { }; frames++ }
        refit = false
        if (camera.touchEpoch == epochAtStart) fitAllNow.value()
    }
    LaunchedEffect(g.selectedNodeId) { if (g.selectedNodeId == null) hiddenFor = null }

    // ---- derived values for the controls --------------------------------------------------------------------------
    val kindsPresent = remember(visible) {
        val present = visible.nodes.mapTo(HashSet()) { it.kind }
        GraphKinds.ALL.filter { it in present } + present.filter { it !in GraphKinds.ALL }.sorted()
    }
    val matchChips = remember(visible) {
        visible.nodes.filter { it.id in visible.matchedIds }.sortedByDescending { it.degree }.take(MATCH_CHIPS).map { MatchChip(it.id, it.label, it.kind) }
    }
    val activeFilters = options.hiddenKinds.size + options.hiddenRelations.size + (if (options.onlyApprovedActive) 1 else 0) +
        (if (!options.showOrphans) 1 else 0) + (if (options.local) 1 else 0)
    val focusId = if (options.local) options.focusNodeId else (g.selectedNodeId ?: options.focusNodeId)
    val hasSuppressed = g.highlight.suppressedNodeIds.isNotEmpty() || g.highlight.suppressedEdgeIds.isNotEmpty()

    fun resetFilters() { vm.updateGraph { it.copy(options = GraphViewOptions()) }; refit = true }
    fun select(id: String) { hiddenFor = null; vm.selectNode(id); focusOn(id, maxOf(camera.zoom, FIT_FOCUS_ZOOM)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelMax = maxHeight * PANEL_FRACTION
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(s.xs)) {
            GraphSearchRow(
                query = options.query, onQuery = { q -> vm.updateGraph { it.copy(options = it.options.copy(query = q)) } },
                filtersOpen = filtersOpen, activeFilters = activeFilters, onToggleFilters = { filtersOpen = !filtersOpen },
                onResetView = { fitAll(); scene.unpinAll(); scene.reheat(RESET_REHEAT); refit = true },
                onRelayout = { cache.clear(); last[0] = null; epoch++; refit = true },
            )
            if (queryActive && matchChips.isNotEmpty()) GraphMatchRow(matchChips, visible.matchedIds.size, onSelect = ::select)
            if (pathMode) {
                GraphHighlightBanner(
                    label = g.highlightLabel ?: "Highlight", nodes = g.highlight.nodeIds.size, edges = g.highlight.edgeIds.size,
                    suppressed = g.highlight.suppressedNodeIds.size + g.highlight.suppressedEdgeIds.size, onClear = vm::clearHighlight,
                )
            }
            if (filtersOpen) {
                Box(Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())) {
                    GraphFiltersPanel(
                        options = options, showSuppressed = g.showSuppressed,
                        canGoLocal = g.selectedNodeId != null || options.focusNodeId != null, focusLabel = focusId?.let { lookup.label(it) },
                        onOptions = { f -> vm.updateGraph { it.copy(options = f(it.options)) } },
                        onShowSuppressed = { v -> vm.updateGraph { it.copy(showSuppressed = v) } },
                        onLocal = { enable ->
                            vm.updateGraph { st ->
                                val focus = st.selectedNodeId ?: st.options.focusNodeId
                                st.copy(options = st.options.copy(local = enable && focus != null, focusNodeId = if (enable) focus else st.options.focusNodeId))
                            }
                            refit = true
                        },
                        onReset = ::resetFilters,
                    )
                }
            }
            GraphCountLine(
                nodes = visible.nodes.size, edges = visible.edges.size, truncated = visible.truncated, cap = options.maxNodes, total = totalMatching,
                matches = if (queryActive) visible.matchedIds.size else null,
            )
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(AppTheme.radii.md))
                    .background(c.background).border(sizes.borderWidth, c.border, RoundedCornerShape(AppTheme.radii.md)),
            ) {
                if (visible.nodes.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(s.lg), verticalArrangement = Arrangement.spacedBy(s.sm, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (pack.nodes.isEmpty() && pack.sources.isEmpty()) "The active graph has no nodes" else "No nodes match these filters",
                            style = AppTheme.type.title, color = c.onBackground, textAlign = TextAlign.Center,
                        )
                        SecondaryButton("Reset filters", ::resetFilters)
                    }
                } else {
                    GraphCanvas(
                        scene = scene, camera = camera, style = style, metrics = metrics, pulse = pulse,
                        description = "Knowledge graph, ${visible.nodes.size} nodes, ${visible.edges.size} links. Drag to pan, pinch to zoom, tap a node for its details. " +
                            "Use the search field to select nodes without touching the picture.",
                        onTap = { id ->
                            hiddenFor = null
                            if (id != null) vm.selectNode(id) else if (g.selectedNodeId != null) vm.selectNode(null)
                        },
                        onDoubleTap = { id, at ->
                            if (id != null) {
                                focusOn(id, FOCUS_ZOOM)
                                vm.updateGraph { it.copy(options = it.options.copy(focusNodeId = id)) }
                            } else {
                                val t = camera.zoomTargetAround(at.x, at.y, camera.zoom * DOUBLE_TAP_ZOOM_STEP)
                                camera.go(scope) { camera.animateTo(t, motionMs) }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    GraphLegend(
                        kinds = kindsPresent, showSuppressedKey = g.showSuppressed && hasSuppressed && pathMode, open = legendOpen, onToggle = { legendOpen = !legendOpen },
                        modifier = Modifier.align(Alignment.BottomStart).padding(s.sm),
                    )
                }
            }
        }
    }

    val selectedId = g.selectedNodeId
    if (selectedId != null && selectedId != hiddenFor && lookup.exists(selectedId)) {
        GraphNodeSheet(
            lookup = lookup, nodeId = selectedId, inView = { scene.indexOf(it) >= 0 },
            onDismiss = { vm.selectNode(null) },
            onOpenNeighbour = { id -> vm.selectNode(id); focusOn(id, maxOf(camera.zoom, FIT_FOCUS_ZOOM)) },
            onLocal = {
                vm.updateGraph { it.copy(options = it.options.copy(local = true, focusNodeId = selectedId)) }
                hiddenFor = selectedId
                refit = true
            },
            onFocus = {
                vm.updateGraph { it.copy(options = it.options.copy(focusNodeId = selectedId)) }
                focusOn(selectedId, FOCUS_ZOOM)
                hiddenFor = selectedId
            },
        )
    }
}

private const val RESET_REHEAT = 0.6

/**
 * Runs the force layout for [scene] until the composition leaves it: a few steps per frame (off the main thread for big graphs),
 * sleeping while the layout is cooled and waking when a drag or a reset reheats it. With reduce motion it runs to completion at once
 * and again after every reheat, so nothing moves frame by frame.
 */
private suspend fun runLayout(scene: GraphScene, reduceMotion: Boolean, cache: MutableMap<String, Pair<Double, Double>>) {
    if (reduceMotion) {
        if (!scene.ready) {
            withContext(Dispatchers.Default) { scene.runToEnd() }
            scene.markReady()
        }
        while (true) {
            scene.wake.receive()
            if (scene.heavy) withContext(Dispatchers.Default) { scene.runToEnd() } else scene.runToEnd()
            scene.bump()
        }
    }
    while (true) {
        if (scene.layout.finished) {
            scene.savePositions(cache)
            scene.wake.receive()
        }
        withFrameNanos { }
        if (scene.heavy) withContext(Dispatchers.Default) { scene.step(HEAVY_STEPS) } else scene.step(LIGHT_STEPS)
        scene.bump()
    }
}
