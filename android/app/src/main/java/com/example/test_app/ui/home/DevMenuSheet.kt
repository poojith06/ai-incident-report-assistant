package com.example.test_app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.example.test_app.ai.tts.SherpaVoices
import com.example.test_app.eval.EvalInput
import com.example.test_app.eval.EvalUiState
import com.example.test_app.mock.AiBackend
import com.example.test_app.mock.DevSettings
import com.example.test_app.mock.MockScenario
import com.example.test_app.mock.SttChoice
import com.example.test_app.mock.TtsEngine
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.theme.AppTheme

/**
 * Hidden developer menu: **Mock AI / Real AI** switch (+ LLM rephrase switch), the Real AI backend (Qwen3 + Whisper | Gemma 4 E2B), the speech-to-text
 * choice (Mock / Whisper CPU stopgap / Gemma audio) with the typed-transcript stand-in for the mock, "Run eval set" (the English evaluation set through the real dialogue pipeline, `eval/README.md`),
 * the mock scenario selector and the mock delays. Opened by long-pressing the Home title.
 * See `doc/README.md` and `doc/SERVICES_AND_MOCKS.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevMenuSheet(
    settings: DevSettings,
    onChange: ((DevSettings) -> DevSettings) -> Unit,
    onDismiss: () -> Unit,
    eval: EvalUiState,
    audioEvalAvailable: Boolean,
    whisperModelPresent: Boolean,
    gemmaModelPresent: Boolean,
    effectiveStt: SttChoice,
    effectiveTts: TtsEngine,
    sherpaModelsPresent: Boolean,
    onRunEval: (EvalInput) -> Unit,
) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, contentColor = c.onSurface) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = s.md),
            verticalArrangement = Arrangement.spacedBy(s.xs),
        ) {
            Text("Developer menu", style = AppTheme.type.headline, color = c.onSurface)

            Text("AI", style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.sm))
            SwitchRow(
                title = if (settings.useRealAi) "Real AI (${settings.aiBackend.label})" else "Mock AI",
                detail = "Real AI loads the model once at app start; if it fails you can switch back here or on the error screen.",
                checked = settings.useRealAi,
            ) { v -> onChange { it.copy(useRealAi = v) } }
            Text("Real AI backend", style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.sm))
            AiBackend.entries.forEach { backend ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
                        .selectable(selected = settings.aiBackend == backend, role = Role.RadioButton) { onChange { it.copy(aiBackend = backend) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.aiBackend == backend, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.mutedText),
                    )
                    Column(Modifier.padding(start = s.sm)) {
                        Text(backend.label, style = AppTheme.type.body, color = c.onSurface)
                        Text(backend.description, style = AppTheme.type.caption, color = c.mutedText)
                    }
                }
            }
            if (settings.aiBackend == AiBackend.GEMMA) {
                SwitchRow(
                    title = "Gemma: one-call narration",
                    detail = "Audio to transcript and report in ONE model call instead of transcribe, then extract (ignored when gallery photos are attached: the photo description needs the normal extraction). Not yet measured; off by default.",
                    checked = settings.gemmaOneCall,
                ) { v -> onChange { it.copy(gemmaOneCall = v) } }
            }
            if (settings.aiBackend == AiBackend.GEMMA) {
                SwitchRow(
                    title = "Gemma: image / audio encoder on the CPU",
                    detail = "Off = Adreno GPU (3x faster, but it sometimes aborts or misreads photos on this board). On = CPU: slower (photo description about 7 s) and stable. Reloads the model.",
                    checked = settings.gemmaEncoderOnCpu,
                ) { v -> onChange { it.copy(gemmaEncoderOnCpu = v) } }
            }
            SwitchRow(
                title = "Rephrase questions with the LLM",
                detail = "Real AI only. Off = the fixed question templates are spoken verbatim.",
                checked = settings.useLlmRephrase,
            ) { v -> onChange { it.copy(useLlmRephrase = v) } }
            Text(
                "Follow-up questions, read-outs, speech rate, hands-free answers: see the Settings screen (gear on Home).",
                style = AppTheme.type.caption, color = c.mutedText,
            )
            Text("Speech-to-text", style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.sm))
            val effective = effectiveStt
            SttChoice.entries.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
                        .selectable(selected = effective == choice, role = Role.RadioButton) { onChange { it.copy(sttChoice = choice) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = effective == choice, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.mutedText),
                    )
                    Column(Modifier.padding(start = s.sm)) {
                        Text(choice.label, style = AppTheme.type.body, color = c.onSurface)
                        Text(choice.description, style = AppTheme.type.caption, color = c.mutedText)
                    }
                }
            }
            if (effectiveStt == SttChoice.WHISPER_CPU && !whisperModelPresent) {
                Text(
                    "The whisper model file is not on this device (files/whisper/ggml-small.en.bin, see doc/DEVICE_SETUP.md): Whisper CPU will fail with an error.",
                    style = AppTheme.type.caption, color = c.warning,
                )
            }
            if (settings.aiBackend == AiBackend.GEMMA && settings.useRealAi && !gemmaModelPresent) {
                Text(
                    "The Gemma files are not on this device (files/gemma/gemma-4-E2B_q4_0-it.gguf + gemma-4-E2B-it-mmproj.gguf, see doc/DEVICE_SETUP.md section 8): loading will fail with an error.",
                    style = AppTheme.type.caption, color = c.warning,
                )
            }
            SwitchRow(
                title = "Whisper: place-name prompt",
                detail = "Whisper CPU only. Biases the decoder toward conference room, server room, pantry, lab, canteen, Block B.",
                checked = settings.whisperPlacePrompt,
            ) { v -> onChange { it.copy(whisperPlacePrompt = v) } }
            Text(
                if (effectiveStt != SttChoice.MOCK) "Typed transcript is ignored while real speech recognition is selected." else "Mock speech: type what you would say; leave empty to use the scenario scripts.",
                style = AppTheme.type.caption, color = c.mutedText, modifier = Modifier.padding(top = s.xs),
            )
            OutlinedTextField(
                value = settings.typedTranscript,
                onValueChange = { v -> onChange { it.copy(typedTranscript = v) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Typed transcript: one utterance per line (narration, then answers)", style = AppTheme.type.caption) },
                textStyle = AppTheme.type.body,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.onSurface, unfocusedTextColor = c.onSurface,
                    focusedBorderColor = c.primary, unfocusedBorderColor = c.border,
                    focusedLabelColor = c.primary, unfocusedLabelColor = c.mutedText, cursorColor = c.primary,
                ),
            )

            TtsSection(settings, effectiveTts, sherpaModelsPresent, onChange)

            EvalSection(settings.useRealAi, eval, audioEvalAvailable, onRunEval)

            Text("Mock scenario", style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.md))
            MockScenario.entries.forEach { sc ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
                        .selectable(selected = settings.scenario == sc, role = Role.RadioButton) { onChange { it.copy(scenario = sc) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.scenario == sc, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.mutedText),
                    )
                    Column(Modifier.padding(start = s.sm)) {
                        Text(sc.label, style = AppTheme.type.body, color = c.onSurface)
                        Text(sc.description, style = AppTheme.type.caption, color = c.mutedText)
                    }
                }
            }
            Text("Mock delays", style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.md))
            DelaySlider("Speech-to-text", settings.sttDelayMs) { v -> onChange { it.copy(sttDelayMs = v) } }
            DelaySlider("LLM", settings.llmDelayMs) { v -> onChange { it.copy(llmDelayMs = v) } }
            DelaySlider("JSON parse", settings.jsonDelayMs) { v -> onChange { it.copy(jsonDelayMs = v) } }
            DelaySlider("Validation", settings.validationDelayMs) { v -> onChange { it.copy(validationDelayMs = v) } }
            DelaySlider("Tool call", settings.toolDelayMs) { v -> onChange { it.copy(toolDelayMs = v) } }
            Text("", modifier = Modifier.padding(bottom = s.lg))
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.type.body, color = c.onSurface)
            Text(detail, style = AppTheme.type.caption, color = c.mutedText)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary,
                uncheckedTrackColor = c.surfaceElevated, uncheckedThumbColor = c.mutedText,
            ),
        )
    }
}

@Composable
private fun DelaySlider(label: String, valueMs: Long, onValue: (Long) -> Unit) {
    val c = AppTheme.colors
    Text("$label: ${valueMs} ms", style = AppTheme.type.body, color = c.onSurface)
    Slider(
        value = valueMs.toFloat(), onValueChange = { onValue((it / 100f).toInt() * 100L) },
        valueRange = 0f..5000f,
        colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.border),
    )
}

/** "Run eval set": runs the English evaluation set through the real dialogue pipeline; shows progress and the summary. */
@Composable
private fun EvalSection(useRealAi: Boolean, eval: EvalUiState, audioEvalAvailable: Boolean, onRunEval: (EvalInput) -> Unit) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    val running = eval is EvalUiState.Running
    Text("Evaluation", style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.md))
    Text(
        "Runs the English eval set (assets/eval/cases.json) through the dialogue pipeline with the services chosen above. " +
            "Results: files/eval_runs/<time>/results.json (see eval/README.md).",
        style = AppTheme.type.caption, color = c.mutedText,
    )
    if (!useRealAi) {
        Text(
            "Mock AI is selected: extraction is scripted, so the numbers say nothing about the models.",
            style = AppTheme.type.caption, color = c.warning,
        )
    }
    PrimaryButton(text = if (running) "Running..." else "Run eval set", onClick = { if (!running) onRunEval(EvalInput.TRANSCRIPT) }, enabled = !running)
    if (audioEvalAvailable) {
        SecondaryButton(text = "Run eval set from audio", onClick = { if (!running) onRunEval(EvalInput.AUDIO) })
    } else {
        Text(
            "Audio run unavailable: select Whisper CPU above and wait until it is loaded (model file in files/whisper/). The transcript run reports WER as n/a (no ASR).",
            style = AppTheme.type.caption, color = c.mutedText,
        )
    }
    when (eval) {
        EvalUiState.Idle -> Unit
        is EvalUiState.Running -> {
            Text(
                if (eval.total == 0) "Starting..." else "Case ${minOf(eval.done + 1, eval.total)} of ${eval.total}: ${eval.currentId ?: "finishing"} (${eval.input.wire} input)",
                style = AppTheme.type.body, color = c.onSurface,
            )
            if (eval.total > 0) {
                LinearProgressIndicator(
                    progress = { eval.done.toFloat() / eval.total }, modifier = Modifier.fillMaxWidth(),
                    color = c.primary, trackColor = c.border,
                )
            }
        }
        is EvalUiState.Done -> {
            eval.lines.forEach { Text(it, style = AppTheme.type.caption, color = c.onSurface) }
            Text("Saved: ${eval.resultsPath}", style = AppTheme.type.caption, color = c.mutedText)
        }
        is EvalUiState.Failed -> Text("Eval failed: ${eval.message}", style = AppTheme.type.body, color = c.error)
    }
}

