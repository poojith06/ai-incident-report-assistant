package com.example.test_app.ui.success

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.displayText
import com.example.test_app.domain.model.entry
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.test_app.domain.config.EmergencyContacts
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.flow.AnalysisUiState
import com.example.test_app.ui.analysis.AnalysisCard
import com.example.test_app.ui.components.EmergencyContactsCard
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/** Confirmation with report ID and short summary, then the collapsible post-incident analysis card (ADR-055). Exit: "Report another incident" to Home. See `doc/UI_FLOW.md`. */
@Composable
fun SuccessScreen(report: IncidentReport, analysis: AnalysisUiState = AnalysisUiState.None, onAnother: () -> Unit) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    ScreenFrame(title = null) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(AppTheme.sizes.iconLarge * 1.6f).clip(CircleShape).background(c.success), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.onSuccess, modifier = Modifier.size(AppTheme.sizes.iconLarge))
            }
            Text("Report submitted", style = AppTheme.type.headline, color = c.onBackground, modifier = Modifier.padding(top = s.lg))
            Text(report.reportId, style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.xs))
            SurfaceCard(Modifier.padding(top = s.lg)) {
                fun line(f: IncidentField) = report.entry(f).displayText(f).ifBlank { "Not provided" }
                Summary("Type", line(IncidentField.INCIDENT_TYPE))
                Summary("Severity", line(IncidentField.SEVERITY_LEVEL))
                Summary("Injury", line(IncidentField.INJURY_OUTCOME))
                Summary("Location", line(IncidentField.INCIDENT_LOCATION))
                Summary("What happened", line(IncidentField.INCIDENT_DESCRIPTION))
            }
            if (EmergencyContacts.isSevere(report.entry(IncidentField.SEVERITY_LEVEL).value.asTextOrNull())) {
                EmergencyContactsCard(EmergencyContacts.DUMMY, Modifier.padding(top = s.md, bottom = s.md))
            }
            AnalysisCard(analysis, Modifier.padding(top = s.md, bottom = s.md))
        }
        PrimaryButton("Report another incident", onClick = onAnother)
        Box(Modifier.padding(bottom = s.md))
    }
}

@Composable
private fun Summary(label: String, value: String) {
    Column {
        Text(label, style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        Text(value, style = AppTheme.type.body, color = AppTheme.colors.onSurface, textAlign = TextAlign.Start)
    }
}
