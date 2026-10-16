package com.example.test_app.ai

import com.example.test_app.dialogue.RephrasePrompt
import com.example.test_app.dialogue.RephraseRequest
import com.example.test_app.domain.service.ImageHint
import kotlinx.serialization.json.JsonPrimitive

/**
 * Prompts of the on-device models as chat-format-neutral [ChatPrompt]s. The engine renders them: ChatML for Qwen3 (Genie),
 * the Gemma 4 turn format for Gemma (`ChatFormat`). `ChatPrompt.render(ChatFormat.CHATML)` is byte for byte the Qwen text of the
 * interim prototype prompts (hand-tuned from the device eval; `PromptsRenderTest` pins it); the Gemma rendering is pinned against the
 * model's own jinja template. The team's real `IncidentRules.kt` will replace the extraction/update text later; keep the entry
 * points stable. Token budget (hard caps 2000 / 1500 / 500, targets 1200 / 800 / 300) is checked by `PromptBudgetTest` and
 * `tools/count_tokens.py`. See `doc/PROMPTS.md`, `doc/DIALOGUE.md` ("Extraction contract") and `doc/MODEL_INTEGRATION.md`.
 *
 * Extraction and update prompts END with a forced assistant prefix ([EXTRACTION_PREFIX] / [UPDATE_PREFIX]) so the model starts
 * straight with the JSON (Qwen3 never opens `<think>`; Gemma starts no thought channel); the model's reply continues after the
 * prefix ([LlmOutputParser.parseObject] re-adds it).
 */
object Prompts {
    /** Last characters of [extraction]; the model continues right after it. */
    const val EXTRACTION_PREFIX = "{\"incident_type\":"

    /** Last characters of [update]; the model continues right after it. */
    const val UPDATE_PREFIX = "{"

    /** Last characters of [multimodalExtraction]; the model continues right after it. */
    const val MULTIMODAL_PREFIX = "{\"language\":"

    /** Last characters of [imageDescription]; the model continues right after it. */
    const val IMAGE_PREFIX = "{\"description\":"

    /**
     * The speech-recognition instruction (language-agnostic, ADR-051): the model names the language it hears and writes the transcript
     * verbatim in that language's own script, in a fixed two-line shape that [com.example.test_app.ai.llama.GemmaText.parseTranscriptReply] reads.
     * Replaces the guide's "Transcribe this audio exactly" line, which named no language and made Gemma default to Devanagari.
     */
    const val TRANSCRIBE_INSTRUCTION = """Listen to the audio and identify the language actually spoken. Transcribe it verbatim in that language, using that language's own native script. Do not translate. Never convert the speech to Devanagari or Hindi unless the speaker really speaks Hindi. Keep code-mixed words in the language and script they were spoken in.
Reply in exactly two lines:
Language: <English name of the language, for example Tamil, Assamese, Odia, Bengali, English>
Transcript: <verbatim text>"""

    /** Longest value copied into CURRENT_FORM (keeps the update prompt small). */
    private const val MAX_FORM_VALUE_CHARS = 160

    /** Longest photo description copied into the extraction prompt. */
    private const val MAX_HINT_CHARS = 300

    private const val EXTRACTION_RULES = """Extract incident report fields as JSON. Use null for anything not said. Never invent details.
Copy location, datetime and persons_involved using the user's exact words.
The speaker may use any Indian language (for example Tamil, Assamese, Odia, Bengali, Telugu, Hindi), possibly mixed with English; understand it and write the English report: write EVERY value in ENGLISH. Translate descriptions; write place names and acronyms in Latin letters and keep proper nouns and codes (KRB, CVIT, Vindhya A2).
incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident. If a person got hurt, use Injury.
severity: Critical (deaths, many injured, emergency needed), High (anyone hurt, fire, gas), Medium (contained or isolated), Low (minor), or null if no incident."""

    private const val PHOTO_RULE =
        "PHOTO (optional) describes an attached photo. Use it only for facts the user did not say; the user's words win. If the user said nothing, base the report on PHOTO alone."

