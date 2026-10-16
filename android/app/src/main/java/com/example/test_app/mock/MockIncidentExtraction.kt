package com.example.test_app.mock

import com.example.test_app.dialogue.DialogueFields
import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.ClarificationTurn
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.Witness
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.asWitnesses
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.model.witnessValue
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.IncidentJsonCodec
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Mock LLM. First pass: returns a canned report for the active [MockScenario].
 * Later passes: merges the user's answer into the previous report with simple keyword parsing.
 * Like a real LLM it does NOT fill severity, device info or default the time: the app does
 * (`IncidentPolicies`). Output is raw JSON text. See `doc/SERVICES_AND_MOCKS.md`.
 */
class MockIncidentExtraction(private val settings: DevSettingsStore) : IncidentExtractionService {

    override suspend fun extract(request: ExtractionRequest): String {
        delay(settings.current.llmDelayMs)
        if (settings.consumeExtractionFailure()) {
            throw ExtractionException("The assistant couldn't process that.")
        }
        val previous = request.previousReport
        val report = if (previous == null) firstPass(request) else merge(request, previous)
        return IncidentJsonCodec.encode(report)
    }

    // ---- first pass ----------------------------------------------------------------------------

    private fun firstPass(request: ExtractionRequest): IncidentReport {
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val ten = now.withHour(10).withMinute(0).withSecond(0).toString()
        val hints = request.imageHints.joinToString(" ") { it.description }
        val base = canned(settings.current.scenario, ten)
        val descKey = IncidentField.INCIDENT_DESCRIPTION.key
        val description = base.getValue(descKey)
        val fields = if (hints.isNotEmpty()) {
            base + (descKey to description.copy(value = textValue(description.value.asTextOrNull() + " Photo: " + hints)))
        } else base
        return IncidentReport(
            reportId = "INC-${now.year}-" + "%06d".format((System.currentTimeMillis() / 1000) % 1_000_000),
            status = IncidentReport.STATUS_NEEDS_CLARIFICATION,
            createdAt = now.toString(),
            input = ReportInput(
                mode = request.mode.wire,
                transcript = request.originalTranscript,
                media = request.media,
            ),
            fields = fields,
            clarification = Clarification(maxRounds = request.config.maxClarificationRounds),
        )
    }

    private fun text(text: String?, c: Double) = FieldEntry(textValue(text), c, FieldSource.AI)
    private fun list(items: List<String>, c: Double) = FieldEntry(listValue(items), c, FieldSource.AI)
    private fun empty() = FieldEntry(JsonNull, 0.0, FieldSource.AI)

