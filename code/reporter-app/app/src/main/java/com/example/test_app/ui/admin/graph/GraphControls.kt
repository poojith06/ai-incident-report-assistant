package com.example.test_app.ui.admin.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.example.test_app.knowledgegraph.admin.graphview.GraphKinds
import com.example.test_app.knowledgegraph.admin.graphview.GraphViewOptions
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.ui.admin.AdminSearchField
import com.example.test_app.ui.components.LinkButton
import com.example.test_app.ui.theme.AppTheme
import kotlin.math.roundToInt

/*
 * The controls around the graph canvas: search row, matches, highlight banner, the collapsible Filters panel, the count line and the
 * legend. All of them are stateless; AdminGraphTab owns the state and talks to the view model. See doc/ADMIN_DASHBOARD.md.
 */

/** Search field with three icon buttons: filters panel, fit to screen (reset view) and re-layout. */
@Composable
internal fun GraphSearchRow(
    query: String,
    onQuery: (String) -> Unit,
    filtersOpen: Boolean,
    activeFilters: Int,
    onToggleFilters: () -> Unit,
    onResetView: () -> Unit,
    onRelayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AdminSearchField(query, onQuery, "Search nodes by name, code or id", Modifier.weight(1f))
        IconButton(onClick = onToggleFilters, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = (if (filtersOpen) "Hide graph filters" else "Show graph filters") + (if (activeFilters > 0) ", $activeFilters active" else ""),
                tint = if (filtersOpen || activeFilters > 0) c.primary else c.mutedText,
            )
        }
        IconButton(onClick = onResetView, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
            Icon(Icons.Filled.FitScreen, contentDescription = "Reset view: fit the whole graph and clear pinned nodes", tint = c.mutedText)
        }
        IconButton(onClick = onRelayout, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
            Icon(Icons.Filled.Shuffle, contentDescription = "Re-layout the graph from scratch", tint = c.mutedText)
        }
    }
}

/** A search match shown as a chip: the accessible way to pick a node without touching the canvas. */
internal class MatchChip(val id: String, val label: String, val kind: String)

/** Sideways scrolling chips of the nodes the search matched (the first few); tapping one selects the node. */
@Composable
internal fun GraphMatchRow(matches: List<MatchChip>, total: Int, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        matches.forEach { m ->
            FilterChip(
                selected = false, onClick = { onSelect(m.id) },
                label = { Text(m.label, style = AppTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Dot(kindColor(c.graphPalette, m.kind)) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch).semantics { contentDescription = "Select ${m.label}, ${humanize(m.kind)}" },
                colors = chipColors(),
            )
        }
        if (total > matches.size) Text("+${total - matches.size} more", style = AppTheme.type.caption, color = c.mutedText)
    }
}

/** Banner shown while an incident path or a preview is highlighted. */
@Composable
internal fun GraphHighlightBanner(label: String, nodes: Int, edges: Int, suppressed: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(AppTheme.radii.md)).background(c.primaryContainer).padding(start = s.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = s.xs)) {
            Text("$label: highlighted path", style = AppTheme.type.label, color = c.onPrimaryContainer)
            Text(
                "$nodes nodes, $edges links" + if (suppressed > 0) ", $suppressed suppressed candidates" else "",
                style = AppTheme.type.caption, color = c.onPrimaryContainer,
            )
        }
        LinkButton("Clear", onClear)
    }
}

/** "N nodes, M links" plus the truncation note and the number of search matches. */
@Composable
internal fun GraphCountLine(nodes: Int, edges: Int, truncated: Boolean, cap: Int, total: Int, matches: Int?, modifier: Modifier = Modifier) {
    val text = buildString {
        append("$nodes nodes, $edges links")
        if (truncated) append(" (showing top $cap of $total)")
        if (matches != null) append(" \u2022 $matches match${if (matches == 1) "" else "es"}")
    }
    Text(text, style = AppTheme.type.caption, color = AppTheme.colors.mutedText, modifier = modifier.fillMaxWidth())
}

/**
 * The Filters panel: node kinds, relations, switches, Global or Local graph with its depth, and Reset. Every change is reported
 * through [onOptions] (a transform of the options), [onShowSuppressed], [onLocal] or [onReset].
 */
