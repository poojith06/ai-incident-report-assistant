package com.example.test_app.ui.admin.graph

import androidx.compose.ui.graphics.Color
import com.example.test_app.knowledgegraph.admin.graphview.GraphKinds
import com.example.test_app.knowledgegraph.model.Relation

/*
 * Colour and text conventions shared by the graph canvas, the legend, the filter chips and the node sheet, so all of them agree
 * (the legend colour of a kind is the colour of its nodes). The palette itself is AppTheme.colors.graphPalette (12 colours).
 * See doc/ADMIN_DASHBOARD.md.
 */

/** Position of [kind] in the legend order [GraphKinds.ALL]; unknown kinds come after the known ones. */
internal fun kindIndex(kind: String): Int {
    val i = GraphKinds.ALL.indexOf(kind)
    return if (i < 0) GraphKinds.ALL.size else i
}

/** Colour of a node kind: its legend index modulo the palette size. */
internal fun kindColor(palette: List<Color>, kind: String): Color = palette[kindIndex(kind) % palette.size]

/** Colour of a relation: its ordinal modulo the palette size. */
internal fun relationColor(palette: List<Color>, relation: Relation): Color = palette[relation.ordinal % palette.size]

/** `TEMPORARY_ARRANGEMENT` -> `Temporary arrangement`. */
internal fun humanize(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Longest label drawn on the canvas. */
internal const val MAX_LABEL_CHARS = 24

/** Label for the canvas: trimmed to [MAX_LABEL_CHARS] characters with an ellipsis. */
internal fun shortLabel(text: String): String {
    val t = text.trim().replace('\n', ' ')
    return if (t.length <= MAX_LABEL_CHARS) t else t.take(MAX_LABEL_CHARS - 1).trimEnd() + "\u2026"
}
