package com.example.test_app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.example.test_app.admin.AdminSnapshot
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.OverviewStats
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.displayText
import com.example.test_app.domain.model.entry
import com.example.test_app.knowledgegraph.IncidentAnalysisExport
import com.example.test_app.knowledgegraph.model.ConsequenceItem
import com.example.test_app.knowledgegraph.model.Likelihood
import com.example.test_app.knowledgegraph.model.RecommendationLog
import com.example.test_app.knowledgegraph.model.Suppression
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.components.Thumbnail
import com.example.test_app.ui.theme.AppTheme

private const val LOW_CONFIDENCE = 0.5
private const val PERCENT = 100

/**
 * Full-tab detail of one report (ADR-058): header with the actions (show in graph, run analysis), every field with its source, confidence
 * and status, photos, the dialogue, why the severity is what it is, and the analysis (recommendations, consequences, arrangements, teams,
 * suppressed candidates) read from the audit log and the outbox export. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminReportDetail(report: IncidentReport, snapshot: AdminSnapshot, vm: AdminViewModel, modifier: Modifier = Modifier) {
    val id = report.reportId
    val audit = snapshot.auditFor(id)
    val export = snapshot.exportFor(id)
    LazyColumn(
        modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        contentPadding = PaddingValues(vertical = AppTheme.spacing.sm),
    ) {
        item(key = "header") { HeaderCard(report, audit != null, vm) }
        item(key = "fields") { FieldsCard(report) }
        item(key = "photos") { PhotosCard(report) }
        item(key = "dialogue") { DialogueCard(report, snapshot) }
        item(key = "severity") { SeverityCard(report) }
        if (audit == null && export == null) {
            item(key = "analysis-none") {
                SurfaceCard {
                    CardHeading("Analysis")
                    Text("This report has not been analysed yet: there is no audit log and no outbox export. Use Run analysis above.", style = AppTheme.type.body, color = AppTheme.colors.mutedText)
                }
            }
        } else {
            item(key = "analysis") { AnalysisSummaryCard(audit, export) }
            if (export != null) {
                item(key = "recs") { RecommendationsCard(export) }
                item(key = "consequences") { ConsequencesCard(export) }
                item(key = "arrangements") { ArrangementsAndTeamsCard(export) }
            }
            val suppressed = export?.suppressed ?: audit?.suppressed.orEmpty()
            item(key = "suppressed") { SuppressedCard(suppressed) }
        }
    }
}

/** A card with a tappable heading that shows or hides its content (state survives rotation). */
@Composable
fun AdminCollapsibleCard(title: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    SurfaceCard(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch).clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = AppTheme.type.title, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = AppTheme.colors.mutedText, modifier = Modifier.size(AppTheme.sizes.icon))
        }
        if (open) content()
    }
}

@Composable
private fun CardHeading(text: String) = Text(text, style = AppTheme.type.title, color = AppTheme.colors.onSurface)

@Composable
private fun Caption(text: String, color: androidx.compose.ui.graphics.Color = AppTheme.colors.mutedText) = Text(text, style = AppTheme.type.caption, color = color)

@Composable
private fun HeaderCard(report: IncidentReport, hasAudit: Boolean, vm: AdminViewModel) {
    val c = AppTheme.colors
    SurfaceCard {
        Text(report.reportId, style = AppTheme.type.headline, color = c.onSurface)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            AdminPill(report.status, adminStatusColor(if (report.status == IncidentReport.STATUS_COMPLETE || report.status == IncidentReport.STATUS_SUBMITTED) "OK" else "WARN"))
            AdminPill(OverviewStats.reportSeverity(report), adminSeverityColor(OverviewStats.reportSeverity(report)))
            AdminPill(OverviewStats.reportType(report), c.primary)
            if (com.example.test_app.admin.DemoData.isDemo(report)) AdminPill("DEMO", c.warning)
        }
        AdminKeyValue("Created", report.createdAt)
        AdminKeyValue("Input", "${report.input.mode}, language ${report.input.language}")
        AdminCollapsibleCardInline("Transcript") {
            Text(report.input.transcript.ifBlank { "(empty)" }, style = AppTheme.type.body, color = c.onSurface)
        }
        PrimaryButton("Show in graph", onClick = { vm.showIncidentInGraph(report.reportId) }, enabled = hasAudit)
        if (!hasAudit) Caption("The graph path needs an analysis audit log. Run the analysis first.")
        SecondaryButton(if (hasAudit) "Re-run analysis" else "Run analysis", onClick = { vm.analyzeReport(report.reportId) })
    }
}

/** Collapsible block without its own card, for use inside a card. */
@Composable
private fun AdminCollapsibleCardInline(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch).clickable(onClickLabel = if (open) "Hide $title" else "Show $title") { open = !open }.semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = AppTheme.type.label, color = AppTheme.colors.primary, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = AppTheme.colors.mutedText)
        }
        if (open) content()
    }
}