    private val EXTRACTION_EXAMPLES = listOf(
        ChatMessage(ChatRole.USER, "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."),
        ChatMessage(
            ChatRole.ASSISTANT,
            "{\"incident_type\": \"Water Leak\", \"severity\": \"Medium\", \"location\": \"Conference Room 2\", \"datetime\": \"around 10 AM\", \"persons_involved\": null, \"actions_taken\": \"Maintenance isolated the area\", \"description\": \"Water leak near Conference Room 2, area isolated.\"}",
        ),
        ChatMessage(ChatRole.USER, "uh a worker cut his hand on the machine in the workshop just now"),
        ChatMessage(
            ChatRole.ASSISTANT,
            "{\"incident_type\": \"Injury\", \"severity\": \"High\", \"location\": \"workshop\", \"datetime\": \"just now\", \"persons_involved\": \"a worker\", \"actions_taken\": null, \"description\": \"A worker cut his hand on a machine in the workshop.\"}",
        ),
        ChatMessage(ChatRole.USER, "Good morning, is this working?"),
        ChatMessage(
            ChatRole.ASSISTANT,
            "{\"incident_type\": null, \"severity\": null, \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null, \"description\": null}",
        ),
    )

    private val UPDATE_SYSTEM = """You update an incident report form. You get CURRENT_FORM, the field the app ASKED about, and what the USER said.
Return JSON with ONLY the fields the user gave or corrected, in English. The user may answer in any Indian language (for example Tamil, Assamese, Odia, Bengali, Telugu, Hindi), possibly mixed: understand it, translate, and write place names in Latin letters. Never repeat unchanged fields. Never invent details. If the user gave nothing useful, return {}."""

    private val UPDATE_EXAMPLES = listOf(
        ChatMessage(
            ChatRole.USER,
            "CURRENT_FORM: {\"incident_type\": \"Water Leak\", \"location\": null, \"datetime\": \"around 10 AM\"}\nASKED: location\nUSER: uh it's in block B second floor near the lift",
        ),
        ChatMessage(ChatRole.ASSISTANT, "{\"location\": \"block B second floor near the lift\"}"),
        ChatMessage(
            ChatRole.USER,
            "CURRENT_FORM: {\"incident_type\": \"Fire\", \"location\": \"Room 2\", \"datetime\": null}\nASKED: datetime\nUSER: actually it was room 3 not room 2, and it started like 5 minutes ago",
        ),
        ChatMessage(ChatRole.ASSISTANT, "{\"location\": \"room 3\", \"datetime\": \"5 minutes ago\"}"),
    )

    /**
     * First-pass extraction prompt for the user's narration. [imageHints] (descriptions of attached photos, Gemma only) are added
     * as a `PHOTO:` line and one rule; without hints the prompt is exactly the prototype text.
     */
    fun extraction(transcript: String, imageHints: List<ImageHint> = emptyList()): ChatPrompt {
        val hints = imageHints.map { sanitize(it.description).take(MAX_HINT_CHARS) }.filter { it.isNotEmpty() }
        val system = if (hints.isEmpty()) EXTRACTION_RULES else EXTRACTION_RULES + "\n" + PHOTO_RULE
        val user = (sanitize(transcript) + hints.joinToString("") { "\nPHOTO: $it" }).trimStart()
        return ChatPrompt(
            listOf(ChatMessage(ChatRole.SYSTEM, system)) + EXTRACTION_EXAMPLES + ChatMessage(ChatRole.USER, user),
            EXTRACTION_PREFIX,
        )
    }

    /**
     * Update prompt for one follow-up answer. [currentForm] uses the model's key names (incident_type, severity,
     * location, datetime, persons_involved, actions_taken, description); null/blank entries are omitted.
     * [asked] is the model key of the question (see [askedKey]). Conversation is NOT resent.
     */
    fun update(currentForm: Map<String, String?>, asked: String, user: String): ChatPrompt {
        val form = currentForm.entries
            .filter { !it.value.isNullOrBlank() }
            .joinToString(", ", "{", "}") { (k, v) ->
                JsonPrimitive(sanitize(k)).toString() + ": " + JsonPrimitive(sanitize(v.orEmpty()).take(MAX_FORM_VALUE_CHARS)).toString()
            }
        return ChatPrompt(
            listOf(ChatMessage(ChatRole.SYSTEM, UPDATE_SYSTEM)) + UPDATE_EXAMPLES +
                ChatMessage(ChatRole.USER, "CURRENT_FORM: " + form + "\nASKED: " + sanitize(asked) + "\nUSER: " + sanitize(user)),
            UPDATE_PREFIX,
        )
    }

    /** Model key (as used in [update]) for a dialogue field key; "none" for a free correction. */
    fun askedKey(dialogueField: String?): String = when (dialogueField) {
        "location" -> "location"
        "time" -> "datetime"
        "immediate_actions" -> "actions_taken"
        "people_involved" -> "persons_involved"
        "description" -> "description"
        else -> "none"
    }

