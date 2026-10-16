package com.example.test_app.dialogue

import com.example.test_app.domain.service.SeverityCues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Keyword lists of `mode_keywords.json` (all languages merged; `needs_native_review` entries are kept but flagged in the file). */
class ModeKeywords(
    val urgent: List<String>,
    /** Subset of [urgent] that the negation guard applies to ("I don't need help", "not very urgent"). */
    val urgentNegatable: Set<String>,
    val care: List<String>,
    /** Subset of [care] about injury: these are subject to the negation guard. */
    val careInjuryRelated: Set<String>,
    val negationWords: Set<String>,
    /** Negations that FOLLOW the word they negate (Hindi "chot nahi", Telugu "noppi ledu"); same window. */
    val negationAfterWords: Set<String>,
    val negationWindowTokens: Int,
    val personalIncident: List<String>,
)

/** `relevance_cues.json`: which locations are too vague to count. */
class RelevanceCues(
    val vagueLocations: Set<String>,
)

/** `blocked_phrases.json`. */
class BlockedPhrases(val phrases: List<String>, val stems: List<String>, val whyStarters: List<String>)

/** `field_keywords.json`: validator keyword map and the answer-handling phrase lists. */
class FieldKeywords(
    val byField: Map<String, List<String>>,
    val unsurePhrases: List<String>,
    val herePhrases: List<String>,
    val pastCues: List<String>,
    val noChangePhrases: List<String>,
    val fillerWords: Set<String>,
)

/**
 * All dialogue assets, parsed and validated. Pure Kotlin: pass a reader for `assets/dialogue/<name>`
 * (Android: `AssetManager`, see `platform/DialogueAssetsLoader.kt`; JVM tests: the files on disk).
 * See `doc/DIALOGUE.md`.
 */