@Composable
private fun FieldsCard(report: IncidentReport) {
    val c = AppTheme.colors
    AdminCollapsibleCard("Fields", initiallyOpen = true) {
        IncidentField.entries.forEach { field ->
            val e = report.entry(field)
            val text = e.displayText(field)
            Column(Modifier.fillMaxWidth()) {
                Text(field.label, style = AppTheme.type.caption, color = c.mutedText)
                Text(text.ifBlank { "(empty)" }, style = AppTheme.type.body, color = if (text.isBlank()) c.mutedText else c.onSurface)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                    AdminPill(e.source, c.mutedText)
                    AdminPill("${(e.confidence * PERCENT).toInt()}%", if (e.confidence < LOW_CONFIDENCE) c.warning else c.success)
                    AdminPill(e.effectiveStatus, if (e.effectiveStatus in FieldStatus.NEEDS_ATTENTION) c.warning else adminStatusColor(null))
                }
            }
        }
    }
}

@Composable
private fun PhotosCard(report: IncidentReport) {
    AdminCollapsibleCard("Photos (${report.input.media.size})") {
        if (report.input.media.isEmpty()) {
            Caption("No photos attached.")
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                report.input.media.forEachIndexed { i, m -> Thumbnail(m, i) }
            }
        }
    }
}

@Composable
private fun DialogueCard(report: IncidentReport, snapshot: AdminSnapshot) {
    val c = AppTheme.colors
    val log = snapshot.dialogueFor(report.reportId)
    AdminCollapsibleCard("Dialogue") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            AdminPill("mode ${report.dialogue.mode}", c.primary)
            AdminPill(if (report.dialogue.emergency) "EMERGENCY" else "no emergency", if (report.dialogue.emergency) c.error else c.mutedText)
            AdminPill("rounds ${report.clarification.roundsUsed}/${report.clarification.maxRounds}", c.mutedText)
        }
        if (report.dialogue.askedFields.isNotEmpty()) {
            AdminKeyValue("Asked fields", report.dialogue.askedFields.joinToString(", ") { IncidentField.label(it) })
        }
        if (report.clarification.history.isEmpty()) Caption("No follow-up questions were asked.")
        report.clarification.history.forEachIndexed { i, t ->
            Column(Modifier.fillMaxWidth()) {
                Caption("Question ${i + 1}")
                Text(t.question, style = AppTheme.type.body, color = c.onSurface)
                Text("Answer: ${t.answer.ifBlank { "(none)" }}", style = AppTheme.type.body, color = c.primary)
                if (t.updatedFields.isNotEmpty()) Caption("Updated: " + t.updatedFields.joinToString(", ") { IncidentField.label(it) })
            }
        }
        if (log != null) {
            val lines = log.lines.filter { it.isNotBlank() }
            AdminCollapsibleCardInline("Dialogue log (${lines.size} lines)") { JsonBlock(lines.joinToString("\n")) }
        } else Caption("No dialogue log file for this report.")
    }
}

@Composable
private fun SeverityCard(report: IncidentReport) {
    val c = AppTheme.colors
    val sev = report.entry(IncidentField.SEVERITY_LEVEL)
    val hazard = report.entry(IncidentField.HAZARD_STILL_ACTIVE)
    val injury = report.entry(IncidentField.INJURY)
    AdminCollapsibleCard("Severity reasoning") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            val s = OverviewStats.reportSeverity(report)
            AdminPill(s, adminSeverityColor(s))
            AdminPill("source ${sev.source}", c.mutedText)
            AdminPill("confidence ${(sev.confidence * PERCENT).toInt()}%", if (sev.confidence < LOW_CONFIDENCE) c.warning else c.success)
        }
        AdminKeyValue("Dialogue mode", report.dialogue.mode + if (report.dialogue.emergency) " (emergency flag set)" else "")
        AdminKeyValue("Hazard still active", hazard.displayText(IncidentField.HAZARD_STILL_ACTIVE).ifBlank { "unknown" })
        AdminKeyValue("Injury", injury.displayText(IncidentField.INJURY).ifBlank { "unknown" })
        AdminKeyValue("Missing fields", report.validation.missingFields.joinToString(", ") { IncidentField.label(it) }.ifBlank { "none" })
        AdminKeyValue("Low-confidence fields", report.validation.lowConfidenceFields.joinToString(", ") { IncidentField.label(it) }.ifBlank { "none" })
    }
}