    /**
     * Rephrase prompt: spec 8.2 text as the system turn and a short user turn. No assistant prefix: ChatML closes thinking with an
     * empty think block, Gemma needs nothing.
     */
    fun rephrase(req: RephraseRequest): ChatPrompt {
        val safe = req.copy(template = sanitize(req.template), transcriptSoFar = sanitize(req.transcriptSoFar))
        return ChatPrompt(
            listOf(ChatMessage(ChatRole.SYSTEM, RephrasePrompt.render(safe)), ChatMessage(ChatRole.USER, "Write the question now.")),
        )
    }

    private const val TRANSLATE_SYSTEM = "Translate the user's message (any Indian language, possibly mixed with English) into one short plain English sentence. Write names and acronyms in Latin letters. Output only the translation."

    /**
     * Translation of a short answer in ANY non-Latin script so the Kotlin answer parsers can read it. Used once, only for a non-Latin
     * answer to `injured_person` or `injury_type` (not a re-ask).
     */
    fun translateAnswer(text: String): ChatPrompt =
        ChatPrompt(listOf(ChatMessage(ChatRole.SYSTEM, TRANSLATE_SYSTEM), ChatMessage(ChatRole.USER, sanitize(text))))

    /** Gemma speech recognition: [TRANSCRIBE_INSTRUCTION] plus the audio. The reply is `Language:` / `Transcript:` (see `GemmaText.parseTranscriptReply`). */
    fun transcription(audio: MediaPart.Audio): ChatPrompt =
        ChatPrompt(listOf(ChatMessage(ChatRole.USER, TRANSCRIBE_INSTRUCTION, listOf(audio))))

    /** The plain instruction used for forced English (the guide's original line); no `Language:` / `Transcript:` lines come back. */
    const val TRANSCRIBE_EXACT = "Transcribe this audio exactly. Output only the transcript, nothing else."

    /**
     * Gemma speech recognition with the language FORCED by the user setting "Spoken language" (ADR-054; language identification is
     * unreliable, for example Tamil was named Hindi, but a told language is transcribed well). [language] is a name from
     * `assets/dialogue/languages.json` (any name works, no per-language code). English uses [TRANSCRIBE_EXACT]. The reply is plain
     * text (parsed by `GemmaText.cleanTranscript`, not by the two-line reply parser).
     */
    fun transcriptionForced(language: String, audio: MediaPart.Audio): ChatPrompt {
        val name = sanitize(language)
        val text = if (isEnglish(name)) TRANSCRIBE_EXACT
        else "This audio is spoken in $name. Transcribe it verbatim in $name using its native script. Do not translate. Output only the transcript."
        return ChatPrompt(listOf(ChatMessage(ChatRole.USER, text, listOf(audio))))
    }

    private fun isEnglish(name: String) = name.equals("english", ignoreCase = true) || name.equals("en", ignoreCase = true)

    /** The assistant prefix of the one-call prompt: with a forced [language] the `language` key is already filled in. */
    fun multimodalPrefix(language: String? = null): String =
        if (language == null) MULTIMODAL_PREFIX else "{\"language\": " + JsonPrimitive(sanitize(language)).toString() + ", \"transcript\":"

    private const val IMAGE_INSTRUCTION = """This photo was taken to report an incident at a workplace or campus. Look for what went wrong: fire, flames, smoke, soot or burn marks, damaged electrical parts, water leaking or flooding, a burst or broken pipe, spilled liquid or foam, broken equipment, structural damage, or a person who is hurt.
Respond with ONLY a JSON object, no markdown, in exactly this shape:
{"description": "<two short sentences: first the place or setting and what is visible, then what is wrong or dangerous>", "tags": ["<up to 5 lowercase keywords>"]}"""

    /** Gemma photo analysis (a short description of the setting and the problem + tags). The photo MUST already be downscaled (`ImageScaling`). */
    fun imageDescription(image: MediaPart.Image): ChatPrompt =
        ChatPrompt(listOf(ChatMessage(ChatRole.USER, IMAGE_INSTRUCTION, listOf(image))), IMAGE_PREFIX)

    /** Last characters of [photoOnlyExtraction]; the model continues right after it. */
    const val PHOTO_ONLY_PREFIX = EXTRACTION_PREFIX

