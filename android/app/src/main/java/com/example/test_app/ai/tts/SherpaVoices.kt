package com.example.test_app.ai.tts

import com.example.test_app.dialogue.LanguageDetect
import com.example.test_app.dialogue.LanguageNames
import com.example.test_app.platform.TtsConfig

/** The on-device voice models of the Sherpa TTS; [dirName] is the folder under `files/tts/` (see `doc/TTS.md`). */
enum class TtsModel(val dirName: String) {
    /** Piper VITS en_US-amy-medium (int8), English only, espeak-ng phonemes. */
    PIPER("piper-amy"),

    /** AI4Bharat VITS Rasa (int8), Indic scripts, character frontend, a speaker id and an emotion id (no language input). */
    RASA("rasa"),
}

/** Which engine speaks a text: one of the two Sherpa models, or the Android `TextToSpeech` fallback (a language the models do not cover). */
enum class VoiceEngine { PIPER, RASA, SYSTEM }

/**
 * What to say and with which voice. [chunks] are the texts handed to the model one after another (the first is audible
 * while the next is synthesised); empty for blank text. [language] is `en`, a Rasa language code (`te`, `hi`, ...) or the
 * script name of an uncovered text (SYSTEM). [speakerId] / [emotionId] only matter for RASA.
 */
data class VoicePlan(
    val engine: VoiceEngine,
    val language: String,
    val chunks: List<String>,
    val speakerId: Int = 0,
    val emotionId: Int = 0,
    /** Silence to play after this plan when another follows (see [SherpaVoices.segments]). */
    val pauseAfterMs: Int = 0,
) {
    val model: TtsModel? get() = when (engine) {
        VoiceEngine.PIPER -> TtsModel.PIPER
        VoiceEngine.RASA -> TtsModel.RASA
        VoiceEngine.SYSTEM -> null
    }
}

/** Developer-menu voice choices for the Rasa model: [speakerId] null = by language ([SherpaVoices.DEFAULT_SPEAKERS]); [emotionId] 0..31 (0 = the assistant style). */
data class RasaVoiceChoice(val speakerId: Int? = null, val emotionId: Int = 0)

/**
 * Voice routing, text preparation and speed mapping of the Sherpa TTS (pure Kotlin, unit-tested). The text decides the model:
 * Latin script -> Piper (English); one of the Indic scripts the Rasa model was trained on -> Rasa, with the speaker of the
 * language; anything else (Arabic, Cyrillic, CJK, ... and Indic languages without a Rasa voice) -> the Android TTS.
 * The Rasa model has NO language input: its 20 trained speakers each belong to one language (upstream model card), so the
 * language picks the speaker. A script alone does not name the language (Devanagari = Hindi / Marathi / Nepali / Bodo / Dogri /
 * Maithili / Sanskrit, Bengali script = Bengali / Assamese): the speech model's own language name (`languageHint`) decides
 * between the languages of the script when it names one of them, else the script's first language.
 */
object SherpaVoices {
    /** Rasa speaker ids (ai4bharat/vits_rasa_13 model card), the female voice where there is one. Hindi: see [HINDI_SPEAKER]. */
    val DEFAULT_SPEAKERS: Map<String, Int> = mapOf(
        "as" to 0, "bn" to 2, "brx" to 4, "doi" to 6, "kn" to 8, "mai" to 10, "ml" to 11, "mr" to 12, "ne" to 14,
        "pa" to 15, "sa" to 17, "ta" to 18, "te" to 19,
        "hi" to HINDI_SPEAKER,
    )

    /** Script (lowercase Unicode script name from [LanguageDetect.detect]) -> the languages written in it, default first. */
    val SCRIPT_LANGUAGES: Map<String, List<String>> get() = LanguageNames.SCRIPT_LANGUAGES

    /** Language names (as the speech model reports them, lowercase) and codes -> code. */
    val LANGUAGE_CODES: Map<String, String> get() = LanguageNames.CODES

    /** Languages the Rasa model has no trained speaker for: Android TTS unless the developer menu forces a speaker. */
    val NO_RASA_SPEAKER: Set<String> = setOf("gu", "or")