class DialogueAssets(
    val questionBank: QuestionBank,
    val modeKeywords: ModeKeywords,
    val blocked: BlockedPhrases,
    val fieldKeywords: FieldKeywords,
    /** Escalation cues and high-severity incident types (`severity_cues.json`), with the negation words of `mode_keywords.json`. */
    val severityCues: SeverityCues,
    /** Which questions are worth asking (`relevance_cues.json`). */
    val relevance: RelevanceCues,
    /** The read-out templates of the primary (English) bank. */
    val readout: ReadoutTemplates = ReadoutTemplates.ENGLISH,
    /** The other languages, by code (`hi`, `te`): `language_packs.json` lists them, each has `question_bank.<code>.json` and `glossary.<code>.json`. */
    val packs: Map<String, LanguagePack> = emptyMap(),
) {
    /** The pack of the primary bank (English, no glossary). */
    val primaryPack: LanguagePack = LanguagePack(questionBank.language, questionBank, readout, Glossary.NONE)

    /**
     * The pack for a language given as the speech model's name ("hindi", "Telugu"), a code (`hi`) or a script name (`devanagari`). A language
     * without a pack (Tamil, Kannada, ... or unknown / blank) gets the primary English pack: its lines are spoken in English by the English voice.
     */
    fun pack(language: String?): LanguagePack {
        val code = LanguageNames.code(language) ?: return primaryPack
        return if (code == primaryPack.code) primaryPack else packs[code] ?: primaryPack
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** [read] returns the text of `assets/dialogue/<fileName>`. Throws [DialogueAssetException] on any problem. */
        fun load(language: String = "en", read: (String) -> String): DialogueAssets = try {
            val modeKeywords = parseModeKeywords(read("mode_keywords.json"))
            val primaryText = read("question_bank.$language.json")
            DialogueAssets(
                QuestionBank.parse(primaryText),
                modeKeywords,
                parseBlocked(read("blocked_phrases.json")),
                parseFieldKeywords(read("field_keywords.json")),
                parseSeverityCues(read("severity_cues.json"), modeKeywords),
                parseRelevance(read("relevance_cues.json")),
                ReadoutTemplates.parse(primaryText),
                loadPacks(read),
            )
        } catch (e: DialogueAssetException) {
            throw e
        } catch (e: Exception) {
            throw DialogueAssetException("failed to load dialogue assets: ${e.message}", e)
        }

        /** The languages of `language_packs.json`: question bank (with the `readout` templates) and glossary each. */
        private fun loadPacks(read: (String) -> String): Map<String, LanguagePack> {
            val codes = (root(read("language_packs.json"))["packs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw DialogueAssetException("language_packs.json: missing 'packs' array")
            return codes.associateWith { code ->
                val text = read("question_bank.$code.json")
                val bank = QuestionBank.parse(text)
                if (bank.language != code) throw DialogueAssetException("question_bank.$code.json: language is '${bank.language}'")
                LanguagePack(code, bank, ReadoutTemplates.parse(text), Glossary.parse(read("glossary.$code.json")))
            }
        }

        /** Loads from a directory such as `app/src/main/assets/dialogue` (JVM tests). */
        fun fromDirectory(dir: File, language: String = "en"): DialogueAssets =
            load(language) { File(dir, it).readText(Charsets.UTF_8) }

        private fun root(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

        private fun strings(e: JsonElement?): List<String> =
            (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() } ?: emptyList()

        /** Collects `key` arrays from every language object under [parent]. */
        private fun langStrings(parent: JsonElement?, key: String): List<String> =
            (parent as? JsonObject)?.values?.flatMap { strings((it as? JsonObject)?.get(key)) } ?: emptyList()

        /** Phrase list given either as a plain array (en) or as `{needs_native_review, phrases: []}` (other languages). */
        private fun phrases(parent: JsonElement?): List<String> =
            (parent as? JsonObject)?.values?.flatMap { v ->
                if (v is JsonArray) strings(v) else strings((v as? JsonObject)?.get("phrases"))
            } ?: emptyList()

        private fun parseModeKeywords(text: String): ModeKeywords {
            val r = root(text)
            val care = r["CARE"]
            val negation = r["negation"] as? JsonObject
            val mk = ModeKeywords(
                urgent = langStrings(r["URGENT"], "keywords"),
                urgentNegatable = langStrings(r["URGENT"], "negatable").toSet(),
                care = langStrings(care, "keywords"),
                careInjuryRelated = langStrings(care, "injury_related").toSet(),
                negationWords = (negation?.values?.flatMap { strings((it as? JsonObject)?.get("words")) } ?: emptyList()).toSet(),
                negationAfterWords = (negation?.values?.flatMap { strings((it as? JsonObject)?.get("after_words")) } ?: emptyList()).toSet(),
                negationWindowTokens = negation?.get("window_tokens")?.jsonPrimitive?.int ?: 4,
                personalIncident = (r["personal_incident"] as? JsonObject)?.values?.flatMap { strings(it) } ?: emptyList(),
            )
            if (mk.urgent.isEmpty() || mk.care.isEmpty()) throw DialogueAssetException("mode_keywords.json: empty URGENT or CARE list")
            if (!mk.urgent.containsAll(mk.urgentNegatable)) throw DialogueAssetException("mode_keywords.json: urgent negatable must be a subset of URGENT keywords")
            if (!mk.care.containsAll(mk.careInjuryRelated)) throw DialogueAssetException("mode_keywords.json: injury_related must be a subset of CARE keywords")
            return mk
        }

        private fun parseSeverityCues(text: String, mode: ModeKeywords): SeverityCues {
            val r = root(text)
            val cues = SeverityCues(
                escalation = strings(r["escalation_cues"]) + langStrings(r["escalation_cues_other"], "cues"),
                highIncidentTypes = strings(r["high_incident_types"]).toSet(),
                negationWords = mode.negationWords,
                negationAfterWords = mode.negationAfterWords,
                negationWindowTokens = mode.negationWindowTokens,
            )
            if (cues.escalation.isEmpty()) throw DialogueAssetException("severity_cues.json: no escalation_cues")
            if (cues.highIncidentTypes.isEmpty()) throw DialogueAssetException("severity_cues.json: no high_incident_types")
            return cues
        }

        private fun parseRelevance(text: String): RelevanceCues {
            val r = root(text)
            val rc = RelevanceCues(
                vagueLocations = strings(r["vague_locations"]).toSet(),
            )
            if (rc.vagueLocations.isEmpty()) throw DialogueAssetException("relevance_cues.json: no vague_locations")
            return rc
        }

        private fun parseBlocked(text: String): BlockedPhrases {
            val r = root(text)
            val b = BlockedPhrases(strings(r["phrases"]), strings(r["stems"]), strings(r["why_starters"]))
            if (b.phrases.isEmpty()) throw DialogueAssetException("blocked_phrases.json: no phrases")
            return b
        }

        private fun parseFieldKeywords(text: String): FieldKeywords {
            val r = root(text)
            val byField = (r["fields"] as? JsonObject)?.mapValues { strings(it.value) } ?: emptyMap()
            val missing = DialogueFields.PRIORITY.filter { byField[it].isNullOrEmpty() }
            if (missing.isNotEmpty()) throw DialogueAssetException("field_keywords.json: no keywords for $missing")
            return FieldKeywords(
                byField = byField,
                unsurePhrases = phrases(r["unsure_phrases"]),
                herePhrases = phrases(r["here_phrases"]),
                pastCues = phrases(r["past_cues"]),
                noChangePhrases = phrases(r["no_change_phrases"]),
                fillerWords = strings(r["filler_words"]).toSet(),
            )
        }
    }
}
