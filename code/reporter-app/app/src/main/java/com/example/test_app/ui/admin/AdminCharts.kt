package com.example.test_app.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.test_app.ui.theme.AppTheme

/** Shown by the charts when there is nothing to draw. */
const val ADMIN_CHART_EMPTY_TEXT = "No reports yet. Use Settings > Generate demo history to try the dashboard."

/** Height of the vertical bar chart as a multiple of the waveform height token. */
private const val CHART_HEIGHT_FACTOR = 1.5f

/** Share of the track width a bar of the maximum value fills in [HorizontalBars]. */
private const val FULL_FRACTION = 1f

/** Smallest visible bar fraction for a non-zero value in [HorizontalBars]. */
private const val MIN_FRACTION = 0.02f

/** Colour of a severity word (Critical/High error, Medium warning, Low success, anything else muted). */
@Composable
fun adminSeverityColor(severity: String?): Color {
    val c = AppTheme.colors
    return when (severity?.lowercase()) {
        "critical", "high" -> c.error
        "medium" -> c.warning
        "low" -> c.success
        else -> c.mutedText
    }
}

/** Muted one-line empty state used by charts and lists. */
@Composable
fun AdminEmptyText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppTheme.type.body, color = AppTheme.colors.mutedText, modifier = modifier.fillMaxWidth())
}

/**
 * Vertical bar chart drawn on a Compose Canvas: a baseline, one bar per [values] entry (label under the bar, value above it when the bar slot
 * is wide enough). [description] is the accessibility summary. Empty or all-zero data shows [emptyText]. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun BarChart(
    values: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    barColor: Color = AppTheme.colors.primary,
    description: String = values.joinToString(", ") { "${it.first}: ${it.second}" },
    emptyText: String = ADMIN_CHART_EMPTY_TEXT,
) {
    if (values.isEmpty() || values.all { it.second == 0 }) {
        AdminEmptyText(emptyText, modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val c = AppTheme.colors
    val textStyle = AppTheme.type.caption.copy(color = c.mutedText)
    val valueStyle = AppTheme.type.caption.copy(color = c.onSurface)
    val gap = AppTheme.spacing.xs
    val radius = AppTheme.radii.sm / 2
    val hairline = AppTheme.sizes.borderWidth
    val labelPad = AppTheme.spacing.xs
    Canvas(
        modifier.fillMaxWidth().height(AppTheme.sizes.waveformHeight * CHART_HEIGHT_FACTOR).semantics { contentDescription = description },
    ) {
        val max = values.maxOf { it.second }.coerceAtLeast(1)
        val lineH = measurer.measure("0", textStyle).size.height
        val top = lineH.toFloat()
        val baseY = size.height - lineH - labelPad.toPx()
        val slot = size.width / values.size
        val barW = (slot - gap.toPx()).coerceAtLeast(1f)
        drawLine(c.border, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = hairline.toPx())
        values.forEachIndexed { i, (label, v) ->
            val x = i * slot + (slot - barW) / 2f
            val h = (baseY - top) * v / max
            if (v > 0) drawRoundRect(barColor, Offset(x, baseY - h), Size(barW, h), CornerRadius(radius.toPx()))
            val lbl = measurer.measure(label, textStyle, maxLines = 1)
            if (lbl.size.width <= slot) drawText(lbl, topLeft = Offset(i * slot + (slot - lbl.size.width) / 2f, baseY + labelPad.toPx()))
            if (v > 0) {
                val num = measurer.measure(v.toString(), valueStyle, maxLines = 1)
                if (num.size.width <= slot) drawText(num, topLeft = Offset(i * slot + (slot - num.size.width) / 2f, baseY - h - lineH))
            }
        }
    }
}

/**
 * Horizontal bars (label left, bar, value right) for the first [maxItems] of [values], each bar coloured from the graph palette. Plain rows,
 * so long labels wrap-safe (ellipsis) and nothing overflows. Empty data shows [emptyText].
 */
@Composable
fun HorizontalBars(
    values: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    maxItems: Int = 8,
    emptyText: String = ADMIN_CHART_EMPTY_TEXT,
) {
    val shown = values.take(maxItems)
    if (shown.isEmpty()) {
        AdminEmptyText(emptyText, modifier)
        return
    }
    val c = AppTheme.colors
    val max = shown.maxOf { it.second }.coerceAtLeast(1)
    val summary = shown.joinToString(", ") { "${it.first}: ${it.second}" }
    Column(modifier.fillMaxWidth().semantics { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        shown.forEachIndexed { i, (label, v) ->
            Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = AppTheme.type.label, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(LABEL_WEIGHT))
                Box(Modifier.weight(BAR_WEIGHT).height(AppTheme.spacing.md).clip(RoundedCornerShape(AppTheme.radii.sm)).background(c.surfaceElevated)) {
                    val fraction = if (v == 0) 0f else (v.toFloat() / max * FULL_FRACTION).coerceAtLeast(MIN_FRACTION)
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(AppTheme.radii.sm)).background(c.graphPalette[i % c.graphPalette.size]))
                }
                Text(
                    v.toString(), style = AppTheme.type.label, color = c.onSurface, textAlign = TextAlign.End, maxLines = 1,
                    modifier = Modifier.widthIn(min = AppTheme.spacing.xl).padding(start = AppTheme.spacing.xs),
                )
            }
        }
    }
}

private const val LABEL_WEIGHT = 0.38f
private const val BAR_WEIGHT = 0.52f
