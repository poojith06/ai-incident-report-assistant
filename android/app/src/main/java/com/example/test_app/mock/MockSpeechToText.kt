package com.example.test_app.mock

import com.example.test_app.dialogue.ANSWER_HINT_SAFETY
import com.example.test_app.dialogue.DialogueFields
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.ImageAnalysisService
import com.example.test_app.domain.service.ImageHint
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import com.example.test_app.domain.service.Transcript
import kotlinx.coroutines.delay

/** Marker used in [SttHint.expectedField] for the "Add more by voice" utterance. */
const val ADD_MORE_HINT = "__add_more__"

/**
 * Mock STT: ignores the audio and returns a scripted transcript after a configurable delay. When the developer menu has
 * a typed transcript script (`DevSettings.typedTranscript`) its next line is returned instead of the scenario script:
 * this is the labelled stand-in for real speech recognition while Whisper is not available (Real AI mode uses it too).
 */
class MockSpeechToText(private val settings: DevSettingsStore) : SpeechToTextService {
    override suspend fun transcribe(audio: AudioInput, hint: SttHint): Transcript {
        delay(settings.current.sttDelayMs)
        if (settings.consumeSttFailure()) {
            throw SpeechToTextException("I couldn't make out the audio.")
        }
        val control = hint.expectedField == ANSWER_HINT_SAFETY
        val typed = if (control) null else settings.nextTypedUtterance()
        return Transcript(typed ?: script(settings.current.scenario, hint.expectedField))
    }

    private fun script(scenario: MockScenario, expected: String?): String = when (expected) {
        null -> when (scenario) {
            MockScenario.INJURY_FOLLOW_UPS ->
                "Someone slipped on the wet floor near Conference Room 2 and hurt his ankle. Water is still dripping from the ceiling. " +
                    "It started around 10 AM."
            MockScenario.VAGUE_LOCATION -> "Someone slipped near the stairs."
            else -> "There was a water leak near Conference Room 2 around 10 AM. Nobody was hurt. " +
                "Maintenance has already isolated the area. Ravi from facilities saw it first. " +
                "It's coming from the ceiling above the projector, and the projector might be damaged."
        }
        // Dialogue field keys (follow-up questions).
        IncidentField.INJURY_OUTCOME.key -> "Yes, one person slipped and hurt their ankle."
        DialogueFields.INJURED_PERSON -> "It was me."
        DialogueFields.INJURY_TYPE -> "A twisted ankle."
        DialogueFields.PEOPLE_INVOLVED, IncidentField.PERSONS_INVOLVED.key -> "Priya from accounts."
        DialogueFields.IMMEDIATE_ACTIONS, IncidentField.IMMEDIATE_ACTIONS_TAKEN.key ->
            "Maintenance put up a barrier and started mopping."
        DialogueFields.LOCATION, IncidentField.INCIDENT_LOCATION.key -> "It was the main building, second floor, by the east stairwell."
        DialogueFields.DESCRIPTION, IncidentField.INCIDENT_DESCRIPTION.key -> "A person slipped on a wet step and fell."
        DialogueFields.TIME -> "Around 10 AM."
        ANSWER_HINT_SAFETY -> "Yes, I am safe."
        ADD_MORE_HINT -> "Also, the projector might be damaged, and Ravi from facilities saw it first."
        else -> "No further details."
    }
}

/** Mock image analysis: fixed tags after a short delay. */
class MockImageAnalysis : ImageAnalysisService {
    override suspend fun analyze(media: MediaItem): ImageHint {
        delay(400)
        return ImageHint(
            mediaId = media.id,
            tags = listOf("ceiling", "water stain", "indoor"),
            description = "Photo shows an indoor ceiling area with visible moisture.",
        )
    }
}
