package com.example.test_app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.test_app.admin.EditorForms
import com.example.test_app.admin.EdgeDraft
import com.example.test_app.admin.FormResult
import com.example.test_app.admin.NodeDraft
import com.example.test_app.admin.SourceDraft
import com.example.test_app.knowledgegraph.admin.ValidationReport
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/** Forms of the Graph data tab (node, edge, source, approval) and their small building blocks. See `doc/ADMIN_DASHBOARD.md`. */

/** Text shown once on top of every edit form. */
internal const val EDIT_VERSION_NOTE = "Edits create a new graph version and can be rolled back in Versions."

/** Production rule of recommendations, shown on the approve form. */
internal const val APPROVAL_RULE = "In production a recommendation needs a source, a reviewer and an approval time; the source must be approved."

/** Single-select chip row without an "all" entry; scrolls sideways. */
@Composable
internal fun <T> AdminSegmentRow(options: List<T>, selected: T?, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        options.forEach { o ->
            FilterChip(
                selected = selected == o, onClick = { onSelect(o) }, label = { Text(label(o), style = AppTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = c.primaryContainer, selectedLabelColor = c.onPrimaryContainer, containerColor = c.surface, labelColor = c.onSurface,
                ),
            )
        }
    }
}

/** Multi-select chip row (toggle each chip); scrolls sideways. */
@Composable
internal fun AdminMultiChipRow(options: List<String>, selected: List<String>, modifier: Modifier = Modifier, onChange: (List<String>) -> Unit) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        options.forEach { o ->
            val on = o in selected
            FilterChip(
                selected = on, onClick = { onChange(if (on) selected - o else selected + o) }, label = { Text(o, style = AppTheme.type.label, maxLines = 1) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = c.primaryContainer, selectedLabelColor = c.onPrimaryContainer, containerColor = c.surface, labelColor = c.onSurface,
                ),
            )
        }
    }
}

/** A labelled chip row used as a dropdown for enum values, with the field error below. */
@Composable
internal fun FieldChoice(label: String, options: List<String>, value: String, error: String?, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        Text(label, style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminSegmentRow(options, value, { it }, onSelect = onChange)
        error?.let { Text(it, style = AppTheme.type.caption, color = AppTheme.colors.error) }
    }
}

/** A text field with the label and a red supporting error text; [mono] uses the code style, [lines] is the minimum line count. */
@Composable
internal fun FormField(label: String, value: String, error: String?, onChange: (String) -> Unit, mono: Boolean = false, lines: Int = 1, number: Boolean = false, enabled: Boolean = true) {
    val c = AppTheme.colors
    val style: TextStyle = (if (mono) AppTheme.type.code else AppTheme.type.body).copy(color = c.onSurface)
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, isError = error != null, enabled = enabled,
        supportingText = error?.let { e -> { Text(e, color = c.error) } }, textStyle = style,
        singleLine = lines == 1, minLines = lines, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.border, cursorColor = c.primary, errorBorderColor = c.error),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        Text(label, style = AppTheme.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Result card of the graph validator: errors, warnings, or "Valid: no errors". */
@Composable
internal fun ValidationCard(report: ValidationReport, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    SurfaceCard(modifier, borderColor = if (report.ok) c.success else c.error) {
        if (report.ok) Text("Valid: no errors", style = AppTheme.type.label, color = c.success)
        else Text("${report.errors.size} error(s)", style = AppTheme.type.label, color = c.error)
        report.errors.forEach { Text(it, style = AppTheme.type.caption, color = c.error) }
        if (report.warnings.isNotEmpty()) Text("${report.warnings.size} warning(s)", style = AppTheme.type.label, color = c.warning)
        report.warnings.forEach { Text(it, style = AppTheme.type.caption, color = c.warning) }
    }
}

/** Full-screen dialog with a title, a close button and a scrolling body (keyboard aware). */
@Composable
internal fun AdminFormDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.surfaceElevated).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = s.md, end = s.xs, top = s.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = AppTheme.type.title, color = c.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close the form", tint = c.onSurface)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = s.md, vertical = s.sm), verticalArrangement = Arrangement.spacedBy(s.sm)) {
                    Text(EDIT_VERSION_NOTE, style = AppTheme.type.caption, color = c.mutedText)
                    content()
                }
            }
        }
    }
}

/** Shows the validation card under a form after a save was attempted (the service rejected the change). */
@Composable
internal fun RejectionCard(submitted: Boolean, validation: ValidationReport?) {
    if (submitted && validation != null && !validation.ok) ValidationCard(validation)
}

/**
 * The node form. Save validates with [EditorForms.node] and calls [onSave] on success; field errors show under the fields. [typeLocked] pins
 * the node type (for recommendations, teams and contacts).
 */
@Composable
internal fun NodeForm(initial: NodeDraft, editing: Boolean, pack: GraphPack, validation: ValidationReport?, onSave: (KgNode) -> Unit, typeLocked: Boolean = false) {
    var d by remember(initial) { mutableStateOf(initial) }
    var errors by remember(initial) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var submitted by remember(initial) { mutableStateOf(false) }
    FormField("Id", d.id, errors["id"], { d = d.copy(id = it) }, mono = true, enabled = !editing)
    FormField("Name", d.name, errors["name"], { d = d.copy(name = it) })
    if (!typeLocked) FieldChoice("Type", NodeType.entries.map { it.name }, d.type, errors["type"]) { d = d.copy(type = it) }
    else Text("Type: ${d.type}", style = AppTheme.type.label, color = AppTheme.colors.mutedText)
    FieldChoice("Verification", VerificationStatus.entries.map { it.name }, d.verification, errors["verification"]) { d = d.copy(verification = it) }
    FormField("Properties (JSON object)", d.propertiesJson, errors["properties"], { d = d.copy(propertiesJson = it) }, mono = true, lines = 5)
    SwitchRow("Active", d.active) { d = d.copy(active = it) }
    RejectionCard(submitted, validation)
    PrimaryButton("Save", {
        when (val r = EditorForms.node(d, pack, editing)) {
            is FormResult.Invalid -> errors = r.errors
            is FormResult.Ok -> { errors = emptyMap(); submitted = true; onSave(r.value) }
        }
    }, enabled = d.id.isNotBlank() && d.name.isNotBlank())
}

