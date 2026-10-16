package com.example.test_app.domain.service

import com.example.test_app.domain.model.IncidentReport
import kotlinx.serialization.json.Json

/** Parses/serializes the Incident JSON contract (`doc/DATA_SCHEMA.md`). */
object IncidentJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    /**
     * Parses raw model output. Tolerates Markdown code fences around the JSON, as real LLMs often add them.
     * @throws ExtractionException if the text is not a valid report.
     */
    fun decode(raw: String): IncidentReport {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return try {
            json.decodeFromString(IncidentReport.serializer(), cleaned)
        } catch (e: Exception) {
            throw ExtractionException("The assistant returned something I couldn't read.", e)
        }
    }

    fun encode(report: IncidentReport): String = json.encodeToString(IncidentReport.serializer(), report)
}
