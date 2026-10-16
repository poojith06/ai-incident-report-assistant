package com.example.test_app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.test_app.domain.model.FieldGroup
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.entry
import com.example.test_app.domain.model.isEmptyValue
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.ui.components.EmergencyBanner
import com.example.test_app.ui.components.NoticeBanner
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SourceChip
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.components.Thumbnail
import com.example.test_app.ui.theme.AppTheme
import kotlinx.serialization.json.JsonElement

/**
 * Review form: every field editable, with an AI / user / empty marker, required fields highlighted when
 * missing or low-confidence, a speaker button to replay/stop the spoken read-out, photos, collapsible transcript, "Add more by voice", anonymous toggle, Submit.
 * Entry: after the Process block. Exit: Submit (to Success) or discard. See `doc/UI_FLOW.md` section Review.
 */
@Composable
fun ReviewScreen(
    report: IncidentReport,
    submitting: Boolean,
    speaking: Boolean,
    notice: String?,
    onDismissNotice: () -> Unit,
    onEdit: (String, JsonElement) -> Unit,
    onReadAloud: () -> Unit,
    onAnonymous: (Boolean) -> Unit,
    onAddMore: () -> Unit,
    onSubmit: () -> Unit,
    onDiscard: () -> Unit,
) {
    val s = AppTheme.spacing
    val c = AppTheme.colors
    var confirmDiscard by remember { mutableStateOf(false) }
    var transcriptOpen by remember { mutableStateOf(false) }
    val v = report.validation
    val missingCount = v.missingFields.size

    ScreenFrame(title = "Review report", leadingIcon = Icons.Filled.Close, leadingDescription = "Discard report", onLeading = { confirmDiscard = true }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s.md), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = s.md)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(s.xs)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(report.reportId, style = AppTheme.type.label, color = c.mutedText, modifier = Modifier.weight(1f))
                        IconButton(onClick = onReadAloud, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                            Icon(
                                if (speaking) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                                contentDescription = if (speaking) "Stop reading aloud" else "Read aloud",
                                tint = if (speaking) c.error else c.primary,
                            )
                        }
                    }
                    if (report.dialogue.emergency) EmergencyBanner(
                        "Emergency flagged. Your safety comes first: get somewhere safe and contact emergency services or security yourself. Nothing has been sent from this app.",
                    )
                    if (notice != null) NoticeBanner(notice, onDismissNotice)
                    Text(
                        when {
                            missingCount > 0 -> "$missingCount required ${if (missingCount == 1) "detail is" else "details are"} missing. Highlighted below."
                            v.lowConfidenceFields.isNotEmpty() || report.fields.values.any { it.effectiveStatus in FieldStatus.NEEDS_ATTENTION } ->
                                "Please double-check the highlighted details."
                            else -> "Everything required is filled in. Check and submit."
                        },
                        style = AppTheme.type.body, color = if (missingCount > 0) c.error else c.onBackground,
                    )
                }
            }
            if (report.input.media.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(s.sm)) {
                        itemsIndexed(report.input.media, key = { _, m -> m.id }) { i, m -> Thumbnail(m, i) }
                    }
                }
            }
            item {
                SurfaceCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("What you said", style = AppTheme.type.title, color = c.onSurface)
                        IconButton(onClick = { transcriptOpen = !transcriptOpen }) {
                            Icon(
                                if (transcriptOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (transcriptOpen) "Hide transcript" else "Show transcript", tint = c.mutedText,
                            )
                        }
                    }
                    if (transcriptOpen) {
                        Text(report.input.transcript, style = AppTheme.type.body, color = c.mutedText)
                        report.clarification.history.forEach { t ->
                            Text("Q: ${t.question}", style = AppTheme.type.caption, color = c.mutedText)
                            Text("A: ${t.answer}", style = AppTheme.type.body, color = c.mutedText)
                        }
                    }
                }
            }
            FieldGroup.entries.forEach { group ->
                item(key = "group_${group.name}") {
                    Text(group.title, style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.sm))
                }
                IncidentField.entries.filter { it.group == group }.forEach { field ->
                    item(key = field.key) {
                        val entry = report.entry(field)
                        val mandatory = field.key in v.mandatoryFields
                        val missing = field.key in v.missingFields
                        val low = field.key in v.lowConfidenceFields
                        val status = entry.effectiveStatus
                        val flagged = status in FieldStatus.NEEDS_ATTENTION
                        SurfaceCard(
                            borderColor = when {
                                missing -> c.error
                                low || flagged -> c.warning
                                else -> c.border
                            },
                            borderWidth = if (missing || low || flagged) AppTheme.sizes.borderWidthStrong else AppTheme.sizes.borderWidth,
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    field.label + if (mandatory) " *" else "",
                                    style = AppTheme.type.label, color = c.onSurface, modifier = Modifier.weight(1f),
                                )
                                SourceChip(if (entry.value.isEmptyValue()) null else entry.source)
                            }
                            FieldEditor(field, entry) { onEdit(field.key, it) }
                            if (missing) Text("Required", style = AppTheme.type.caption, color = c.error)
                            else if (flagged) Text(flaggedMessage(status, entry.rawAnswer), style = AppTheme.type.caption, color = c.warning)
                            else if (low) Text("Not sure about this one. Please check.", style = AppTheme.type.caption, color = c.warning)
                        }
                    }
                }
            }
            item {
                val device = report.device
                SurfaceCard {
                    Text("Reporter", style = AppTheme.type.title, color = c.primary)
                    Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (device?.anonymous == true) "Anonymous" else device?.reporter ?: "Unknown device",
                                style = AppTheme.type.body, color = c.onSurface,
                            )
                            Text("From this device. Reported ${device?.reportedAt?.take(16)?.replace('T', ' ') ?: "now"}.", style = AppTheme.type.caption, color = c.mutedText)
                            Text(
                                device?.gps?.let { "GPS %.5f, %.5f".format(it.latitude, it.longitude) } ?: "GPS not captured",
                                style = AppTheme.type.caption, color = c.mutedText,
                            )
                        }
                        Text("Anonymous", style = AppTheme.type.label, color = c.onSurface, modifier = Modifier.padding(end = s.sm))
                        Switch(
                            checked = device?.anonymous == true, onCheckedChange = onAnonymous,
                            colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary, uncheckedTrackColor = c.surfaceElevated, uncheckedThumbColor = c.mutedText),
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(s.sm), modifier = Modifier.padding(top = s.sm)) {
                    SecondaryButton("Add more by voice", onClick = onAddMore, icon = Icons.Filled.Mic)
                    PrimaryButton(
                        text = when {
                            submitting -> "Submitting\u2026"
                            missingCount > 0 -> "Fill required details to submit"
                            else -> "Submit report"
                        },
                        onClick = onSubmit, enabled = missingCount == 0 && !submitting,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this report?") },
            text = { Text("Nothing will be saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text("Discard", color = c.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
            containerColor = c.surfaceElevated, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}

/** Caption for a field the follow-up dialogue could not fill (`FieldStatus.NEEDS_ATTENTION`). */
private fun flaggedMessage(status: String, rawAnswer: String?): String = when (status) {
    FieldStatus.UNKNOWN_REPORTER_UNSURE -> "You weren't sure about this one. Tap to fill it in."
    FieldStatus.NO_RESPONSE -> "I didn't hear an answer for this. Tap to fill it in."
    FieldStatus.UNVERIFIED -> if (rawAnswer.isNullOrBlank()) "I couldn't be sure about this. Please check it." else "I heard \"$rawAnswer\" but couldn't be sure. Please check it."
    else -> "Still missing. Tap to fill it in."
}
