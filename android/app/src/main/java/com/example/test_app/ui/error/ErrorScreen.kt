package com.example.test_app.ui.error

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.test_app.flow.FlowError
import com.example.test_app.flow.FlowState
import com.example.test_app.ui.components.CenteredMessage
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.theme.AppTheme

/**
 * Friendly error screen with a retry path for every [FlowError]. Permission errors offer "Allow" (re-request)
 * and "Open settings". Errors after a report exists also offer "Fill in manually"; model errors in Real AI mode offer "Switch to Mock AI". See `doc/UI_FLOW.md` section Error.
 */
@Composable
fun ErrorScreen(
    error: FlowState.Error,
    onRetry: () -> Unit,
    onBackToForm: () -> Unit,
    onCancel: () -> Unit,
    /** Shown for model failures while Real AI is selected: switches to the mock AI so the demo still works. */
    onSwitchToMock: (() -> Unit)? = null,
    /** Shown for speech-to-text failures while Whisper CPU is selected: switches the speech-to-text to the mock. */
    onSwitchToMockStt: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val (title, body) = messages(error)
    val isPermission = error.kind == FlowError.MIC_PERMISSION_DENIED
    ScreenFrame(title = null) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CenteredMessage(title, body)
        }
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), modifier = Modifier.padding(bottom = AppTheme.spacing.md)) {
            PrimaryButton(if (isPermission) "Allow access" else "Try again", onClick = onRetry)
            if (isPermission) {
                SecondaryButton("Open app settings", onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
            }
            if (onSwitchToMock != null && (error.kind == FlowError.MODEL_FAILURE || error.kind == FlowError.EXTRACTION_FAILURE)) {
                SecondaryButton("Switch to Mock AI", onClick = onSwitchToMock)
            }
            if (onSwitchToMockStt != null && error.kind == FlowError.STT_FAILURE) {
                SecondaryButton("Switch to Mock speech", onClick = onSwitchToMockStt)
            }
            if (error.backState != null) SecondaryButton("Fill in manually", onClick = onBackToForm)
            SecondaryButton(if (error.backState != null && error.kind == FlowError.SUBMIT_FAILURE) "Discard report" else "Cancel", onClick = onCancel)
        }
    }
}

private fun messages(e: FlowState.Error): Pair<String, String> = when (e.kind) {
    FlowError.MIC_PERMISSION_DENIED ->
        "I need the microphone" to "To take your report by voice I need microphone access. Audio never leaves this device."
    FlowError.RECORDING_TOO_SHORT ->
        "That was too short" to "I didn't catch enough to work with. Tap the mic and describe what happened."
    FlowError.RECORDING_FAILED ->
        "The microphone didn't start" to (e.detail ?: "Something went wrong while recording. Please try again.")
    FlowError.STT_FAILURE ->
        "I couldn't understand the audio" to (e.detail ?: "Speech recognition failed.") + " Please try again."
    FlowError.EXTRACTION_FAILURE ->
        "I couldn't process that" to (e.detail ?: "Something went wrong while reading your report.") + " Please try again."
    FlowError.MODEL_FAILURE ->
        "The on-device model isn't ready" to (e.detail ?: "The AI model could not be loaded.") + " You can try again or switch to Mock AI."
    FlowError.SUBMIT_FAILURE ->
        "Couldn't save the report" to (e.detail ?: "Saving failed.") + " Your answers are kept; try again."
}
