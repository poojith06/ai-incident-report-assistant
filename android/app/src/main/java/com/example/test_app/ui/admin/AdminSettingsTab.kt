package com.example.test_app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import com.example.test_app.admin.AdminPin
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.AnalysisSettings
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/** One editable number of [AnalysisSettings]: [int] fields parse as Int, the others as Double. */
private class NumberSpec(val key: String, val label: String, val int: Boolean, val read: (AnalysisSettings) -> Number)

private val SPECS = listOf(
    NumberSpec("abstain", "Abstain below evidence (0 to 1)", false) { it.abstainBelowEvidence },
    NumberSpec("immediate", "Limit: immediate actions (0 to 10)", true) { it.immediate },
    NumberSpec("operational", "Limit: operational steps (0 to 10)", true) { it.operational },
    NumberSpec("recovery", "Limit: recovery steps (0 to 10)", true) { it.recovery },
    NumberSpec("temporary", "Limit: temporary arrangements (0 to 10)", true) { it.temporaryArrangements },
    NumberSpec("preventive", "Limit: preventive actions (0 to 10)", true) { it.preventive },
    NumberSpec("consequences", "Limit: consequences (0 to 10)", true) { it.maxConsequences },
    NumberSpec("wGraph", "Weight: graph priority (0 to 1)", false) { it.wGraphPriority },
    NumberSpec("wSeverity", "Weight: severity relevance (0 to 1)", false) { it.wSeverity },
    NumberSpec("wLocation", "Weight: location match (0 to 1)", false) { it.wLocation },
    NumberSpec("wAsset", "Weight: asset match (0 to 1)", false) { it.wAsset },
    NumberSpec("wRecurrence", "Weight: recurrence (0 to 1)", false) { it.wRecurrence },
    NumberSpec("wEvidence", "Weight: evidence completeness (0 to 1)", false) { it.wEvidence },
)