    const val MAX_SPEAKER = 1023
    const val MAX_EMOTION = 31

    /** Pause between two Rasa chunks (the model gets no sentence-final punctuation, so there is no pause of its own). */
    const val CHUNK_PAUSE_MS = 220

    /** A Rasa chunk with fewer sound-carrying code points (letters and vowel signs) than this is joined to the next one (an abbreviation such as "K.R." would synthesise as a click). */
    const val MIN_CHUNK_LETTERS = 6

    /**
     * Language code of [text] for voice choice: `en` for Latin script, else the code of the language in the script's list that
     * [languageHint] names, else the script's first language, else the script name itself (not covered).
     */
    fun languageOf(text: String, languageHint: String?): String = LanguageNames.forText(text, languageHint)

    /** The engine, speaker and prepared text chunks for [text]. [choice] is the developer-menu override of the Rasa speaker / emotion. */
    fun plan(text: String, languageHint: String? = null, choice: RasaVoiceChoice = RasaVoiceChoice()): VoicePlan {
        val language = languageOf(text, languageHint)
        if (language == "en") return VoicePlan(VoiceEngine.PIPER, "en", listOfNotNull(collapse(text.replace('\u0964', '.').replace('\u0965', '.')).ifEmpty { null }))
        val forced = choice.speakerId?.takeIf { it in 0..MAX_SPEAKER }
        val speaker = forced ?: DEFAULT_SPEAKERS[language]?.takeIf { language !in NO_RASA_SPEAKER }
        val covered = language in SCRIPT_LANGUAGES.values.flatten()
        if (!covered || speaker == null) return VoicePlan(VoiceEngine.SYSTEM, language, listOf(collapse(text)).filter { it.isNotEmpty() })
        val emotion = choice.emotionId.takeIf { it in 0..MAX_EMOTION } ?: 0
        return VoicePlan(VoiceEngine.RASA, language, rasaChunks(text), speaker, emotion)
    }

    /** Silence after a run that ends a sentence, a clause, or just switches voice inside a sentence. */
    const val SENTENCE_PAUSE_MS = 280
    const val CLAUSE_PAUSE_MS = 140
    const val SWITCH_PAUSE_MS = 40

    private enum class ScriptClass { LATIN, OTHER, NEUTRAL }

