package com.example.test_app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.example.test_app.ai.AiStatus
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.flow.ProcessingPhase
import com.example.test_app.ui.components.LanguageChip
import com.example.test_app.ui.components.LinkButton
import com.example.test_app.ui.components.MicButton
import com.example.test_app.ui.components.NoticeBanner
import com.example.test_app.ui.components.ProgressOrb
import com.example.test_app.ui.components.ScreenFrame
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SecondaryCircleButton
import com.example.test_app.ui.components.Thumbnail
import com.example.test_app.ui.components.Waveform
import com.example.test_app.ui.components.formatElapsed
import com.example.test_app.ui.processing.ProcessingSteps
import com.example.test_app.ui.theme.AppTheme

/** What the Home frame is showing. The mic button stays in the same spot in all four. */
enum class HomeMode {
    /** Big mic, helper text, gallery and history shortcuts. Also the "tap the mic when you're ready" hint of the dialogue. */
    IDLE,

    /** The assistant is speaking (the greeting); the mic is idle, tapping it skips the speech and listens now. */
    SPEAKING,

    /** The mic became a stop button; timer + live waveform below it. */
    RECORDING,

    /** The mic became a spinner; step list + transcript below it. */
    PROCESSING,
}

/**
 * Home frame for the voice path. One screen, four modes (see [HomeMode]): the dialogue greets (SPEAKING), listens
 * (RECORDING: the mic is a stop button with a waveform below) and processes the first narration (PROCESSING) in place.
 * [caption] replaces the helper text (greeting, or the 10 s "Tap the mic when you're ready" hint). [aiStatus] shows
 * "Loading models..." while the real model loads; the mic stays usable. The gear in the top-right corner opens Settings
 * (hidden while recording or processing). Long-press the title to open the developer menu. See `doc/UI_FLOW.md` section Home.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    mode: HomeMode,
    reportCount: Int,
    notice: String?,
    caption: String?,
    aiStatus: AiStatus,
    elapsedMs: Long,
    level: Float,
    phase: ProcessingPhase,
    transcript: String?,
    onDismissNotice: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    /** Gallery photos attached so far (compressed copies), shown as thumbnails above the mic. */
    photos: List<MediaItem>,
    /** Picked photos are being compressed: the mic waits. */
    importingPhotos: Boolean,
    /** A model can describe photos (Gemma, mock): "Use photos only" is offered (speaking is optional with photos). */
    canDescribePhotos: Boolean,
    /** Opens the system photo picker. */
    onGallery: () -> Unit,
    onClearPhotos: () -> Unit,
    onPhotoOnly: () -> Unit,
    /** The "Spoken language" setting for the chip under the mic text; null hides the chip (only the Gemma speech model uses it). */
    spokenLanguage: String?,
    onLanguage: () -> Unit,
    onHistory: () -> Unit,
    onOpenDevMenu: () -> Unit,
    /** Opens the Settings screen; null (icon hidden) while a report is being recorded or processed. */
    onSettings: (() -> Unit)? = null,
    /** Opens the admin dashboard (PIN gate); null (icon hidden) while a report is being recorded or processed. */
    onAdmin: (() -> Unit)? = null,
) {
    val s = AppTheme.spacing
    val c = AppTheme.colors
    ScreenFrame(title = null) {
        Row(Modifier.fillMaxWidth().padding(top = s.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Incident Assistant",
                style = AppTheme.type.headline,
                color = c.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AppTheme.sizes.minTouch)
                    .combinedClickable(onClick = {}, onLongClick = onOpenDevMenu, onLongClickLabel = "Developer menu")
                    .semantics { onLongClick("Developer menu") { onOpenDevMenu(); true } },
            )
            if (onAdmin != null) {
                IconButton(onClick = onAdmin, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                    Icon(Icons.Filled.AdminPanelSettings, contentDescription = "Admin dashboard", tint = c.mutedText)
                }
            }
            if (onSettings != null) {
                IconButton(onClick = onSettings, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.mutedText)
                }
            }
        }
        when (aiStatus) {
            AiStatus.Loading -> Row(
                Modifier.padding(top = s.xs), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(s.sm),
            ) {
                CircularProgressIndicator(Modifier.size(AppTheme.sizes.icon), color = c.primary, strokeWidth = AppTheme.sizes.borderWidthStrong)
                Text("Loading models\u2026 You can start talking; I'll process it when they're ready.", style = AppTheme.type.caption, color = c.mutedText)
            }
            is AiStatus.Failed -> Text(
                "Model not loaded: ${aiStatus.message} It is retried when you start a report; you can switch to Mock AI in the developer menu.",
                style = AppTheme.type.caption, color = c.error, modifier = Modifier.padding(top = s.xs),
            )
            AiStatus.Mock, AiStatus.Ready -> Unit
        }
        if (notice != null) NoticeBanner(notice, onDismissNotice, Modifier.padding(top = s.sm))

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (photos.isEmpty()) AppTheme.sizes.homeMicTopGap else s.md))
            if (photos.isNotEmpty()) {
                // the attached photos stay above the mic until the report is over (same frame in every mode)
                Row(horizontalArrangement = Arrangement.spacedBy(s.sm), modifier = Modifier.padding(bottom = s.md)) {
                    photos.forEachIndexed { i, p -> Thumbnail(p, i) }
                }
                // Speaking is optional with photos: this sends them as they are (no questions, no microphone); the mic below adds a spoken description
                if (mode == HomeMode.IDLE && caption == null && !importingPhotos && canDescribePhotos) {
                    PrimaryButton("Use photos only", onClick = onPhotoOnly, modifier = Modifier.padding(bottom = s.md))
                }
            }
            when (mode) {
                HomeMode.IDLE -> MicButton(onClick = onMic, recording = false, level = 0f, idleDescription = "Start voice report")
                HomeMode.SPEAKING -> MicButton(
                    onClick = onMic, recording = false, level = 0f, idleDescription = "Skip the spoken greeting and talk now",
                )
                HomeMode.RECORDING -> MicButton(onClick = onStop, recording = true, level = level)
                HomeMode.PROCESSING -> ProgressOrb()
            }
            Column(Modifier.fillMaxWidth().padding(top = s.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s.sm)) {
                when (mode) {
                    HomeMode.IDLE -> {
                        Text(
                            caption ?: if (photos.isEmpty()) "Tap and describe what happened." else "Or tap and describe what happened.",
                            style = AppTheme.type.bodyLarge, color = c.onBackground, textAlign = TextAlign.Center,
                        )
                        if (importingPhotos) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.sm)) {
                                CircularProgressIndicator(Modifier.size(AppTheme.sizes.icon), color = c.primary, strokeWidth = AppTheme.sizes.borderWidthStrong)
                                Text("Preparing photos\u2026", style = AppTheme.type.body, color = c.mutedText)
                            }
                        } else if (caption == null && photos.isNotEmpty()) {
                            // Speaking is optional with photos: "Use photos only" sends them as they are (no questions, no microphone), the mic adds a description
                            if (!canDescribePhotos) Text("Photos attached. Tap the mic and describe what happened.", style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center)
                            LinkButton("Remove photos", onClick = onClearPhotos)
                        }
                        if (caption == null && spokenLanguage != null) LanguageChip(spokenLanguage, onClick = onLanguage)
                        if (caption != null) LinkButton("Cancel", onClick = onCancel)
                    }
                    HomeMode.SPEAKING -> {
                        Text(caption ?: "", style = AppTheme.type.headline, color = c.onBackground, textAlign = TextAlign.Center)
                        Text("Tap the mic to talk right away.", style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center)
                        LinkButton("Cancel", onClick = onCancel)
                    }
                    HomeMode.RECORDING -> {
                        Text(formatElapsed(elapsedMs), style = AppTheme.type.timer, color = c.onBackground)
                        Waveform(level = level, active = true)
                        Text("Listening. Tap the button when you are done.", style = AppTheme.type.body, color = c.mutedText, textAlign = TextAlign.Center)
                        LinkButton("Cancel", onClick = onCancel)
                    }
                    HomeMode.PROCESSING -> {
                        ProcessingSteps(phase, transcript)
                        LinkButton("Cancel", onClick = onCancel)
                    }
                }
            }
        }

        if (mode == HomeMode.IDLE && caption == null) {
            SecondaryCircleButton(
                icon = Icons.Filled.PhotoLibrary,
                label = "Gallery",
                description = "Add photos from the gallery",
                onClick = onGallery,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(s.md))
            SecondaryButton(
                text = if (reportCount > 0) "Past reports ($reportCount)" else "Past reports",
                onClick = onHistory,
                icon = Icons.Filled.History,
            )
            Spacer(Modifier.height(s.md))
        }
    }
}