    private fun canned(scenario: MockScenario, tenAm: String): Map<String, FieldEntry> = when (scenario) {
        MockScenario.INJURY_FOLLOW_UPS -> mapOf(
            "incident_description" to text("Someone slipped on the wet floor near Conference Room 2 and hurt his ankle; water is still dripping from the ceiling.", 0.9),
            "injury_outcome" to text("Minor injury", 0.8),
            "incident_location" to text("Conference Room 2", 0.9),
            "date_time" to text(tenAm, 0.85),
            "immediate_actions_taken" to empty(),
            "persons_involved" to list(emptyList(), 0.7),
            "hazard_still_active" to FieldEntry(boolValue(true), 0.85, FieldSource.AI),
            "equipment_asset_involved" to list(emptyList(), 0.7),
            "witness_information" to FieldEntry(witnessValue(emptyList()), 0.5, FieldSource.AI),
            "incident_type" to text("Water Leak", 0.95),
            "severity_level" to empty(),
            "recommended_follow_up_action" to text("Inspect ceiling plumbing above Conference Room 2.", 0.7),
        )
        MockScenario.VAGUE_LOCATION -> mapOf(
            "incident_description" to text("A person slipped near the stairs.", 0.8),
            "injury_outcome" to empty(),
            "incident_location" to text("Near the stairs", 0.45),
            "date_time" to empty(),
            "immediate_actions_taken" to empty(),
            "persons_involved" to list(emptyList(), 0.6),
            "hazard_still_active" to FieldEntry(boolValue(false), 0.6, FieldSource.AI),
            "equipment_asset_involved" to list(emptyList(), 0.7),
            "witness_information" to FieldEntry(witnessValue(emptyList()), 0.5, FieldSource.AI),
            "incident_type" to text("Slip / Fall", 0.91),
            "severity_level" to empty(),
            "recommended_follow_up_action" to text("Check the stair surface for wet or slippery spots.", 0.65),
        )
        else -> mapOf( // COMPLETE, GALLERY_WITH_PHOTO, STT_FAILURE, EXTRACTION_FAILURE
            "incident_description" to text(
                "Water leaking from the ceiling near Conference Room 2, directly above the projector. " +
                    "Projector may be damaged.", 0.9,
            ),
            "injury_outcome" to text(InjuryOutcome.NONE, 0.95),
            "incident_location" to text("Conference Room 2, ceiling above projector", 0.93),
            "date_time" to text(tenAm, 0.88),
            "immediate_actions_taken" to text("Maintenance isolated the area.", 0.95),
            "persons_involved" to list(emptyList(), 0.8),
            "hazard_still_active" to FieldEntry(boolValue(false), 0.8, FieldSource.AI),
            "equipment_asset_involved" to list(listOf("Projector"), 0.85),
            "witness_information" to FieldEntry(witnessValue(listOf(Witness("Ravi", "Facilities", null))), 0.82, FieldSource.AI),
            "incident_type" to text("Water Leak", 0.96),
            "severity_level" to empty(),
            "recommended_follow_up_action" to text(
                "Inspect ceiling plumbing above Conference Room 2 and assess projector for water damage.", 0.74,
            ),
        )
    }

    // ---- merge ---------------------------------------------------------------------------------

    private fun merge(request: ExtractionRequest, previous: IncidentReport): IncidentReport {
        val answer = request.answerTranscript.orEmpty().trim()
        val question = request.answeredQuestion
        val targets = question?.targetFields.orEmpty()
        var fields = previous.fields
        val updated = mutableListOf<String>()

        if (targets.isEmpty()) { // "Add more by voice"
            fields = addMore(fields, answer, updated)
        } else {
            for (target in targets) {
                if (target in DialogueFields.PRIORITY) {
                    for ((key, entry) in dialogueAnswer(target, answer, fields)) {
                        fields = fields + (key to entry)
                        updated += key
                    }
                    continue
                }
                fields = fields + (target to FieldEntry(parse(target, answer), 0.92, FieldSource.USER_VOICE))
                updated += target
            }
        }
        val c = previous.clarification
        return previous.copy(
            fields = fields,
            clarification = c.copy(
                roundsUsed = c.roundsUsed + if (targets.isEmpty()) 0 else 1,
                maxRounds = request.config.maxClarificationRounds,
                nextQuestion = null,
                history = c.history + ClarificationTurn(
                    question = question?.text ?: "What would you like to add?",
                    answer = answer,
                    askedFields = targets,
                    updatedFields = updated,
                ),
            ),
        )
    }

    private fun addMore(
        fields: Map<String, FieldEntry>, answer: String, updated: MutableList<String>,
    ): Map<String, FieldEntry> {
        var out = fields
        val descKey = IncidentField.INCIDENT_DESCRIPTION.key
        val desc = out[descKey]?.value?.asTextOrNull().orEmpty()
        out = out + (descKey to FieldEntry(textValue("$desc $answer".trim()), 0.9, FieldSource.USER_VOICE))
        updated += descKey
        if (answer.contains("projector", ignoreCase = true)) {
            val key = IncidentField.EQUIPMENT_ASSET_INVOLVED.key
            val items = out[key]?.value?.asStringList().orEmpty()
            if ("Projector" !in items) {
                out = out + (key to FieldEntry(listValue(items + "Projector"), 0.9, FieldSource.USER_VOICE))
                updated += key
            }
        }
        Regex("""(\w+) from (\w+)""").find(answer)?.let { m ->
            val key = IncidentField.WITNESS_INFORMATION.key
            val items = out[key]?.value?.asWitnesses().orEmpty()
            val name = m.groupValues[1]
            if (items.none { it.name.equals(name, true) }) {
                val role = m.groupValues[2].replaceFirstChar { it.uppercase() }
                out = out + (key to FieldEntry(witnessValue(items + Witness(name, role, null)), 0.85, FieldSource.USER_VOICE))
                updated += key
            }
        }
        return out
    }