    private fun classOf(cp: Int): ScriptClass = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.LATIN -> ScriptClass.LATIN
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> ScriptClass.NEUTRAL
        else -> ScriptClass.OTHER
    }

    /**
     * Splits [text] into runs of one script: Latin text ("K R B building", English words, ids) and non-Latin text (Devanagari, Telugu, ...).
     * Digits, spaces and punctuation (script COMMON) stay with the run before them; those before the first letter go to the first run.
     * A text of one script is one run. Every run is a (trimmed, non-blank) substring of [text]; joined with spaces they give [text] back.
     */
    fun scriptRuns(text: String): List<String> {
        val runs = ArrayList<StringBuilder>()
        var current: ScriptClass? = null
        val lead = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val cls = classOf(cp)
            if (cls != ScriptClass.NEUTRAL && cls != current) {
                runs += StringBuilder().also { it.append(lead); lead.setLength(0) }
                current = cls
            }
            if (runs.isEmpty()) lead.appendCodePoint(cp) else runs.last().appendCodePoint(cp)
            i += n
        }
        if (runs.isEmpty()) return listOf(text).filter { it.isNotBlank() }
        return runs.map { it.toString() }.filter { it.isNotBlank() }
    }

    /**
     * The voice plans of an utterance, one per script run, spoken one after another ([VoicePlan.pauseAfterMs] between them). Mixed text such
     * as a Hindi sentence with an English place name ("KRB building" inside Devanagari) gets the Hindi voice for the Devanagari runs and
     * Piper for the Latin runs; [languageHint] names the language of the non-Latin runs. A plan can be SYSTEM (a script without a voice):
     * the caller then hands the whole utterance to the fallback engine.
     */
    fun segments(text: String, languageHint: String? = null, choice: RasaVoiceChoice = RasaVoiceChoice()): List<VoicePlan> {
        val runs = scriptRuns(text)
        val plans = ArrayList<VoicePlan>()
        for ((i, run) in runs.withIndex()) {
            val p = plan(run, languageHint, choice)
            if (p.chunks.isEmpty()) continue
            val last = run.trimEnd().lastOrNull()
            val pause = when {
                i == runs.lastIndex -> 0
                last != null && isEnder(last) -> SENTENCE_PAUSE_MS
                last != null && last in ",;:" -> CLAUSE_PAUSE_MS
                else -> SWITCH_PAUSE_MS
            }
            plans += p.copy(pauseAfterMs = pause)
        }
        return plans
    }

    /** Linear resampling of mono float audio (used to bring Piper's 22.05 kHz and Rasa's 24 kHz to the one rate of an AudioTrack). */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val n = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until n) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = samples[i0] * (1f - frac) + samples[i1] * frac
        }
        return out
    }

    /** Whitespace runs (incl. newlines) -> one space, trimmed. */
    fun collapse(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun isEnder(c: Char) = c == '.' || c == '?' || c == '!' || c == '\u0964' || c == '\u0965' || c == '\u2026'

    private const val CLOSERS = "\"')]\u201D\u2019"

    private fun isEdgePunct(c: Char) = isEnder(c) || c in ",;:-\u2013\u2014\"'()[]\u201C\u201D\u2018\u2019" || c.isWhitespace()

    /** Letters and combining vowel signs / viramas: the length of an Indic word in code points that carry sound. */
    private fun letters(s: String): Int = s.count {
        it.isLetter() || Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt()
    }

    /**
     * Rasa text preparation (the model card's note: sherpa splits on `.` `?` `!` and a trailing short "sentence" may synthesise as
     * noise). Sentences end at an ender followed by whitespace or the end; an ender inside a word ("K.R.", "3.5") is dropped and never
     * splits; sentence-final and edge punctuation is stripped from every chunk, so sherpa never sees one; a chunk with fewer than
     * [MIN_CHUNK_LETTERS] letters is joined to the next chunk. Commas inside a chunk stay.
     */
    fun rasaChunks(text: String): List<String> {
        val flat = collapse(text)
        val raw = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < flat.length) {
            val ch = flat[i]
            if (!isEnder(ch)) {
                cur.append(ch); i++; continue
            }
            var j = i
            while (j < flat.length && isEnder(flat[j])) j++
            var k = j // closing quotes / brackets may follow the ender: ... done!" Next
            while (k < flat.length && flat[k] in CLOSERS) k++
            val boundary = k >= flat.length || flat[k].isWhitespace()
            if (boundary) {
                raw += cur.toString(); cur.setLength(0)
                i = k
            } else {
                if (i > 0 && flat[i - 1].isDigit() && flat[j].isDigit()) cur.append(' ')
                i = j
            }
        }
        raw += cur.toString()
        val chunks = raw.map { it.trim { c -> isEdgePunct(c) } }.filter { letters(it) > 0 }
        val merged = ArrayList<String>()
        var carry = ""
        for ((index, chunk) in chunks.withIndex()) {
            val joined = if (carry.isEmpty()) chunk else "$carry $chunk"
            if (letters(joined) < MIN_CHUNK_LETTERS && index < chunks.lastIndex) carry = joined else { merged += joined; carry = "" }
        }
        return merged
    }

    /** Sherpa `speed` (higher = faster) from the user's speech rate: the rate within [TtsConfig.MIN_RATE]..[TtsConfig.MAX_RATE]; a broken value is 1.0. */
    fun speed(rate: Float): Float = if (rate.isNaN() || rate <= 0f) 1.0f else rate.coerceIn(TtsConfig.MIN_RATE, TtsConfig.MAX_RATE)

    /**
     * Hindi has no speaker of its own; the Marathi female voice (Devanagari, speaker 12) read the test sentence back through Gemma STT
     * word for word (match 1.00; speakers 6, 10, 14 as well, 4 and 17 were worse), see `doc/TTS.md`. Gujarati and Odia have no usable
     * speaker at all (word match at most 0.24 with five or six borrowed speakers each), hence [NO_RASA_SPEAKER].
     */
    private const val HINDI_SPEAKER = 12
}
