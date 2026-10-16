package com.example.test_app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.test_app.ui.theme.AppTheme

/**
 * Scrolling live level meter: keeps the last [bars] samples of [level] (0..1) and draws them as rounded bars.
 * Decorative (hidden from accessibility). Used by: VoiceCaptureScreen, ClarificationScreen.
 */
@Composable
fun Waveform(level: Float, active: Boolean, modifier: Modifier = Modifier, bars: Int = 36) {
    val history = remember { mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    LaunchedEffect(level, active) {
        history.add(if (active) level else 0f)
        while (history.size > bars) history.removeAt(0)
    }
    val color = if (active) AppTheme.colors.recordingActive else AppTheme.colors.border
    val barWidth = AppTheme.sizes.waveformBar
    Canvas(
        modifier
            .fillMaxWidth()
            .height(AppTheme.sizes.waveformHeight)
            .clearAndSetSemantics { },
    ) {
        val bw = barWidth.toPx()
        val slot = size.width / bars
        history.forEachIndexed { i, v ->
            val h = (size.height * (0.08f + 0.92f * v)).coerceAtMost(size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - bw) / 2f, (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f),
            )
        }
    }
}

/** Formats milliseconds as m:ss. */
fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