    /**
     * Answer to a follow-up-dialogue question (`targetFields` holds dialogue keys, see `dialogue/DialogueMode.kt`).
     * Returns the report fields it fills; an answer it cannot map fills nothing (the dialogue records `unverified`).
     */
    private fun dialogueAnswer(target: String, answer: String, fields: Map<String, FieldEntry>): Map<String, FieldEntry> {
        fun voice(v: JsonElement) = FieldEntry(v, 0.92, FieldSource.USER_VOICE)
        val key = DialogueFields.reportKey(target)
        return when (target) {
            DialogueFields.INJURED_PERSON -> {
                val existing = fields[key]?.value?.asStringList().orEmpty()
                val who = if (Regex("""\b(me|i|my|myself|mine)\b""", RegexOption.IGNORE_CASE).containsMatchIn(answer)) "Reporter"
                else cleanText(answer)
                mapOf(key to voice(listValue(existing + (who + DialogueFields.INJURED_SUFFIX))))
            }
            DialogueFields.PEOPLE_INVOLVED -> {
                val existing = fields[key]?.value?.asStringList().orEmpty()
                val names = answer.trimEnd('.').split(",", " and ").map { it.trim() }.filter { it.isNotEmpty() }
                mapOf(key to voice(listValue(existing + names)))
            }
            DialogueFields.TIME -> {
                val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""", RegexOption.IGNORE_CASE).find(answer)
                if (m == null) emptyMap() else {
                    var h = m.groupValues[1].toInt() % 12
                    if (m.groupValues[3].equals("pm", true)) h += 12
                    val t = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                        .withHour(h).withMinute(m.groupValues[2].ifEmpty { "0" }.toInt()).withSecond(0)
                    mapOf(key to voice(textValue(t.toString())))
                }
            }
            else -> mapOf(key to voice(textValue(cleanText(answer))))
        }
    }

    private fun parse(key: String, answer: String): JsonElement = when (key) {
        IncidentField.INJURY_OUTCOME.key -> textValue(parseInjury(answer))
        IncidentField.PERSONS_INVOLVED.key ->
            listValue(answer.trimEnd('.').split(",", " and ").map { it.trim() }.filter { it.isNotEmpty() })
        else -> textValue(cleanText(answer))
    }

    private fun parseInjury(answer: String): String {
        val a = answer.lowercase()
        return when {
            Regex("""\b(died|dead|fatal|fatality|killed)\b""").containsMatchIn(a) -> InjuryOutcome.FATAL
            Regex("""\b(serious|severe|ambulance|hospital|unconscious|fracture|broken)\b""").containsMatchIn(a) -> InjuryOutcome.SERIOUS
            Regex("""^(no|nope|nobody|none|nothing)\b|\bno one\b|\bnot hurt\b|\bno injur""").containsMatchIn(a) -> InjuryOutcome.NONE
            else -> InjuryOutcome.MINOR
        }
    }

    private fun cleanText(answer: String): String =
        answer.trim().trimEnd('.')
            .replace(Regex("^(it was|it happened|that was)\\s+(at\\s+)?(the\\s+)?", RegexOption.IGNORE_CASE), "")
            .replaceFirstChar { it.uppercase() }
}
