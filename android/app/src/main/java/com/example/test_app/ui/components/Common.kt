package com.example.test_app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.ui.theme.AppTheme

/**
 * Standard screen frame: safe-area padding, optional top bar with a leading icon button, centered content
 * column capped at the token max width. Used by: every screen. See `doc/COMPONENTS.md`.
 */
@Composable
fun ScreenFrame(
    title: String?,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingDescription: String = "Back",
    onLeading: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = AppTheme.spacing
    Box(
        Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.fillMaxSize().widthIn(max = AppTheme.sizes.contentMaxWidth)) {
            if (title != null || onLeading != null || trailing != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = s.sm, vertical = s.xs).heightIn(min = AppTheme.sizes.minTouch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onLeading != null) {
                        IconButton(onClick = onLeading, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                            Icon(leadingIcon ?: Icons.AutoMirrored.Filled.ArrowBack, contentDescription = leadingDescription, tint = AppTheme.colors.onBackground)
                        }
                    }
                    if (title != null) {
                        Text(title, style = AppTheme.type.title, color = AppTheme.colors.onBackground, modifier = Modifier.weight(1f).padding(start = s.sm))
                    } else Box(Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = s.md), content = content)
        }
    }
}

/** Cancel (X) icon used as the leading action on capture / clarification screens. */
val CancelIcon: ImageVector get() = Icons.Filled.Close

/** Full-width primary button with token sizing. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch + AppTheme.spacing.sm),
        shape = RoundedCornerShape(AppTheme.radii.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary, contentColor = c.onPrimary,
            disabledContainerColor = c.surfaceElevated, disabledContentColor = c.mutedText,
        ),
    ) { Text(text, style = AppTheme.type.title) }
}

/** Full-width filled tonal button: a clear, weighty secondary action (for example "Skip and fill manually"). */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch + AppTheme.spacing.sm),
        shape = RoundedCornerShape(AppTheme.radii.md),
        colors = ButtonDefaults.buttonColors(containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer),
    ) { Text(text, style = AppTheme.type.title) }
}

/** Full-width outlined secondary button. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch),
        shape = RoundedCornerShape(AppTheme.radii.md),
        border = BorderStroke(AppTheme.sizes.borderWidth, c.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onBackground),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(AppTheme.sizes.icon))
            Box(Modifier.size(AppTheme.spacing.sm))
        }
        Text(text, style = AppTheme.type.label)
    }
}

/** Text-only tertiary action (min touch target enforced). */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = AppTheme.sizes.minTouch)) {
        Text(text, style = AppTheme.type.label, color = AppTheme.colors.primary)
    }
}

/** Rounded surface container. Used by: ReviewScreen, ProcessingScreen, HistoryScreen. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    borderColor: Color = AppTheme.colors.border,
    borderWidth: androidx.compose.ui.unit.Dp = AppTheme.sizes.borderWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radii.md))
            .background(AppTheme.colors.surface)
            .border(borderWidth, borderColor, RoundedCornerShape(AppTheme.radii.md))
            .padding(AppTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        content = content,
    )
}

/**
 * Red, non-dismissible banner for `dialogue.emergency == true`. Tokens only (errorContainer / error).
 * Used by: ReviewScreen. No alert is sent in this phase; the banner only tells the user to get to safety.
 */
@Composable
fun EmergencyBanner(message: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radii.md))
            .background(c.errorContainer)
            .border(BorderStroke(AppTheme.sizes.borderWidthStrong, c.error), RoundedCornerShape(AppTheme.radii.md))
            .padding(AppTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = "Emergency", tint = c.error, modifier = Modifier.size(AppTheme.sizes.icon))
        Text(message, style = AppTheme.type.label, color = c.onSurface, modifier = Modifier.weight(1f))
    }
}

/** Dismissible info banner for non-blocking notices (for example "TTS unavailable"). */
@Composable
fun NoticeBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radii.md))
            .background(c.surfaceElevated)
            .padding(start = AppTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = c.warning, modifier = Modifier.size(AppTheme.sizes.icon))
        Text(message, style = AppTheme.type.label, color = c.onSurface, modifier = Modifier.weight(1f).padding(AppTheme.spacing.sm))
        IconButton(onClick = onDismiss, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss message", tint = c.mutedText)
        }
    }
}

/** Status of one row in [StepRow]. */
enum class StepStatus { PENDING, ACTIVE, DONE }

/** One line of the processing step list. Used by: ProcessingScreen. */
@Composable
fun StepRow(label: String, detail: String?, status: StepStatus, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
        Box(Modifier.size(AppTheme.sizes.iconLarge * 0.6f), contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.DONE -> Box(
                    Modifier.size(AppTheme.sizes.icon).clip(CircleShape).background(c.success),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, contentDescription = "Done", tint = c.onSuccess, modifier = Modifier.size(AppTheme.sizes.icon * 0.7f)) }
                StepStatus.ACTIVE -> CircularProgressIndicator(
                    modifier = Modifier.size(AppTheme.sizes.icon), color = c.primary, strokeWidth = AppTheme.sizes.borderWidthStrong,
                )
                StepStatus.PENDING -> Box(Modifier.size(AppTheme.sizes.statusDot).clip(CircleShape).background(c.border))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                label, style = AppTheme.type.body,
                color = if (status == StepStatus.PENDING) c.mutedText else c.onBackground,
            )
            if (detail != null) Text(detail, style = AppTheme.type.caption, color = c.mutedText)
        }
    }
}

/** Small colored pill describing where a field value came from. Used by: ReviewScreen. */
@Composable
fun SourceChip(source: String?, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val (label, color) = when (source) {
        FieldSource.AI -> "AI-filled" to c.fieldAi
        FieldSource.USER_VOICE -> "Your answer" to c.fieldUserVoice
        FieldSource.USER_EDIT -> "Edited" to c.fieldUserEdited
        else -> "Empty" to c.mutedText
    }
    Row(
        modifier
            .clip(RoundedCornerShape(AppTheme.radii.pill))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        Box(Modifier.size(AppTheme.sizes.statusDot * 0.7f).clip(CircleShape).background(color))
        Text(label, style = AppTheme.type.caption, color = color)
    }
}

/** Centered message with icon-less title, used by Error and Success screens. */
@Composable
fun CenteredMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        Text(title, style = AppTheme.type.headline, color = AppTheme.colors.onBackground, textAlign = TextAlign.Center)
        Text(body, style = AppTheme.type.body, color = AppTheme.colors.mutedText, textAlign = TextAlign.Center)
    }
}

/** Standard vertical padding values used by scroll content. */
@Composable
fun contentPadding(): PaddingValues = PaddingValues(vertical = AppTheme.spacing.md)