/** The settings in [buffer], or the messages why the text cannot be turned into settings (not numbers, or out of range). */
private fun parse(buffer: Map<String, String>): Pair<AnalysisSettings?, List<String>> {
    val bad = SPECS.filter { s ->
        val t = buffer[s.key].orEmpty().trim()
        (if (s.int) t.toIntOrNull() else t.toDoubleOrNull()) == null
    }
    if (bad.isNotEmpty()) return null to bad.map { "${it.label.substringBefore(" (")} is not a valid ${if (it.int) "whole number" else "number"}." }
    fun d(k: String) = buffer.getValue(k).trim().toDouble()
    fun i(k: String) = buffer.getValue(k).trim().toInt()
    val s = AnalysisSettings(
        abstainBelowEvidence = d("abstain"), maxConsequences = i("consequences"), immediate = i("immediate"), operational = i("operational"),
        recovery = i("recovery"), temporaryArrangements = i("temporary"), preventive = i("preventive"),
        wGraphPriority = d("wGraph"), wSeverity = d("wSeverity"), wLocation = d("wLocation"), wAsset = d("wAsset"), wRecurrence = d("wRecurrence"), wEvidence = d("wEvidence"),
    )
    return s to s.problems()
}

/**
 * Settings tab (ADR-058): admin PIN change, analysis settings (abstention threshold, output limits, ranking weights), the delete-with-reports
 * switch, demo data generation and removal, and developer info. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminSettingsTab(state: AdminUiState, vm: AdminViewModel) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), contentPadding = PaddingValues(vertical = AppTheme.spacing.sm)) {
        item { PinCard(vm) }
        item { AnalysisCard(state.analysisSettings, vm) }
        item { DeleteCard(state.alsoDeleteKgFiles, vm) }
        item { DemoCard(state.busy != null, vm) }
        item { DevInfoCard(state) }
    }
}

@Composable
private fun CardTitle(text: String) = Text(text, style = AppTheme.type.title, color = AppTheme.colors.onSurface)

@Composable
private fun SettingsField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false,
) {
    val c = AppTheme.colors
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = AppTheme.type.caption) }, textStyle = AppTheme.type.body.copy(color = c.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.primary, unfocusedBorderColor = c.border, cursorColor = c.primary,
            focusedLabelColor = c.primary, unfocusedLabelColor = c.mutedText,
        ),
    )
}

@Composable
private fun PinCard(vm: AdminViewModel) {
    val c = AppTheme.colors
    var current by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    fun digits(s: String) = s.filter { it in '0'..'9' }.take(AdminPin.LENGTH)
    SurfaceCard {
        CardTitle("Admin PIN")
        Text(
            "This PIN is a demo gate to keep casual users out. It is not real security: it is stored unencrypted on this device.",
            style = AppTheme.type.caption, color = c.warning,
        )
        SettingsField(current, { current = digits(it); message = null }, "Current PIN", keyboard = KeyboardType.NumberPassword, password = true)
        SettingsField(next, { next = digits(it); message = null }, "New PIN (${AdminPin.LENGTH} digits)", keyboard = KeyboardType.NumberPassword, password = true)
        SettingsField(confirm, { confirm = digits(it); message = null }, "Repeat the new PIN", keyboard = KeyboardType.NumberPassword, password = true)
        message?.let { Text(it, style = AppTheme.type.caption, color = c.error) }
        PrimaryButton("Change PIN", onClick = {
            when (val r = vm.changePin(current, next, confirm)) {
                is AdminPin.Change.Ok -> { current = ""; next = ""; confirm = ""; message = null }
                is AdminPin.Change.Rejected -> message = r.reason
            }
        })
    }
}

@Composable
private fun AnalysisCard(settings: AnalysisSettings, vm: AdminViewModel) {
    val c = AppTheme.colors
    val buffer: SnapshotStateMap<String, String> = remember(settings) {
        mutableStateMapOf<String, String>().apply { SPECS.forEach { put(it.key, it.read(settings).toString()) } }
    }
    var saveProblems by remember(settings) { mutableStateOf<List<String>>(emptyList()) }
    val (parsed, live) = parse(buffer)
    SurfaceCard {
        CardTitle("Analysis settings")
        Text(
            "Settings of the recommendation engine. They apply from the next analysis; existing audit logs are not changed.",
            style = AppTheme.type.caption, color = c.mutedText,
        )
        SPECS.forEach { spec ->
            SettingsField(
                buffer[spec.key].orEmpty(), { buffer[spec.key] = it; saveProblems = emptyList() }, spec.label,
                keyboard = if (spec.int) KeyboardType.Number else KeyboardType.Decimal,
            )
        }
        (live + saveProblems).distinct().forEach { Text(it, style = AppTheme.type.caption, color = c.error) }
        PrimaryButton("Save", onClick = { parsed?.let { saveProblems = vm.saveAnalysisSettings(it) } }, enabled = parsed != null && live.isEmpty())
        SecondaryButton("Reset to defaults", onClick = vm::resetAnalysisSettings)
    }
}

@Composable
private fun DeleteCard(checked: Boolean, vm: AdminViewModel) {
    val c = AppTheme.colors
    SurfaceCard {
        Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Also delete the analysis files (audit, outbox) when reports are deleted", style = AppTheme.type.body, color = c.onSurface,
                modifier = Modifier.weight(1f).padding(end = AppTheme.spacing.sm),
            )
            Switch(
                checked = checked, onCheckedChange = vm::setAlsoDeleteKg,
                colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary, uncheckedTrackColor = c.surfaceElevated, uncheckedThumbColor = c.mutedText),
            )
        }
    }
}

@Composable
private fun DemoCard(busy: Boolean, vm: AdminViewModel) {
    val c = AppTheme.colors
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    SurfaceCard {
        CardTitle("Demo data")
        Text(
            "Generate demo history creates about 30 synthetic reports with ids DEMO-nnnn and runs the analysis on them, so every tab has something to show. " +
                "It never touches real reports. Remove demo data deletes only the DEMO reports and their analysis files.",
            style = AppTheme.type.caption, color = c.mutedText,
        )
        PrimaryButton("Generate demo history", onClick = vm::generateDemo, enabled = !busy)
        SecondaryButton("Remove demo data", onClick = { confirmRemove = true }, enabled = !busy)
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove demo data?") },
            text = { Text("All reports with ids starting DEMO- are deleted. Real reports are not touched.") },
            confirmButton = { TextButton({ confirmRemove = false; vm.removeDemo() }) { Text("Remove", color = c.error) } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel", color = c.mutedText) } },
            containerColor = c.surface, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}

@Composable
private fun DevInfoCard(state: AdminUiState) {
    val context = LocalContext.current
    val snap = state.snapshot ?: return
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown" }
    SurfaceCard {
        CardTitle("Developer info")
        AdminKeyValue("App version", appVersion)
        AdminKeyValue("Report schema", IncidentReport.SCHEMA_VERSION)
        AdminKeyValue("Active graph version", snap.pack.packVersion.toString())
        AdminKeyValue("Stored graph versions", snap.versions.joinToString(", ").ifBlank { "none" })
        AdminKeyValue("Reports", snap.reports.size.toString())
        AdminKeyValue("Analysis audit logs", snap.audits.size.toString())
        AdminKeyValue("Outbox exports", snap.outbox.size.toString())
        AdminKeyValue("Dialogue logs", snap.dialogueLogs.size.toString())
        AdminKeyValue("Eval runs", snap.evalRuns.size.toString())
    }
}