    private const val PHOTO_ONLY_RULES = """Nobody described the incident: you only get descriptions of PHOTOS. Extract incident report fields as JSON from the photos alone. Use null for anything the photos do not clearly show. Never invent names, room numbers or times.
incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident is visible. Fire, flames, smoke or burn damage: Fire (or Electrical Hazard when the damage is on electrical parts). Water leaking, dripping, spraying, overflowing or flooding (an overflowing toilet, sink or bath too): Water Leak. A person visibly hurt: Injury.
severity: judge the visible danger. Critical (flames described on a building or a large area, flames spreading, or people in danger), High (smoke, soot or burn damage, a smaller or contained fire, damaged electrical parts, a person hurt), Medium (an active leak, flooding, spill or broken equipment), Low (a small drip, a stain or minor damage), or null if no incident is visible.
location: the kind of room or area that is visible, for example office, bathroom, kitchen, laboratory, corridor, outdoors, building exterior; null when the photos do not show it (a part of a room such as a wall, ceiling or floor, or just "indoor", is not a location). persons_involved: only people clearly visible, else null. datetime and actions_taken: null.
description: one short sentence saying what the photos show and what is wrong."""

    private val PHOTO_ONLY_EXAMPLES = listOf(
        ChatMessage(ChatRole.USER, "PHOTO: A kitchen with flames and dark smoke rising from the cooker hood. A cooking pot is burning on the stove."),
        ChatMessage(
            ChatRole.ASSISTANT,
            "{\"incident_type\": \"Fire\", \"severity\": \"High\", \"location\": \"kitchen\", \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null, \"description\": \"A fire is burning on the cooker in a kitchen with dark smoke.\"}",
        ),
        ChatMessage(ChatRole.USER, "PHOTO: A basement ceiling with a cracked pipe. Water is spraying from the crack and the floor below is wet."),
        ChatMessage(
            ChatRole.ASSISTANT,
            "{\"incident_type\": \"Water Leak\", \"severity\": \"Medium\", \"location\": \"basement\", \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null, \"description\": \"Water is spraying from a cracked pipe in a basement and wetting the floor.\"}",
        ),
        ChatMessage(ChatRole.USER, "PHOTO: A corridor where a person lies on the floor next to a fallen ladder. The person looks hurt."),
        ChatMessage(
            ChatRole.ASSISTANT,
            "{\"incident_type\": \"Injury\", \"severity\": \"High\", \"location\": \"corridor\", \"datetime\": null, \"persons_involved\": \"a person\", \"actions_taken\": null, \"description\": \"A person lies hurt on the floor of a corridor next to a fallen ladder.\"}",
        ),
        ChatMessage(ChatRole.USER, "PHOTO: A tidy meeting room with a table and chairs. Nothing looks wrong."),
        ChatMessage(
            ChatRole.ASSISTANT,
            "{\"incident_type\": null, \"severity\": null, \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null, \"description\": null}",
        ),
    )

    /**
     * First-pass extraction when nobody spoke (photo-only report, ADR-053/ADR-062): the system rules say the report comes from the photos
     * alone, the user turn is the `PHOTO:` descriptions. The reply has the same 7 keys as [extraction].
     */
    fun photoOnlyExtraction(imageHints: List<ImageHint>): ChatPrompt {
        val hints = imageHints.map { sanitize(it.description).take(MAX_HINT_CHARS) }.filter { it.isNotEmpty() }
        require(hints.isNotEmpty()) { "photoOnlyExtraction needs at least one photo description" }
        return ChatPrompt(
            listOf(ChatMessage(ChatRole.SYSTEM, PHOTO_ONLY_RULES)) + PHOTO_ONLY_EXAMPLES + ChatMessage(ChatRole.USER, hints.joinToString("\n") { "PHOTO: $it" }),
            PHOTO_ONLY_PREFIX,
        )
    }

    private const val MULTIMODAL_RULES = """You write incident reports from a spoken report and/or a photo. Use null for anything not said or visible. Never invent details.
The speaker may use any Indian language (for example Tamil, Assamese, Odia, Bengali, Telugu, Hindi), possibly mixed with English. Copy location, datetime and persons_involved using the speaker's exact words, but write EVERY value except "language" and "transcript" in ENGLISH (translate; place names and acronyms in Latin letters).
incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident. If a person got hurt, use Injury.
severity: Critical (deaths, many injured, emergency needed), High (anyone hurt, fire, gas), Medium (contained or isolated), Low (minor), or null if no incident."""

