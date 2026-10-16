package com.example.test_app.ui.clarification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.example.test_app.domain.model.ClarificationQuestion
import com.example.test_app.ui.components.MicButton
import com.example.test_app.ui.components.NoticeBanner
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.TonalButton
import com.example.test_app.ui.components.Waveform
import com.example.test_app.ui.components.formatElapsed
import com.example.test_app.ui.theme.AppTheme

/**
 * "Add more by voice" from the Review screen: the prompt is shown (not spoken), the microphone starts by itself and
 * stops on a pause after speech (or on tap). "Skip and fill manually" returns to the form. The follow-up questions of a
 * new report are NOT here any more: they belong to the dialogue (`ui/dialogue/DialogueScreen.kt`).
 * See `doc/UI_FLOW.md` section Add more.
 */
@Composable
fun ClarificationScreen(
    question: ClarificationQuestion,
    recording: Boolean,
    level: Float,
    elapsedMs: Long,
    notice: String?,
    onDismissNotice: () -> Unit,
    onMic: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val s = AppTheme.spacing
    val c = AppTheme.colors
    ScreenFrame(title = "Add more", leadingIcon = Icons.Filled.Close, leadingDescription = "Cancel report", onLeading = onCancel) {
        if (notice != null) NoticeBanner(notice, onDismissNotice)
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(question.text, style = AppTheme.type.headline, color = c.onBackground, textAlign = TextAlign.Center)
            MicButton(
                onClick = onMic, recording = recording, level = level, modifier = Modifier.padding(top = s.md),
                idleDescription = "Start recording", recordingDescription = "Stop and send",
            )
            if (recording) {
                Text(formatElapsed(elapsedMs), style = AppTheme.type.timer, color = c.onBackground)
                Waveform(level = level, active = true, modifier = Modifier.padding(top = s.sm))
                Text("Listening. Just talk; I stop when you pause.", style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center)
            }
        }
        TonalButton(text = "Skip and fill manually", onClick = onSkip)
        Text("", modifier = Modifier.padding(bottom = s.md))
    }
}
