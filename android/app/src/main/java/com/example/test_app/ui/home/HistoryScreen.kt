package com.example.test_app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.displayText
import com.example.test_app.domain.model.entry
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.components.contentPadding
import com.example.test_app.ui.theme.AppTheme

/** Read-only list of locally stored submitted reports. Entry: Home "Past reports". See `doc/UI_FLOW.md`. */
@Composable
fun HistoryScreen(reports: List<IncidentReport>, onBack: () -> Unit) {
    val c = AppTheme.colors
    ScreenFrame(title = "Past reports", onLeading = onBack) {
        if (reports.isEmpty()) {
            Text("No submitted reports yet.", style = AppTheme.type.body, color = c.mutedText, modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                items(reports, key = { it.reportId }) { r ->
                    SurfaceCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(r.reportId, style = AppTheme.type.label, color = c.primary)
                            Text(r.createdAt.take(16).replace('T', ' '), style = AppTheme.type.caption, color = c.mutedText)
                        }
                        Text(r.entry(IncidentField.INCIDENT_TYPE).displayText(IncidentField.INCIDENT_TYPE), style = AppTheme.type.title, color = c.onSurface)
                        Text(r.entry(IncidentField.INCIDENT_LOCATION).displayText(IncidentField.INCIDENT_LOCATION), style = AppTheme.type.body, color = c.mutedText)
                    }
                }
            }
        }
    }
}
