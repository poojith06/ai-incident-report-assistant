package com.example.test_app.dialogue

import java.io.File

/** Loads the real dialogue assets from `app/src/main/assets/dialogue` on disk (no Android needed). */
object TestAssets {
    val dir: File = listOf("src/main/assets/dialogue", "app/src/main/assets/dialogue")
        .map { File(it) }.first { it.isDirectory }

    val assets: DialogueAssets by lazy { DialogueAssets.fromDirectory(dir) }
}
