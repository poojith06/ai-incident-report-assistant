package com.example.test_app.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.test_app.flow.AnalysisUiState
import com.example.test_app.knowledgegraph.model.RecommendationItem
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/**
 * "Post-incident analysis" card on the Success screen (ADR-055): the offline knowledge graph's output, compact and collapsible. Shows,
 * per phase, only graph-approved guidance (immediate actions, possible consequences tagged POSSIBLE / LIKELY / CONFIRMED, operational
 * steps, recovery, temporary arrangements, prevention, responsible teams as role labels, at most one question), the "Demo guidance" label
 * while the guidance relies on the demo seed source, the medical footer for injury cases, and a plain "not enough information" state when
 * the engine abstained. Used by: SuccessScreen. See `doc/UI_FLOW.md`.
 */
@Composable
fun AnalysisCard(state: AnalysisUiState, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    if (state is AnalysisUiState.None) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    SurfaceCard(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
                .semantics { contentDescription = "Post-incident analysis, ${if (expanded) "expanded" else "collapsed"}" }
                .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Post-incident analysis", style = AppTheme.type.title, color = c.onSurface, modifier = Modifier.weight(1f))
            if (state is AnalysisUiState.Running) CircularProgressIndicator(Modifier.size(AppTheme.sizes.icon), color = c.primary, strokeWidth = AppTheme.sizes.borderWidthStrong)
            else Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = c.mutedText)
        }
        when (state) {
            AnalysisUiState.None -> Unit
            AnalysisUiState.Running -> Text("Looking at what usually follows this kind of incident\u2026", style = AppTheme.type.caption, color = c.mutedText)
            is AnalysisUiState.Failed -> Text("The analysis is not available right now. Your report was submitted.", style = AppTheme.type.caption, color = c.mutedText)
            is AnalysisUiState.Done -> {
                val set = state.set
                Text(summary(set), style = AppTheme.type.caption, color = c.mutedText)
                set.guidanceLabel?.let { Text(it, style = AppTheme.type.label, color = c.warning) }
                if (expanded) Body(set)
            }
        }
    }
}

private fun summary(set: RecommendationSet): String = when {
    set.abstained -> "Not enough information for guidance."
    set.isEmpty -> "No approved guidance for this incident yet."
    else -> {
        val n = set.allRecommendations.size
        "$n suggested step${if (n == 1) "" else "s"}" + if (set.consequences.isNotEmpty()) ", ${set.consequences.size} possible consequence${if (set.consequences.size == 1) "" else "s"}" else ""
    }
}

@Composable
private fun Body(set: RecommendationSet) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    Column(Modifier.padding(top = s.sm), verticalArrangement = Arrangement.spacedBy(s.md)) {
        if (set.abstained) Text("Not enough information to suggest anything yet.", style = AppTheme.type.body, color = c.onSurface)
        Section("Do now", set.immediateActions)
        if (set.consequences.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(s.xs)) {
                Text("What could follow", style = AppTheme.type.label, color = c.primary)
                set.consequences.forEach {
                    Text("${it.likelihood.name} \u2022 ${it.text.ifBlank { it.name }}", style = AppTheme.type.body, color = c.onSurface)
                }
            }
        }
        Section("Next steps", set.operationalSteps)
        Section("Recovery", set.recoverySteps)
        if (set.temporaryArrangements.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(s.xs)) {
                Text("Temporary arrangements", style = AppTheme.type.label, color = c.primary)
                set.temporaryArrangements.forEach { Text(it.text.ifBlank { it.title }, style = AppTheme.type.body, color = c.onSurface) }
            }
        }
        Section("Prevent it happening again", set.preventiveActions)
        if (set.responsibleTeams.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(s.xs)) {
                Text("Responsible roles", style = AppTheme.type.label, color = c.primary)
                Text(set.responsibleTeams.joinToString(", ") { it.name }, style = AppTheme.type.body, color = c.onSurface)
                Text("Role labels only; contacts are shown only after an administrator verifies them.", style = AppTheme.type.caption, color = c.mutedText)
            }
        }
        set.unresolvedQuestions.firstOrNull()?.let {
            Column(verticalArrangement = Arrangement.spacedBy(s.xs)) {
                Text("One thing would help", style = AppTheme.type.label, color = c.primary)
                Text(it.text, style = AppTheme.type.body, color = c.onSurface)
            }
        }
        set.medicalFooter?.let { Text(it, style = AppTheme.type.caption, color = c.mutedText) }
    }
}

@Composable
private fun Section(title: String, items: List<RecommendationItem>) {
    if (items.isEmpty()) return
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        Text(title, style = AppTheme.type.label, color = c.primary)
        items.forEach { r ->
            Column {
                Text("${r.rank}. ${r.renderedText}", style = AppTheme.type.body, color = c.onSurface)
                r.ownerTeamName?.let { Text("Owner: $it", style = AppTheme.type.caption, color = c.mutedText) }
                if (r.history.isNotEmpty()) {
                    val h = r.history.first()
                    Text("Seen before: ${h.count} similar incidents in ${h.windowDays} days (${h.incidentIds.joinToString(", ")})", style = AppTheme.type.caption, color = c.mutedText)
                }
            }
        }
    }
}
