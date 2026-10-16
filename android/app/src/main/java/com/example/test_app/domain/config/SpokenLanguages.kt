package com.example.test_app.domain.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The list behind Settings > "Spoken language" and the Home language chip: names only, read from `assets/dialogue/languages.json`
 * (data, editable without code; no per-language code anywhere). Pure Kotlin so the JVM tests read the same file from disk.
 * See `doc/DIALOGUE.md` and `doc/UI_FLOW.md`.
 */
object SpokenLanguages {
    /** The names in the file (`{"languages": [...]}`), trimmed, blanks and duplicates (ignoring case) dropped; throws [IllegalArgumentException] for a malformed file. */
    fun parse(text: String): List<String> {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("languages.json is not valid JSON", e)
        }
        val array = root["languages"] as? JsonArray ?: throw IllegalArgumentException("languages.json has no \"languages\" array")
        val names = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim() ?: throw IllegalArgumentException("languages.json: every entry must be a string") }
            .filter { it.isNotEmpty() }
        require(names.isNotEmpty()) { "languages.json lists no language" }
        require(names.none { it.equals(UserSettings.SPOKEN_AUTO, ignoreCase = true) }) { "languages.json must not list \"auto\" (it is built in)" }
        return names.distinctBy { it.lowercase() }
    }

    /** What to show for a stored setting value: "Auto-detect" or the language name. */
    fun label(spokenLanguage: String): String = if (spokenLanguage.isBlank() || spokenLanguage.equals(UserSettings.SPOKEN_AUTO, ignoreCase = true)) "Auto-detect" else spokenLanguage
}
