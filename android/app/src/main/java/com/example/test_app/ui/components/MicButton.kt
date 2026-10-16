package com.example.test_app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.test_app.ui.theme.AppTheme

/**
 * The big primary mic button. Idle: large circle with mic icon. [recording]: red circle with stop icon and a
 * pulsing ring that follows [level] (0..1). Pulse is disabled under "reduce motion".
 * Used by: HomeScreen, VoiceCaptureScreen, ClarificationScreen. See `doc/COMPONENTS.md`.
 */
@Composable
fun MicButton(
    onClick: () -> Unit,
    recording: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
    idleDescription: String = "Start recording",
    recordingDescription: String = "Stop recording",
) {
    val colors = AppTheme.colors
    val sizes = AppTheme.sizes
    val motion = AppTheme.motion
    val pulse = if (recording && !AppTheme.reduceMotion) {
        val t = rememberInfiniteTransition(label = "pulse")
        val v by t.animateFloat(
            initialValue = 1f, targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(motion.pulse, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulseScale",
        )
        v
    } else 1f
    val ringScale = pulse + level * 0.35f
    val size = sizes.micButton
    val container = if (recording) colors.recordingActive else colors.primary
    val content = if (recording) colors.onRecordingActive else colors.onPrimary
    val description = if (recording) recordingDescription else idleDescription

    Box(modifier = modifier.size(sizes.micButton * 1.35f), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier
                    .size(size)
                    .scale(ringScale)
                    .clip(CircleShape)
                    .background(colors.recordingActive.copy(alpha = 0.25f)),
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(container)
                .semantics { contentDescription = description }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(sizes.micIcon),
            )
        }
    }
}

/**
 * Smaller circular secondary action with a label below (for example the gallery button). [description] is the spoken label (default [label]).
 * Used by: HomeScreen. See `doc/COMPONENTS.md`.
 */
@Composable
fun SecondaryCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
) {
    val colors = AppTheme.colors
    val sizes = AppTheme.sizes
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs), modifier = modifier) {
        Box(
            modifier = Modifier
                .size(sizes.secondaryButton)
                .clip(CircleShape)
                .background(colors.surfaceElevated)
                .border(sizes.borderWidth, colors.border, CircleShape)
                .semantics { contentDescription = description }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(sizes.icon * 1.2f))
        }
        Text(label, style = AppTheme.type.label, color = colors.mutedText, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Same footprint as [MicButton] but shows a spinner: the mic's replacement while the report is being processed,
 * so the button does not jump. Used by: HomeScreen (processing mode). See `doc/COMPONENTS.md`.
 */
@Composable
fun ProgressOrb(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val sizes = AppTheme.sizes
    Box(modifier.size(sizes.micButton * 1.35f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(sizes.micButton)
                .clip(CircleShape)
                .background(colors.surfaceElevated)
                .border(sizes.borderWidth, colors.border, CircleShape)
                .semantics { contentDescription = "Processing your report" },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(sizes.iconLarge), color = colors.primary, strokeWidth = sizes.borderWidthStrong * 2,
            )
        }
    }
}
