package com.example.test_app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import com.example.test_app.domain.config.EmergencyContact
import com.example.test_app.domain.config.SpeechRate
import com.example.test_app.domain.config.SpokenLanguages
import com.example.test_app.domain.config.UserSettings
import com.example.test_app.ui.components.EmergencyContactsCard
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/** Read-only lines of the About section (built by `AppRoot`; the app is offline, so there is nothing to link to). */
data class AboutInfo(
    val appVersion: String,
    val schemaVersion: String,
    /** e.g. "Mock AI" or "Gemma 4 E2B (Real AI)". */
    val aiBackend: String,
    /** e.g. "Not needed (mock)", "Loading...", "Loaded", "Failed: ...". */
    val modelsState: String,
    /** What happens to gallery photos with this AI, e.g. "Attached only (this model cannot see photos)". */
    val photos: String,
)

/**
 * User-facing Settings (not the developer menu): follow-up questions on/off, hands-free answers, read results aloud, speech rate,
 * the emergency contacts (read-only, with the dial action), "Delete all past reports" (with a confirm dialog) and About.
 * Opened by the gear on Home; the back arrow (or the system back) returns Home. Everything is a token-styled card. See `doc/UI_FLOW.md`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    onChange: ((UserSettings) -> UserSettings) -> Unit,
    contacts: List<EmergencyContact>,
    reportCount: Int,
    onDeleteAllReports: () -> Unit,
    about: AboutInfo,
    /** The value of "Spoken language" and whether it can be changed (Gemma speech only); [languageNote] explains why not. */
    spokenLanguage: String,
    languageEnabled: Boolean,
    languageNote: String?,
    onSpokenLanguage: () -> Unit,
    onBack: () -> Unit,
) {
    val s = AppTheme.spacing
    val c = AppTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenFrame(title = "Settings", leadingIcon = Icons.AutoMirrored.Filled.ArrowBack, leadingDescription = "Back", onLeading = onBack) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = s.md),
            verticalArrangement = Arrangement.spacedBy(s.md),
        ) {
            SectionTitle("Conversation")
            SurfaceCard {
                SettingSwitch(
                    title = "Follow-up questions",
                    detail = if (settings.followUpsEnabled) "I may ask up to 2 short questions about missing details."
                    else "Off: I ask nothing. Missing details are highlighted on the review screen.",
                    checked = settings.followUpsEnabled,
                ) { v -> onChange { it.copy(followUpsEnabled = v) } }
                SettingSwitch(
                    title = "Hands-free answers",
                    detail = if (settings.followUpsEnabled) "The microphone starts by itself after a question. Off: tap the mic to answer."
                    else "Only used when follow-up questions are on.",
                    checked = settings.handsFreeAnswers,
                    enabled = settings.followUpsEnabled,
                ) { v -> onChange { it.copy(handsFreeAnswers = v) } }
            }

            SectionTitle("Voice")
            SurfaceCard {
                SettingSwitch(
                    title = "Read results aloud",
                    detail = "Reads the report summary on the review screen and the confirmation after you submit.",
                    checked = settings.readResultsAloud,
                ) { v -> onChange { it.copy(readResultsAloud = v) } }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch).alpha(if (languageEnabled) 1f else 0.5f)
                        .clickable(enabled = languageEnabled, role = Role.Button, onClick = onSpokenLanguage),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = s.sm)) {
                        Text("Spoken language", style = AppTheme.type.body, color = c.onSurface)
                        Text(
                            languageNote ?: "Tell the speech model which language you speak; Auto-detect can guess wrong.",
                            style = AppTheme.type.caption, color = c.mutedText,
                        )
                    }
                    Text(SpokenLanguages.label(spokenLanguage), style = AppTheme.type.body, color = c.primary)
                }
                Text("Speech rate", style = AppTheme.type.body, color = c.onSurface, modifier = Modifier.padding(top = s.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(s.sm), verticalArrangement = Arrangement.spacedBy(s.xs)) {
                    SpeechRate.entries.forEach { rate ->
                        FilterChip(
                            selected = settings.speechRate == rate,
                            onClick = { onChange { it.copy(speechRate = rate) } },
                            label = { Text(rate.label, style = AppTheme.type.label) },
                            modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.primaryContainer, selectedLabelColor = c.onPrimaryContainer,
                                containerColor = c.surface, labelColor = c.onSurface,
                            ),
                        )
                    }
                }
            }

            SectionTitle("After a report")
            SurfaceCard {
                SettingSwitch(
                    title = "Post-incident analysis",
                    detail = "After you submit, show what could follow and what to do next, from an offline checklist. Works without the internet.",
                    checked = settings.postIncidentAnalysis,
                ) { v -> onChange { it.copy(postIncidentAnalysis = v) } }
            }

            SectionTitle("Emergency contacts")
            EmergencyContactsCard(contacts, title = "Contacts shown after a severe incident", severe = false)

            SectionTitle("Past reports")
            SurfaceCard {
                Text(
                    if (reportCount == 0) "No reports are stored on this device." else "$reportCount report${if (reportCount == 1) "" else "s"} stored on this device only.",
                    style = AppTheme.type.body, color = c.onSurface,
                )
                SecondaryButton("Delete all past reports", onClick = { confirmDelete = true }, enabled = reportCount > 0)
            }

            SectionTitle("About")
            SurfaceCard {
                AboutLine("App version", about.appVersion)
                AboutLine("Report schema", about.schemaVersion)
                AboutLine("AI", about.aiBackend)
                AboutLine("Models", about.modelsState)
                AboutLine("Photos", about.photos)
                Text("Everything runs on this device. No network is used.", style = AppTheme.type.caption, color = c.mutedText)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all past reports?") },
            text = { Text("$reportCount stored report${if (reportCount == 1) "" else "s"} will be removed from this device. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteAllReports() }) { Text("Delete all", color = c.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep them") } },
            containerColor = c.surfaceElevated, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = AppTheme.type.title, color = AppTheme.colors.primary, modifier = Modifier.padding(top = AppTheme.spacing.xs))
}

/** A title + one-line explanation + switch row; dimmed and inert when [enabled] is false. */
@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch).alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = AppTheme.spacing.sm)) {
            Text(title, style = AppTheme.type.body, color = c.onSurface)
            Text(detail, style = AppTheme.type.caption, color = c.mutedText)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary,
                uncheckedTrackColor = c.surfaceElevated, uncheckedThumbColor = c.mutedText,
            ),
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AppTheme.type.body, color = c.mutedText)
        Text(value, style = AppTheme.type.body, color = c.onSurface, modifier = Modifier.padding(start = AppTheme.spacing.md))
    }
}