@Composable
internal fun GraphFiltersPanel(
    options: GraphViewOptions,
    showSuppressed: Boolean,
    canGoLocal: Boolean,
    focusLabel: String?,
    onOptions: ((GraphViewOptions) -> GraphViewOptions) -> Unit,
    onShowSuppressed: (Boolean) -> Unit,
    onLocal: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AppTheme.radii.md)).background(c.surface).padding(horizontal = s.sm, vertical = s.sm)) {
        Text("Node kinds", style = AppTheme.type.label, color = c.primary)
        ToggleChipRow(
            GraphKinds.ALL, isOn = { it !in options.hiddenKinds }, label = { humanize(it) }, color = { kindColor(c.graphPalette, it) },
            onToggle = { k -> onOptions { o -> o.copy(hiddenKinds = if (k in o.hiddenKinds) o.hiddenKinds - k else o.hiddenKinds + k) } },
        )
        Text("Relations", style = AppTheme.type.label, color = c.primary, modifier = Modifier.padding(top = s.sm))
        ToggleChipRow(
            Relation.entries, isOn = { it !in options.hiddenRelations }, label = { humanize(it.name) }, color = { relationColor(c.graphPalette, it) },
            onToggle = { r -> onOptions { o -> o.copy(hiddenRelations = if (r in o.hiddenRelations) o.hiddenRelations - r else o.hiddenRelations + r) } },
        )
        SwitchRow("Only approved and active", options.onlyApprovedActive) { v -> onOptions { it.copy(onlyApprovedActive = v) } }
        SwitchRow("Show orphan nodes", options.showOrphans) { v -> onOptions { it.copy(showOrphans = v) } }
        SwitchRow("Show suppressed candidates", showSuppressed, onShowSuppressed)

        Text("Scope", style = AppTheme.type.label, color = c.primary, modifier = Modifier.padding(top = s.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(s.xs), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !options.local, onClick = { onLocal(false) }, label = { Text("Global", style = AppTheme.type.label) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch), colors = chipColors(),
            )
            FilterChip(
                selected = options.local, onClick = { onLocal(true) }, enabled = canGoLocal || options.local, label = { Text("Local", style = AppTheme.type.label) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch), colors = chipColors(),
            )
        }
        Text(
            when {
                focusLabel != null -> "Around: $focusLabel"
                else -> "Select a node to see its local graph."
            },
            style = AppTheme.type.caption, color = c.mutedText,
        )
        Row(Modifier.fillMaxWidth().alpha(if (options.local) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
            Text("Depth ${options.localDepth}", style = AppTheme.type.label, color = c.onSurface, modifier = Modifier.padding(end = s.sm))
            Slider(
                value = options.localDepth.toFloat().coerceIn(1f, 3f), onValueChange = { v -> onOptions { it.copy(localDepth = v.roundToInt().coerceIn(1, 3)) } },
                valueRange = 1f..3f, steps = 1, enabled = options.local,
                modifier = Modifier.weight(1f).heightIn(min = AppTheme.sizes.minTouch).semantics { contentDescription = "Local graph depth, ${options.localDepth}" },
                colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.surfaceElevated),
            )
        }
        LinkButton("Reset filters", onReset)
    }
}

/** A row of multi-select chips (on = visible) that scrolls sideways; each chip carries the colour of the thing it filters. */
@Composable
private fun <T> ToggleChipRow(items: List<T>, isOn: (T) -> Boolean, label: (T) -> String, color: (T) -> Color, onToggle: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        items.forEach { item ->
            val on = isOn(item)
            val text = label(item)
            FilterChip(
                selected = on, onClick = { onToggle(item) },
                label = { Text(text, style = AppTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Dot(color(item)) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch).semantics { stateDescription = if (on) "Shown" else "Hidden"; contentDescription = text },
                colors = chipColors(),
            )
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = AppTheme.colors.primaryContainer, selectedLabelColor = AppTheme.colors.onPrimaryContainer,
    containerColor = AppTheme.colors.surface, labelColor = AppTheme.colors.onSurface,
)

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppTheme.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary, uncheckedTrackColor = c.surfaceElevated, uncheckedThumbColor = c.mutedText),
        )
    }
}

/** A small filled circle in a legend or chip colour. */
@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(AppTheme.sizes.statusDot).clip(CircleShape).background(color))
}

/**
 * Collapsible legend: one coloured dot per node kind present in the visible graph (the canvas uses the same colours), plus the
 * warning colour when suppressed candidates are shown. Meant to be overlaid at the bottom of the canvas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GraphLegend(kinds: List<String>, showSuppressedKey: Boolean, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    Column(modifier.clip(RoundedCornerShape(AppTheme.radii.md)).background(c.surface.copy(alpha = LEGEND_ALPHA)).padding(horizontal = s.sm)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
                .semantics { contentDescription = if (open) "Hide the legend" else "Show the legend" }
                .clickable(role = Role.Button, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Legend", style = AppTheme.type.label, color = c.mutedText, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null, tint = c.mutedText)
        }
        if (open) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(s.md), verticalArrangement = Arrangement.spacedBy(s.xs), modifier = Modifier.padding(bottom = s.sm)) {
                kinds.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.xs)) {
                        Dot(kindColor(c.graphPalette, k))
                        Text(humanize(k), style = AppTheme.type.caption, color = c.onSurface)
                    }
                }
                if (showSuppressedKey) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.xs)) {
                        Dot(c.warning)
                        Text("Suppressed candidate", style = AppTheme.type.caption, color = c.onSurface)
                    }
                }
            }
        }
    }
}

private const val LEGEND_ALPHA = 0.92f