    private const val MULTIMODAL_SHAPE = """{
  "language": "<English name of the language the speaker used, for example Tamil, Assamese, Odia, Bengali, English; empty string if no audio>",
  "transcript": "<what the speaker said, verbatim, in that language's own native script (never converted to Devanagari unless the speaker really speaks Hindi), not translated; empty string if no audio>",
  "image_description": "<one sentence describing what is visible; empty string if no photo>",
  "incident_type": "<one of the incident types above, or null>",
  "severity": "<Critical, High, Medium, Low, or null>",
  "location": "<where, if stated or visible, else null>",
  "datetime": "<when, if stated, else null>",
  "persons_involved": "<who, if stated, else null>",
  "actions_taken": "<actions mentioned or visible, else null>",
  "description": "<one short sentence summarising the incident, else null>"
}"""

    /**
     * ONE call: narration audio and/or photos to the 7-key extraction JSON plus `transcript` and `image_description`
     * (the guide's `bench_report.py` prompt shape with this app's keys). At least one of [audio] and [images] is required.
     * Media order = marker order: photos first, then the audio. [forcedLanguage] (user setting "Spoken language"): the user text says
     * which language is spoken and the `language` key of the assistant prefix is pre-filled ([multimodalPrefix]); null = unchanged.
     */
    fun multimodalExtraction(audio: MediaPart.Audio?, images: List<MediaPart.Image>, forcedLanguage: String? = null): ChatPrompt {
        require(audio != null || images.isNotEmpty()) { "multimodalExtraction needs audio or an image" }
        val parts = buildList {
            if (images.isNotEmpty()) add(if (images.size == 1) "take a look at the photo" else "take a look at the photos")
            if (audio != null) add("listen to the audio")
        }
        val forced = if (audio != null && forcedLanguage != null) {
            val n = sanitize(forcedLanguage)
            "The audio is spoken in $n; write the transcript verbatim in $n using its native script.\n"
        } else ""
        val user = "Please ${parts.joinToString(" and ")} and give an incident report.\n" + forced +
            "Respond with ONLY a JSON object, no markdown, in exactly this shape:\n" + MULTIMODAL_SHAPE
        return ChatPrompt(
            listOf(ChatMessage(ChatRole.SYSTEM, MULTIMODAL_RULES), ChatMessage(ChatRole.USER, user, images + listOfNotNull(audio))),
            multimodalPrefix(if (audio != null) forcedLanguage else null),
        )
    }

    private const val GROUNDED_SYSTEM = """Rewrite only APPROVED_FACTS into concise user-facing steps.
Do not add actions, causes, numbers, locations, medical guidance or safety claims.
Preserve owner roles, uncertainty and prerequisites.
Return JSON with recommendation_id and rendered_text."""

    /**
     * Post-incident analysis (ADR-056, spec section 14): the LLM only RE-PHRASES graph-approved statements. [facts] are
     * (recommendation id, canonical text) pairs; the reply must hold exactly one `{"recommendation_id", "rendered_text"}` item per id.
     * Kotlin validates the reply (`knowledgegraph/llmgrounding/LlmOutputValidator`) and falls back to the canonical text on any doubt.
     * Off by default.
     */
    fun groundedRephrase(facts: List<Pair<String, String>>): ChatPrompt {
        val list = facts.joinToString("\n") { (id, text) -> "- recommendation_id: ${sanitize(id)} | text: ${sanitize(text)}" }
        val user = "APPROVED_FACTS:\n" + list +
            "\nReturn ONLY a JSON array, no markdown, in exactly this shape with one item per recommendation_id:\n" +
            "[{\"recommendation_id\": \"<id>\", \"rendered_text\": \"<one short sentence>\"}]"
        return ChatPrompt(listOf(ChatMessage(ChatRole.SYSTEM, GROUNDED_SYSTEM), ChatMessage(ChatRole.USER, user)))
    }

    /** Chat-template tokens of Qwen (ChatML, think) and Gemma 4 (turn, channel, media, bos/eos); user text can never contain them. */
    private val SPECIAL = Regex(
        "<\\|(?:im_\\w*|endoftext)\\|?>?|</?think>|" +
            "<\\|(?:turn|channel|image|audio|think|tool\\w*|\"\\|)>|<(?:turn|channel|image|audio|tool\\w*)\\|>|" +
            "<(?:bos|eos|pad)>|<__media__>",
        RegexOption.IGNORE_CASE,
    )

    /** Removes chat-template tokens (and `<think>` tags) from user text and flattens whitespace so it cannot break the prompt format. */
    fun sanitize(text: String): String {
        var s = text
        while (true) {
            val next = SPECIAL.replace(s, "")
            if (next == s) break
            s = next
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
