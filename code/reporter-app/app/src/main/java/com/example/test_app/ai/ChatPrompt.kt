package com.example.test_app.ai

/** Role of one chat turn. */
enum class ChatRole { SYSTEM, USER, ASSISTANT }

/** Media attached to a user turn (multimodal engines only). Raw arrays: compared by identity. */
sealed interface MediaPart {
    /** Speech: mono float PCM in [-1, 1] at 16 kHz. */
    class Audio(val pcm16k: FloatArray) : MediaPart {
        val durationMs: Long get() = pcm16k.size * 1000L / 16_000
    }

    /** Photo: tightly packed RGB bytes (`width * height * 3`), already downscaled (see `ai/llama/ImageScaling`). */
    class Image(val rgb: ByteArray, val width: Int, val height: Int) : MediaPart {
        init {
            require(width > 0 && height > 0 && rgb.size.toLong() == width.toLong() * height * 3) { "RGB image must be width*height*3 bytes" }
        }
    }
}

/** One turn. [media] belongs to a user turn; it is rendered after [text], in order. */
data class ChatMessage(val role: ChatRole, val text: String, val media: List<MediaPart> = emptyList())

/**
 * A chat-format-neutral prompt: the turns plus an optional forced start of the assistant's reply ([assistantPrefix], e.g. the
 * first characters of a JSON object). The engine renders it in the chat format of its model ([ChatFormat]); thinking is always off.
 */
data class ChatPrompt(val messages: List<ChatMessage>, val assistantPrefix: String = "") {
    /** All media of the prompt in marker order. */
    val media: List<MediaPart> get() = messages.flatMap { it.media }

    fun render(format: ChatFormat): String = format.render(this)
}

/** Chat formats of the supported models. See `doc/PROMPTS.md` ("Gemma chat format"). */
enum class ChatFormat {
    /** Qwen3 (ChatML). No media. With no [ChatPrompt.assistantPrefix] the reply starts with an empty think block (thinking off). */
    CHATML {
        override fun render(prompt: ChatPrompt): String {
            require(prompt.media.isEmpty()) { "ChatML (Qwen3 via Genie) has no media input" }
            val sb = StringBuilder()
            for (m in prompt.messages) {
                val role = when (m.role) {
                    ChatRole.SYSTEM -> "system"
                    ChatRole.USER -> "user"
                    ChatRole.ASSISTANT -> "assistant"
                }
                sb.append("<|im_start|>").append(role).append('\n').append(m.text).append("<|im_end|>\n")
            }
            sb.append("<|im_start|>assistant\n")
            sb.append(prompt.assistantPrefix.ifEmpty { "<think>\n\n</think>\n\n" })
            return sb.toString()
        }
    },

    /**
     * Gemma 4, as rendered by the model's own jinja template with `enable_thinking = false` (read from the GGUF, `tools/gguf_meta.py`):
     * `<|turn>system\n..<turn|>\n`, `<|turn>user\n..<turn|>\n`, `<|turn>model\n..<turn|>\n`, then `<|turn>model\n` and the forced prefix.
     * Message text is trimmed (the template applies `| trim`). The BOS token is NOT rendered: the native side adds it
     * (`add_special`), exactly as llama-server does (it strips the BOS the template emits when the vocab adds one).
     * Each media item becomes one [MEDIA_MARKER] directly after the text of its turn (llama-server's text-then-media layout);
     * libmtmd expands it to `<|image>..<image|>` / `<|audio>..<audio|>`.
     */
    GEMMA4 {
        override fun render(prompt: ChatPrompt): String {
            val sb = StringBuilder()
            for (m in prompt.messages) {
                val role = when (m.role) {
                    ChatRole.SYSTEM -> "system"
                    ChatRole.USER -> "user"
                    ChatRole.ASSISTANT -> "model"
                }
                require(m.media.isEmpty() || m.role == ChatRole.USER) { "media can only be attached to a user turn" }
                sb.append("<|turn>").append(role).append('\n').append(m.text.trim())
                repeat(m.media.size) { sb.append(MEDIA_MARKER) }
                sb.append("<turn|>\n")
            }
            sb.append("<|turn>model\n").append(prompt.assistantPrefix)
            return sb.toString()
        }
    };

    abstract fun render(prompt: ChatPrompt): String

    companion object {
        /** libmtmd's default media marker (`mtmd_default_marker()`); one per media item, in order. */
        const val MEDIA_MARKER = "<__media__>"
    }
}