@Composable
private fun AnalysisSummaryCard(audit: RecommendationLog?, export: IncidentAnalysisExport?) {
    val c = AppTheme.colors
    val abstained = export?.abstained ?: audit?.abstained ?: false
    SurfaceCard {
        CardHeading("Analysis")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            (export?.graphVersion ?: audit?.graphVersion)?.let { AdminPill("graph v$it", c.primary) }
            (export?.audience ?: audit?.audience)?.let { AdminPill(it.name, c.mutedText) }
            if (abstained) AdminPill("ABSTAINED", c.warning)
            if (audit?.userConfirmed == true) AdminPill("confirmed by reporter", c.success)
        }
        audit?.let {
            AdminKeyValue("Audit log", "${it.id} at ${it.createdAt}")
            val f = it.inputFacts
            AdminKeyValue("Facts used", "type ${f.type ?: "?"}, severity ${f.severity ?: "?"}, status ${f.status}, place type ${f.locationType ?: "?"}, evidence ${(f.evidenceConfidence * PERCENT).toInt()}%")
        }
        export?.guidanceLabel?.let { Text(it, style = AppTheme.type.label, color = c.warning) }
        export?.medicalFooter?.let { AdminKeyValue("Medical footer", it) }
        val warnings = export?.warnings ?: audit?.warnings.orEmpty()
        warnings.forEach { Text("Warning: $it", style = AppTheme.type.caption, color = c.warning) }
        val questions = export?.unresolvedQuestions ?: audit?.unresolvedQuestions.orEmpty()
        if (questions.isEmpty()) Caption("No unresolved question.") else questions.forEach { AdminKeyValue("Unresolved question", it.text) }
    }
}

@Composable
private fun RecommendationsCard(export: IncidentAnalysisExport) {
    val c = AppTheme.colors
    AdminCollapsibleCard("Recommendations (${export.recommendations.size})", initiallyOpen = true) {
        if (export.recommendations.isEmpty()) Caption("The engine produced no recommendation.")
        export.recommendations.forEach { r ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                    AdminPill("#${r.rank} ${r.phase.name}", c.primary)
                    AdminPill("score ${"%.2f".format(r.score)}", c.mutedText)
                    AdminPill(r.renderMode.name, c.mutedText)
                    if (r.unverifiedDraft) AdminPill("UNVERIFIED DRAFT", c.warning)
                    if (r.requiresHumanApproval) AdminPill("NEEDS APPROVAL", c.warning)
                }
                Text(r.renderedText, style = AppTheme.type.body, color = c.onSurface)
                Caption("Owner: ${r.ownerTeamName ?: r.ownerTeamId ?: "none"}")
                Caption("Source: ${r.sourceId}" + (r.sourceLabel?.let { " ($it)" } ?: ""))
                if (r.historyPatternIds.isNotEmpty()) Caption("History: ${r.historyPatternIds.joinToString(", ")}")
                r.provenance.forEach { Text(it, style = AppTheme.type.code, color = c.mutedText) }
            }
        }
    }
}

@Composable
private fun ConsequencesCard(export: IncidentAnalysisExport) {
    AdminCollapsibleCard("Consequences (${export.consequences.size})") {
        if (export.consequences.isEmpty()) Caption("None.")
        export.consequences.forEach { ConsequenceRow(it) }
    }
}

@Composable
private fun ConsequenceRow(item: ConsequenceItem) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        AdminPill(item.likelihood.name, when (item.likelihood) { Likelihood.CONFIRMED -> c.error; Likelihood.LIKELY -> c.warning; Likelihood.POSSIBLE -> c.mutedText })
        Text(item.text.ifBlank { item.name }, style = AppTheme.type.body, color = c.onSurface)
        Caption("${item.name} (${item.consequenceType})")
    }
}

@Composable
private fun ArrangementsAndTeamsCard(export: IncidentAnalysisExport) {
    val c = AppTheme.colors
    AdminCollapsibleCard("Arrangements and teams") {
        CardHeading("Temporary arrangements (${export.temporaryArrangements.size})")
        if (export.temporaryArrangements.isEmpty()) Caption("None.")
        export.temporaryArrangements.forEach {
            Column(Modifier.fillMaxWidth()) {
                Text(it.title, style = AppTheme.type.label, color = c.onSurface)
                Text(it.text, style = AppTheme.type.body, color = c.onSurface)
                if (it.unverifiedDraft) AdminPill("UNVERIFIED DRAFT", c.warning)
            }
        }
        CardHeading("Responsible teams (${export.responsibleTeams.size})")
        if (export.responsibleTeams.isEmpty()) Caption("None.")
        export.responsibleTeams.forEach {
            Column(Modifier.fillMaxWidth()) {
                Text("${it.name} (${it.role})", style = AppTheme.type.body, color = c.onSurface)
                Caption(if (it.verified) "verified" + (it.contactNumber?.let { n -> ", $n" } ?: "") else "not verified", if (it.verified) c.success else c.warning)
            }
        }
    }
}

@Composable
private fun SuppressedCard(suppressed: List<Suppression>) {
    val c = AppTheme.colors
    AdminCollapsibleCard("Suppressed (${suppressed.size})") {
        if (suppressed.isEmpty()) Caption("Nothing was suppressed.")
        suppressed.forEach {
            Column(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    AdminPill(it.kind, c.warning)
                    Text(it.candidateId, style = AppTheme.type.code, color = c.onSurface, modifier = Modifier.weight(1f))
                }
                Text(it.reason, style = AppTheme.type.caption, color = c.warning, modifier = Modifier.padding(top = AppTheme.spacing.xs))
            }
        }
    }
}