/** The edge form (see [NodeForm]). */
@Composable
internal fun EdgeForm(initial: EdgeDraft, editing: Boolean, pack: GraphPack, validation: ValidationReport?, onSave: (KgEdge) -> Unit) {
    var d by remember(initial) { mutableStateOf(initial) }
    var errors by remember(initial) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var submitted by remember(initial) { mutableStateOf(false) }
    FormField("Id", d.id, errors["id"], { d = d.copy(id = it) }, mono = true, enabled = !editing)
    FormField("Source node id", d.sourceId, errors["sourceId"], { d = d.copy(sourceId = it) }, mono = true)
    FieldChoice("Relation", Relation.entries.map { it.name }, d.relation, errors["relation"]) { d = d.copy(relation = it) }
    FormField(if (d.relation == Relation.SUPPORTED_BY.name) "Target source id" else "Target node id", d.targetId, errors["targetId"], { d = d.copy(targetId = it) }, mono = true)
    FormField("Priority (0 to 100)", d.priority, errors["priority"], { d = d.copy(priority = it) }, number = true)
    FormField("Conditions (JSON object)", d.conditionsJson, errors["conditions"], { d = d.copy(conditionsJson = it) }, mono = true, lines = 5)
    FormField("Source id (optional)", d.sourceRef, errors["sourceRef"], { d = d.copy(sourceRef = it) }, mono = true)
    FieldChoice("Approval", ApprovalStatus.entries.map { it.name }, d.approval, errors["approval"]) { d = d.copy(approval = it) }
    SwitchRow("Active", d.active) { d = d.copy(active = it) }
    RejectionCard(submitted, validation)
    PrimaryButton("Save", {
        when (val r = EditorForms.edge(d, pack, editing)) {
            is FormResult.Invalid -> errors = r.errors
            is FormResult.Ok -> { errors = emptyMap(); submitted = true; onSave(r.value) }
        }
    }, enabled = d.id.isNotBlank() && d.sourceId.isNotBlank() && d.targetId.isNotBlank())
}

/** The source form; [onSave] gets the validated source. */
@Composable
internal fun SourceForm(initial: SourceDraft, editing: Boolean, validation: ValidationReport?, saveLabel: String = "Save", onSave: (KgSource) -> Unit) {
    var d by remember(initial) { mutableStateOf(initial) }
    var errors by remember(initial) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var submitted by remember(initial) { mutableStateOf(false) }
    FormField("Id", d.id, errors["id"], { d = d.copy(id = it) }, mono = true, enabled = !editing)
    FormField("Title", d.title, errors["title"], { d = d.copy(title = it) })
    FormField("Version", d.version, errors["version"], { d = d.copy(version = it) })
    FormField("Link (optional)", d.uri, errors["uri"], { d = d.copy(uri = it) })
    FieldChoice("Status", SourceStatus.entries.map { it.name }, d.status, errors["status"]) { d = d.copy(status = it) }
    FormField("Approved by", d.approvedBy, errors["approvedBy"], { d = d.copy(approvedBy = it) })
    FormField("Approved at (ISO date or time)", d.approvedAt, errors["approvedAt"], { d = d.copy(approvedAt = it) })
    FormField("Expires at (ISO date, optional)", d.expiresAt, errors["expiresAt"], { d = d.copy(expiresAt = it) })
    FormField("Label shown with guidance (optional)", d.displayLabel, errors["displayLabel"], { d = d.copy(displayLabel = it) })
    RejectionCard(submitted, validation)
    PrimaryButton(saveLabel, {
        when (val r = EditorForms.source(d)) {
            is FormResult.Invalid -> errors = r.errors
            is FormResult.Ok -> { errors = emptyMap(); submitted = true; onSave(r.value) }
        }
    }, enabled = d.id.isNotBlank() && d.title.isNotBlank())
}

/** The approve form of a recommendation: reviewer and optional expiry, checked with [EditorForms.approval]. */
@Composable
internal fun ApprovalForm(nowIso: String, validation: ValidationReport?, onApprove: (reviewer: String, expiresAt: String?) -> Unit) {
    var reviewer by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var submitted by remember { mutableStateOf(false) }
    Text(APPROVAL_RULE, style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
    FormField("Reviewer name", reviewer, errors["reviewer"], { reviewer = it })
    FormField("Expires at (ISO date, optional)", expiry, errors["expiresAt"], { expiry = it })
    RejectionCard(submitted, validation)
    PrimaryButton("Approve", {
        when (val r = EditorForms.approval(reviewer, expiry, nowIso)) {
            is FormResult.Invalid -> errors = r.errors
            is FormResult.Ok -> { errors = emptyMap(); submitted = true; onApprove(r.value.first, r.value.second) }
        }
    }, enabled = reviewer.isNotBlank())
}

/** "Verified by" name field and button, used for teams and location nodes. */
@Composable
internal fun VerifyForm(buttonLabel: String, onVerify: (by: String) -> Unit) {
    var by by remember { mutableStateOf("") }
    FormField("Verified by (name)", by, null, { by = it })
    PrimaryButton(buttonLabel, { onVerify(by.trim()) }, enabled = by.isNotBlank())
}
