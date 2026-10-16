package com.example.test_app.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.test_app.domain.config.EmergencyContact
import com.example.test_app.ui.theme.AppTheme

/**
 * "Call for help" list shown for severe incidents. Tapping a row opens the phone dialer with the number filled in
 * (ACTION_DIAL: the user presses call; no CALL_PHONE permission needed). Used by: ReviewScreen, SuccessScreen, SettingsScreen.
 * See `doc/COMPONENTS.md`.
 */
@Composable
fun EmergencyContactsCard(
    contacts: List<EmergencyContact>,
    modifier: Modifier = Modifier,
    /** Heading; the Settings screen shows the same list as a plain reference ([severe] = false: neutral border and title). */
    title: String = "Severe incident: call for help",
    severe: Boolean = true,
) {
    val c = AppTheme.colors
    val context = LocalContext.current
    SurfaceCard(
        modifier,
        borderColor = if (severe) c.error else c.border,
        borderWidth = if (severe) AppTheme.sizes.borderWidthStrong else AppTheme.sizes.borderWidth,
    ) {
        Text(title, style = AppTheme.type.title, color = if (severe) c.error else c.onSurface)
        Text("Tap a person to open the dialer.", style = AppTheme.type.caption, color = c.mutedText)
        contacts.forEach { contact ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.minTouch + AppTheme.spacing.sm)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AppTheme.radii.sm))
                    .semantics { contentDescription = "Call ${contact.name}, ${contact.role}, ${contact.phone}" }
                    .clickable(role = Role.Button) {
                        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + contact.phone.filter { it.isDigit() || it == '+' }))
                        try { context.startActivity(dial) } catch (_: ActivityNotFoundException) { }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(contact.name, style = AppTheme.type.body, color = c.onSurface)
                    Text("${contact.role}  \u00B7  ${contact.phone}", style = AppTheme.type.caption, color = c.mutedText)
                }
                Box(
                    Modifier.size(AppTheme.sizes.minTouch).clip(CircleShape).background(c.success),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null, tint = c.onSuccess, modifier = Modifier.size(AppTheme.sizes.icon))
                }
            }
        }
    }
}
