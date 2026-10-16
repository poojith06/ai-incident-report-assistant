package com.example.test_app.ui.dialogue

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
import com.example.test_app.dialogue.DialogueMode
import com.example.test_app.dialogue.DialogueState
import com.example.test_app.flow.DialogueView
import com.example.test_app.platform.MicPhase
import com.example.test_app.ui.components.EmergencyBanner
import com.example.test_app.ui.components.MicButton
import com.example.test_app.ui.components.NoticeBanner
import com.example.test_app.ui.components.ProgressOrb
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.TonalButton
import com.example.test_app.ui.components.Waveform
import com.example.test_app.ui.components.formatElapsed
import com.example.test_app.ui.theme.AppTheme

/**
 * The follow-up dialogue after the first narration was understood: the line the assistant just said (a question, the
 * safety check or the read-back) as a caption, then the microphone starts by itself (hands-free): the big button is a
 * stop button with a live waveform; tapping it while the line is read skips the speech. Each field is asked once, so
 * there is no retry or "read again" control. "Skip and fill manually" jumps to the form. An URGENT mode shows the
 * emergency flag. See `doc/UI_FLOW.md` section Dialogue and `doc/DIALOGUE.md`.
 */
@Composable
fun DialogueScreen(
    view: DialogueView,
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
    val asked = view.ui.askedFields.size
    val title = when {
        asked == 0 -> "Follow-up"
        else -> "Question $asked of ${view.ui.mode.maxQuestions}"
    }
    ScreenFrame(title = title, leadingIcon = Icons.Filled.Close, leadingDescription = "Cancel report", onLeading = onCancel) {
        if (view.ui.emergency) {
            EmergencyBanner(
                "Emergency flagged. Your safety comes first. Nothing has been sent from this app.",
                Modifier.padding(top = s.sm),
            )
        }
        if (notice != null) NoticeBanner(notice, onDismissNotice)
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(view.ui.lastSpoken ?: "", style = AppTheme.type.headline, color = c.onBackground, textAlign = TextAlign.Center)
            when {
                view.mic == MicPhase.LISTENING || recording -> {
                    MicButton(
                        onClick = onMic, recording = true, level = level, modifier = Modifier.padding(top = s.md),
                        recordingDescription = "Stop and send answer",
                    )
                    Text(formatElapsed(elapsedMs), style = AppTheme.type.timer, color = c.onBackground)
                    Waveform(level = level, active = true, modifier = Modifier.padding(top = s.sm))
                    Text("Listening. Just answer; I stop when you pause.", style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center)
                }
                view.ui.state == DialogueState.WAITING_FOR_ANSWER_TAP -> {
                    // "Hands-free answers" is off: the mic does not start by itself; tap it to answer (no tap = skipped).
                    MicButton(
                        onClick = onMic, recording = false, level = 0f, modifier = Modifier.padding(top = s.md),
                        idleDescription = "Tap to answer",
                    )
                    Text("Tap the mic, then answer. If you don't, this question is skipped.", style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center)
                }
                view.speaking -> {
                    MicButton(
                        onClick = onMic, recording = false, level = 0f, modifier = Modifier.padding(top = s.md),
                        idleDescription = "Skip the spoken line and answer now",
                    )
                    Text("Speaking\u2026 I will listen right after. Tap the mic to answer now.", style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center)
                }
                else -> {
                    ProgressOrb(Modifier.padding(top = s.md))
                    Text(
                        if (view.mic == MicPhase.TRANSCRIBING) "Understanding what you said\u2026" else "Working on it\u2026",
                        style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center,
                    )
                }
            }
            if (view.ui.mode == DialogueMode.CARE) {
                Text("Every question can be skipped. You can also type on the review screen.", style = AppTheme.type.caption, color = c.mutedText, textAlign = TextAlign.Center, modifier = Modifier.padding(top = s.sm))
            }
        }
        TonalButton(text = "Skip and fill manually", onClick = onSkip)
        Text("", modifier = Modifier.padding(bottom = s.md))
    }
}
