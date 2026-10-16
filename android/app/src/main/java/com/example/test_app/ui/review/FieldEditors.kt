package com.example.test_app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldKind
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.Severity
import com.example.test_app.domain.model.Witness
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.asWitnesses
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.model.witnessValue
import com.example.test_app.ui.components.LinkButton
import com.example.test_app.ui.theme.AppTheme
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Picks and renders the editor for a field's [FieldKind]. Edits are reported through [onChange] as a JSON value.
 * Used by: ReviewScreen. See `doc/COMPONENTS.md`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldEditor(field: IncidentField, entry: FieldEntry, onChange: (JsonElement) -> Unit) {
    when (field.kind) {
        FieldKind.TEXT -> TextEditor(field, entry.value.asTextOrNull().orEmpty(), singleLine = true) { onChange(textValue(it)) }
        FieldKind.LONG_TEXT -> TextEditor(field, entry.value.asTextOrNull().orEmpty(), singleLine = false) { onChange(textValue(it)) }
        FieldKind.SEVERITY -> ChoiceChips(Severity.ALLOWED, entry.value.asTextOrNull()) { onChange(textValue(it)) }
        FieldKind.INJURY -> ChoiceChips(InjuryOutcome.ALLOWED, entry.value.asTextOrNull()) { onChange(textValue(it)) }
        FieldKind.YES_NO -> ChoiceChips(listOf("Yes", "No"), entry.value.asBooleanOrNull()?.let { if (it) "Yes" else "No" }) {
            onChange(boolValue(it == "Yes"))
        }
        FieldKind.DATE_TIME -> DateTimeEditor(entry.value.asTextOrNull()) { onChange(textValue(it)) }
        FieldKind.STRING_LIST -> TextEditor(
            field, entry.value.asStringList().joinToString(", "), singleLine = true, hint = "Separate with commas",
        ) { text -> onChange(listValue(text.split(",").map { it.trim() }.filter { it.isNotEmpty() })) }
        FieldKind.WITNESSES -> TextEditor(
            field, entry.value.asWitnesses().joinToString("\n") { listOfNotNull(it.name, it.role, it.contact).joinToString(", ") },
            singleLine = false, hint = "One per line: name, role, contact",
        ) { text ->
            onChange(witnessValue(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                val p = line.split(",").map { it.trim() }
                Witness(p[0], p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null })
            }))
        }
    }
}

@Composable
private fun TextEditor(field: IncidentField, initial: String, singleLine: Boolean, hint: String? = null, onText: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val c = AppTheme.colors
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onText(it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        label = { Text(hint ?: field.label, style = AppTheme.type.caption) },
        textStyle = AppTheme.type.body,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.onSurface, unfocusedTextColor = c.onSurface,
            focusedBorderColor = c.primary, unfocusedBorderColor = c.border,
            focusedLabelColor = c.primary, unfocusedLabelColor = c.mutedText, cursorColor = c.primary,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    val c = AppTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, style = AppTheme.type.label) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = c.primaryContainer, selectedLabelColor = c.onPrimaryContainer,
                    containerColor = c.surface, labelColor = c.onSurface,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeEditor(iso: String?, onChange: (String) -> Unit) {
    val parsed = remember(iso) { iso?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } }
    val zone = parsed?.offset ?: OffsetDateTime.now().offset
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val label = parsed?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())) ?: "Not set"

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AppTheme.type.body, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
        LinkButton("Change", onClick = { showDate = true })
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (parsed ?: OffsetDateTime.now()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { pickedDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                    if (pickedDate != null) showTime = true
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        val base = parsed ?: OffsetDateTime.now()
        val tState = rememberTimePickerState(initialHour = base.hour, initialMinute = base.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: base.toLocalDate()
                    val dt = date.atTime(tState.hour, tState.minute).atOffset(zone)
                    onChange(dt.toString())
                    showTime = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = tState, modifier = Modifier.padding(AppTheme.spacing.xs)) },
        )
    }
}
