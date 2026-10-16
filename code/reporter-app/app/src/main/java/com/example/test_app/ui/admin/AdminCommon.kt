package com.example.test_app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.example.test_app.ui.theme.AppTheme

/** Shared building blocks of the admin tabs (token styled). */

/** Search box with a hint, used by Reports, Logs, Graph data and the graph. */
@Composable
fun AdminSearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier.fillMaxWidth(), placeholder = { Text(hint, color = c.mutedText) },
        textStyle = AppTheme.type.body.copy(color = c.onSurface),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.border, cursorColor = c.primary),
    )
}

/** One row of single-select chips that scrolls sideways instead of overflowing; [options] null entry = "all" is added by the caller. */
@Composable
fun AdminChipRow(options: List<String>, selected: String?, onSelect: (String?) -> Unit, allLabel: String = "All", modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        (listOf<String?>(null) + options).forEach { o ->
            FilterChip(
                selected = selected == o, onClick = { onSelect(o) }, label = { Text(o ?: allLabel, style = AppTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = c.primaryContainer, selectedLabelColor = c.onPrimaryContainer, containerColor = c.surface, labelColor = c.onSurface,
                ),
            )
        }
    }
}

/** A small rounded label (status, level, kind). */
@Composable
fun AdminPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = AppTheme.type.caption, color = color,
        modifier = modifier.clip(RoundedCornerShape(AppTheme.radii.sm)).background(AppTheme.colors.surfaceElevated).padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xs),
    )
}

/** Pretty JSON in a monospace block that wraps (no horizontal overflow), selectable by the system. */
@Composable
fun JsonBlock(json: String, modifier: Modifier = Modifier, maxChars: Int = 12_000) {
    val shown = if (json.length > maxChars) json.take(maxChars) + "\n... (${json.length - maxChars} more characters; use Share to get all)" else json
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            shown, style = AppTheme.type.code, color = AppTheme.colors.onSurface,
            modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(AppTheme.radii.sm)).background(AppTheme.colors.background).padding(AppTheme.spacing.sm),
        )
    }
}

/** "label  value" row that wraps long values. */
@Composable
fun AdminKeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier.padding(vertical = AppTheme.spacing.xs)) {
        Text(label, style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        Text(value, style = AppTheme.type.body, color = AppTheme.colors.onSurface)
    }
}

/** Colour of a status word (APPROVED, DRAFT, VERIFIED, NEEDS_ADMIN_VERIFICATION, EXPIRED, REJECTED, DISABLED, ...). */
@Composable
fun adminStatusColor(status: String?): Color {
    val c = AppTheme.colors
    return when (status?.uppercase()) {
        "APPROVED", "VERIFIED", "ACTIVE", "OK" -> c.success
        "DRAFT", "REVIEWED", "NEEDS_ADMIN_VERIFICATION", "WARN" -> c.warning
        "EXPIRED", "REJECTED", "DISABLED", "ERROR" -> c.error
        else -> c.mutedText
    }
}
