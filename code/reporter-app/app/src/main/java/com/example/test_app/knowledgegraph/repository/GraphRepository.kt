package com.example.test_app.knowledgegraph.repository

import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.model.GraphPack
import java.io.File

/**
 * Where graph packs live (spec section 18: versioned, importable, roll-back-able). The file implementation is the app's; the
 * interface lets SQLite replace it later without touching the engine (ADR-055). Implementations must be thread-safe.
 */
interface GraphRepository {
    /** The pack the engine uses. Never null: the repository is seeded before first use. */
    fun activePack(): GraphPack

    /** All stored versions, ascending. */
    fun versions(): List<Int>

    fun load(version: Int): GraphPack?

    /** Stores [pack] under its `packVersion` (a version already stored is NOT overwritten: throws [IllegalStateException]). Does not activate it. */
    fun save(pack: GraphPack)

    /** Makes a stored version the active one (rollback = activating an older version). Throws [IllegalArgumentException] for an unknown version. */
    fun activate(version: Int)
}

/** In-memory repository (tests, and the admin preview of a pack that is not installed). */
class InMemoryGraphRepository(initial: GraphPack) : GraphRepository {
    private val packs = sortedMapOf(initial.packVersion to initial)
    private var active = initial.packVersion

    @Synchronized override fun activePack(): GraphPack = packs.getValue(active)
    @Synchronized override fun versions(): List<Int> = packs.keys.toList()
    @Synchronized override fun load(version: Int): GraphPack? = packs[version]
    @Synchronized override fun save(pack: GraphPack) {
        check(pack.packVersion !in packs) { "graph version ${pack.packVersion} already exists" }
        packs[pack.packVersion] = pack
    }
    @Synchronized override fun activate(version: Int) {
        require(version in packs) { "unknown graph version $version" }
        active = version
    }
}

/**
 * Packs as versioned JSON files: `<dir>/packs/<version>.json` plus `<dir>/active.txt` with the active version number. The seed pack is
 * installed on first run from [seedJson] (assets/kg/seed_pack.json). A damaged active pointer or file falls back to the newest readable
 * pack; if nothing is readable the seed is installed again. No android imports: the app passes `File(filesDir, "kg")`.
 */
class FileGraphRepository(private val dir: File, private val seedJson: () -> String) : GraphRepository {
    private val packsDir get() = File(dir, "packs")
    private val pointer get() = File(dir, "active.txt")
    private var cache: GraphPack? = null

    @Synchronized override fun activePack(): GraphPack {
        cache?.let { return it }
        val pack = readActive() ?: installSeed()
        cache = pack
        return pack
    }

    @Synchronized override fun versions(): List<Int> =
        (packsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()

    @Synchronized override fun load(version: Int): GraphPack? =
        File(packsDir, "$version.json").takeIf { it.isFile }?.let { runCatching { GraphPackCodec.decode(it.readText(Charsets.UTF_8)) }.getOrNull() }

    @Synchronized override fun save(pack: GraphPack) {
        packsDir.mkdirs()
        val file = File(packsDir, "${pack.packVersion}.json")
        check(!file.exists()) { "graph version ${pack.packVersion} already exists" }
        val tmp = File(packsDir, "${pack.packVersion}.json.tmp")
        tmp.writeText(GraphPackCodec.encode(pack), Charsets.UTF_8)
        check(tmp.renameTo(file)) { "could not store graph version ${pack.packVersion}" }
    }

    @Synchronized override fun activate(version: Int) {
        val pack = load(version) ?: throw IllegalArgumentException("unknown graph version $version")
        dir.mkdirs()
        pointer.writeText(version.toString(), Charsets.UTF_8)
        cache = pack
    }

    private fun readActive(): GraphPack? {
        val wanted = pointer.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.toIntOrNull()
        wanted?.let { v -> load(v)?.let { return it } }
        // pointer missing or damaged: the newest readable pack
        for (v in versions().reversed()) load(v)?.let { pack -> runCatching { activate(v) }; return pack }
        return null
    }

    private fun installSeed(): GraphPack {
        val seed = GraphPackCodec.decode(seedJson())
        if (seed.packVersion !in versions()) save(seed)
        activate(seed.packVersion)
        return seed
    }
}