/** "Text-to-speech engine" radio group and the Rasa speaker / emotion fields (Sherpa only). */
@Composable
private fun TtsSection(
    settings: DevSettings,
    effectiveTts: TtsEngine,
    sherpaModelsPresent: Boolean,
    onChange: ((DevSettings) -> DevSettings) -> Unit,
) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    Text("Text-to-speech engine", style = AppTheme.type.title, color = c.primary, modifier = Modifier.padding(top = s.md))
    TtsEngine.entries.forEach { engine ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
                .selectable(selected = effectiveTts == engine, role = Role.RadioButton) { onChange { it.copy(ttsEngine = engine) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = effectiveTts == engine, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.mutedText),
            )
            Column(Modifier.padding(start = s.sm)) {
                Text("TTS engine: ${engine.label}", style = AppTheme.type.body, color = c.onSurface)
                Text(engine.description, style = AppTheme.type.caption, color = c.mutedText)
            }
        }
    }
    if (!sherpaModelsPresent) {
        Text(
            "No Sherpa voice files on this device (files/tts/piper-amy, files/tts/rasa, see doc/DEVICE_SETUP.md section 9): the system engine is used.",
            style = AppTheme.type.caption, color = c.warning,
        )
    }
    if (effectiveTts == TtsEngine.SHERPA) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.onSurface, unfocusedTextColor = c.onSurface,
            focusedBorderColor = c.primary, unfocusedBorderColor = c.border,
            focusedLabelColor = c.primary, unfocusedLabelColor = c.mutedText, cursorColor = c.primary,
        )
        OutlinedTextField(
            value = settings.rasaSpeaker?.toString().orEmpty(),
            onValueChange = { v ->
                val id = v.filter { it.isDigit() }.take(4).toIntOrNull()?.coerceAtMost(SherpaVoices.MAX_SPEAKER)
                onChange { it.copy(rasaSpeaker = id) }
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = AppTheme.type.body, colors = fieldColors,
            label = { Text("Rasa speaker id 0-1023 (empty = by language; speakers 0-19 are the trained ones)", style = AppTheme.type.caption) },
        )
        OutlinedTextField(
            value = settings.rasaEmotion.toString(),
            onValueChange = { v ->
                val id = v.filter { it.isDigit() }.take(2).toIntOrNull()?.coerceAtMost(SherpaVoices.MAX_EMOTION) ?: 0
                onChange { it.copy(rasaEmotion = id) }
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = AppTheme.type.body, colors = fieldColors,
            label = { Text("Rasa emotion id 0-31 (0 = assistant)", style = AppTheme.type.caption) },
        )
    }
}
