package com.example.test_app.ui.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.test_app.flow.ProcessingPhase
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.StepRow
import com.example.test_app.ui.components.StepStatus
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/**
 * The step list (STT, LLM, JSON, validation, form) and, once ready, the "What I heard" transcript card.
 * Used by: [ProcessingScreen] (answer passes) and HomeScreen (first pass, in the same frame as the mic).
 */
@Composable
fun ProcessingSteps(phase: ProcessingPhase, transcript: String?, modifier: Modifier = Modifier) {
    val steps = listOf(
        ProcessingPhase.STT to "Turning speech into text",
        ProcessingPhase.LLM to "Understanding what happened",
        ProcessingPhase.JSON to "Structuring the report",
        ProcessingPhase.VALIDATION to "Checking required details",
        ProcessingPhase.FORM to "Filling in the form",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
        SurfaceCard {
            steps.forEach { (p, label) ->
                StepRow(
                    label = label,
                    detail = null,
                    status = when {
                        p.ordinal < phase.ordinal -> StepStatus.DONE
                        p == phase -> StepStatus.ACTIVE
                        else -> StepStatus.PENDING
                    },
                )
            }
        }
        if (transcript != null) {
            SurfaceCard {
                Text("What I heard", style = AppTheme.type.label, color = AppTheme.colors.primary)
                Text("\u201C$transcript\u201D", style = AppTheme.type.bodyLarge, color = AppTheme.colors.onSurface, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Full-screen Process block, used for clarification-answer passes (the first pass is shown on Home).
 * Entry: after an answer is recorded. Exit: automatic. See `doc/UI_FLOW.md` section Processing.
 *
 * @param roundLabel e.g. "Processing your answer".
 */
@Composable
fun ProcessingScreen(phase: ProcessingPhase, transcript: String?, roundLabel: String?, onCancel: () -> Unit) {
    ScreenFrame(title = roundLabel ?: "Working on it", leadingIcon = Icons.Filled.Close, leadingDescription = "Cancel", onLeading = onCancel) {
        ProcessingSteps(
            phase, transcript,
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = AppTheme.spacing.md),
        )
    }
}
