package com.example.test_app.dialogue

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.IncidentJsonCodec
import java.time.OffsetDateTime

/** Fakes shared by the severity and question-cap tests (the Spec v2 scenario tests keep their own private ones). */
internal class RecordingOut : SpeechOut {
    val texts = mutableListOf<String>()
    val languages = mutableListOf<String>()
    override suspend fun speak(text: String, language: String) {
        texts += text
        languages += language
    }
}

/** Plays a script; `null` entries and an exhausted script both mean silence. */
internal class ScriptIn(script: List<AsrResult?>) : SpeechIn {
    private val queue = ArrayDeque(script)
    override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? = queue.removeFirstOrNull()
}

/** Returns one scripted raw report JSON per call. */
internal class ScriptExtraction(script: List<String>) : IncidentExtractionService {
    private val queue = ArrayDeque(script)
    val requests = mutableListOf<ExtractionRequest>()
    override suspend fun extract(request: ExtractionRequest): String {
        requests += request
        return queue.removeFirstOrNull() ?: throw ExtractionException("script exhausted")
    }
}

/** [language] "" = the STT did not say (the dialogue falls back to the script of the text). */
internal fun said(text: String, confidence: Float = 0.9f, language: String = "") = AsrResult(text, confidence, language)

/** A raw extraction response: string / boolean / string-list values, null = empty entry. */
internal fun reportJson(transcript: String, vararg fields: Pair<String, Any?>): String = IncidentJsonCodec.encode(
    IncidentReport(
        reportId = "INC-2026-000001", status = IncidentReport.STATUS_NEEDS_CLARIFICATION,
        createdAt = "2026-10-11T09:30:00+05:30", input = ReportInput("voice", transcript),
        fields = fields.associate { (k, v) ->
            k to when (v) {
                null -> FieldEntry()
                is String -> FieldEntry(textValue(v), 0.9)
                is Boolean -> FieldEntry(boolValue(v), 0.9)
                is List<*> -> FieldEntry(listValue(v.map { it as String }), 0.9)
                else -> error("bad value $v")
            }
        },
    ),
)

internal val TEST_CLOCK: OffsetDateTime = OffsetDateTime.parse("2026-10-11T09:30:00+05:30")
