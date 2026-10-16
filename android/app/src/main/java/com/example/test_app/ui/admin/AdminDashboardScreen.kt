package com.example.test_app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.test_app.admin.AdminTab
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.ui.admin.graph.AdminGraphTab
import com.example.test_app.ui.components.NoticeBanner
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.theme.AppTheme

/**
 * The in-app admin dashboard (ADR-058): a top bar (back = lock and leave, refresh), a scrollable tab row and the selected tab. Tabs:
 * Overview, Reports, Logs, Graph, Graph data (knowledge editor), Outbox and Settings. All state lives in [AdminViewModel]; the tabs only draw it.
 * Opened from the Admin button on Home after the PIN gate. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminDashboardScreen(state: AdminUiState, vm: AdminViewModel, onExit: () -> Unit) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    ScreenFrame(
        title = "Admin dashboard", onLeading = { vm.lock(); onExit() }, leadingDescription = "Lock and leave the admin dashboard",
        trailing = {
            if (state.loading || state.busy != null) CircularProgressIndicator(Modifier.size(AppTheme.sizes.icon).padding(end = s.xs), color = c.primary, strokeWidth = AppTheme.sizes.borderWidthStrong)
            IconButton(onClick = vm::refresh, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload the admin data", tint = c.onBackground)
            }
            IconButton(onClick = { vm.lock(); onExit() }, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                Icon(Icons.Filled.Lock, contentDescription = "Lock the admin dashboard", tint = c.onBackground)
            }
        },
    ) {
        ScrollableTabRow(
            selectedTabIndex = AdminTab.entries.indexOf(state.tab), containerColor = c.background, contentColor = c.primary, edgePadding = androidx.compose.ui.unit.Dp(0f),
        ) {
            AdminTab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab, onClick = { vm.selectTab(tab) },
                    text = { Text(tab.label, style = AppTheme.type.label, color = if (state.tab == tab) c.primary else c.mutedText) },
                )
            }
        }
        state.busy?.let { Text("$it\u2026", style = AppTheme.type.caption, color = c.mutedText, modifier = Modifier.padding(top = s.xs)) }
        state.notice?.let { NoticeBanner(it, vm::dismissNotice, Modifier.padding(top = s.xs)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val snapshot = state.snapshot
            if (snapshot == null) {
                Text(if (state.loading) "Loading\u2026" else "No data yet. Tap reload.", style = AppTheme.type.body, color = c.mutedText, modifier = Modifier.padding(top = s.lg))
            } else when (state.tab) {
                AdminTab.OVERVIEW -> AdminOverviewTab(state, vm)
                AdminTab.REPORTS -> AdminReportsTab(state, vm)
                AdminTab.LOGS -> AdminLogsTab(state, vm)
                AdminTab.GRAPH -> AdminGraphTab(state, vm)
                AdminTab.DATA -> AdminDataTab(state, vm)
                AdminTab.SYNC -> AdminSyncTab(state, vm)
                AdminTab.SETTINGS -> AdminSettingsTab(state, vm)
            }
        }
    }
}
