package com.example.test_app.platform

import android.content.Context
import com.example.test_app.dialogue.DialogueAssets

/**
 * Loads and validates the files in `assets/dialogue` through the Android `AssetManager`. Throws `DialogueAssetException`
 * on a malformed file; `IncidentApp` touches it at startup in debuggable builds so a bad asset fails loudly.
 * See `doc/DIALOGUE.md`.
 */
object DialogueAssetsLoader {
    fun load(context: Context, language: String = "en"): DialogueAssets =
        DialogueAssets.load(language) { name ->
            context.assets.open("dialogue/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
}
