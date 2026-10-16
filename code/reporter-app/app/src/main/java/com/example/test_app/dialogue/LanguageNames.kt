package com.example.test_app.dialogue

/**
 * Language names, codes and scripts in one place (pure Kotlin, data tables only). The speech model reports the language as a free-form
 * name ("hindi", "Telugu", "assamese", `en`, or a script name such as "devanagari" when it gave none), the spoken-language setting stores
 * a name from `languages.json`, banks and voices are keyed by the two- or three-letter code (`hi`, `te`, `brx`). Used to pick the language
 * pack of the dialogue ([DialogueAssets.pack]) and the voice of the TTS (`ai/tts/SherpaVoices`).
 */
object LanguageNames {
    /** Script (lowercase Unicode script name from [LanguageDetect.detect]) -> the languages written in it, the default first. */
    val SCRIPT_LANGUAGES: Map<String, List<String>> = mapOf(
        "devanagari" to listOf("hi", "mr", "ne", "brx", "doi", "mai", "sa"),
        "bengali" to listOf("bn", "as"),
        "gurmukhi" to listOf("pa"),
        "gujarati" to listOf("gu"),
        "oriya" to listOf("or"),
        "tamil" to listOf("ta"),
        "telugu" to listOf("te"),
        "kannada" to listOf("kn"),
        "malayalam" to listOf("ml"),
    )

    /** Language names (lowercase, as a speech model reports them) and codes -> code. */
    val CODES: Map<String, String> = buildMap {
        for ((code, names) in listOf(
            "en" to listOf("english"), "hi" to listOf("hindi"), "as" to listOf("assamese"), "bn" to listOf("bengali", "bangla"),
            "brx" to listOf("bodo"), "doi" to listOf("dogri"), "kn" to listOf("kannada"), "mai" to listOf("maithili"),
            "ml" to listOf("malayalam"), "mr" to listOf("marathi"), "ne" to listOf("nepali"), "pa" to listOf("punjabi", "panjabi"),
            "sa" to listOf("sanskrit"), "ta" to listOf("tamil"), "te" to listOf("telugu"), "gu" to listOf("gujarati"),
            "or" to listOf("odia", "oriya"), "ur" to listOf("urdu"),
        )) {
            put(code, code)
            names.forEach { put(it, code) }
        }
    }

    /**
     * The code of a language given as a name, a code or a script name (`devanagari` = the script's first language), else null (blank,
     * unknown, not a language of the tables). "Hindi", "hindi", "HI" and "hindi (india)" all give `hi`.
     */
    fun code(language: String?): String? {
        val name = LanguageDetect.normalizeName(language) ?: return null
        return CODES[name] ?: SCRIPT_LANGUAGES[name]?.first()
    }

    /**
     * The code of the language [text] is written in: `en` for Latin script, otherwise the language of the text's script that [hint] names
     * (a name or code), else the script's first language, else the script name itself (a script without a language table entry). A hint
     * that contradicts the script is ignored: the script is the evidence.
     */
    fun forText(text: String, hint: String?): String {
        val script = LanguageDetect.detect(text)
        if (script == "en") return "en"
        val candidates = SCRIPT_LANGUAGES[script] ?: return script
        val hinted = code(hint)
        return if (hinted != null && hinted in candidates) hinted else candidates.first()
    }
}
