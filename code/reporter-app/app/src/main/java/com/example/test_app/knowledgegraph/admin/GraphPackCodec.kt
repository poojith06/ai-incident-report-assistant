package com.example.test_app.knowledgegraph.admin

import com.example.test_app.knowledgegraph.model.GraphPack
import kotlinx.serialization.json.Json

/** JSON of graph packs, audit logs and analysis exports. Packs are decoded strictly: an unknown key in an import is an error, not ignored. */
object GraphPackCodec {
    val pretty: Json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }
    private val strict: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(pack: GraphPack): String = pretty.encodeToString(GraphPack.serializer(), pack) + "\n"

    /** Throws [IllegalArgumentException] (message = what is wrong) for text that is not a valid pack. */
    fun decode(text: String): GraphPack = try {
        strict.decodeFromString(GraphPack.serializer(), text)
    } catch (e: Exception) {
        throw IllegalArgumentException("not a valid graph pack: ${e.message?.lineSequence()?.firstOrNull()}", e)
    }
}
