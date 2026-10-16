package com.example.test_app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.test_app.domain.config.SpokenLanguages
import com.example.test_app.domain.config.UserSettings
import com.example.test_app.ui.theme.AppTheme

/**
 * Small pill under the Home mic text: "Language: Auto" / "Language: Tamil". Tapping it opens [SpokenLanguageSheet]. 48 dp high.
 * Used by: HomeScreen. See `doc/COMPONENTS.md`.
 */
@Composable
fun LanguageChip(spokenLanguage: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(AppTheme.radii.md)
    val value = if (spokenLanguage.equals(UserSettings.SPOKEN_AUTO, ignoreCase = true) || spokenLanguage.isBlank()) "Auto" else spokenLanguage
    Row(
        modifier
            .heightIn(min = AppTheme.sizes.minTouch)
            .clip(shape)
            .border(AppTheme.sizes.borderWidth, c.border, shape)
            .semantics { contentDescription = "Spoken language: $value. Tap to change." }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AppTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Language: $value", style = AppTheme.type.label, color = c.mutedText)
    }
}

/**
 * Bottom sheet that picks the "Spoken language": "Auto-detect" plus the names of `assets/dialogue/languages.json`. The same list as
 * Settings > Spoken language. Used by: AppRoot (opened from the Home chip and the Settings row). See `doc/UI_FLOW.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpokenLanguageSheet(
    languages: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    val options = listOf(UserSettings.SPOKEN_AUTO) + languages
    val current = if (selected.isBlank()) UserSettings.SPOKEN_AUTO else selected
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, contentColor = c.onSurface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = s.lg, vertical = s.sm),
            verticalArrangement = Arrangement.spacedBy(s.xs),
        ) {
            Text("Spoken language", style = AppTheme.type.title, color = c.primary)
            Text(
                "Tell the speech model which language you speak. Auto-detect lets it guess (it can guess wrong, for example for Tamil).",
                style = AppTheme.type.caption, color = c.mutedText,
            )
            options.forEach { name ->
                val isSelected = name.equals(current, ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(name); onDismiss() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.mutedText),
                    )
                    Text(SpokenLanguages.label(name), style = AppTheme.type.body, color = c.onSurface, modifier = Modifier.padding(start = s.sm))
                }
            }
        }
    }
}
