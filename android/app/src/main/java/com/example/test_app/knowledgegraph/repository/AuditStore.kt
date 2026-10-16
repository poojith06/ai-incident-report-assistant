package com.example.test_app.knowledgegraph.repository

import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.model.RecommendationLog
import java.io.File

/** Stores the audit record of every engine run (spec section 8 `kg_recommendation_log`). */
interface AuditStore {
    fun save(log: RecommendationLog)
    fun load(incidentId: String): RecommendationLog?
}

/** Audit records as `<dir>/<incidentId>.json` (a later run for the same incident replaces the file). */
class FileAuditStore(private val dir: File) : AuditStore {
    @Synchronized override fun save(log: RecommendationLog) {
        dir.mkdirs()
        File(dir, fileName(log.incidentId)).writeText(GraphPackCodec.pretty.encodeToString(RecommendationLog.serializer(), log), Charsets.UTF_8)
    }

    @Synchronized override fun load(incidentId: String): RecommendationLog? =
        File(dir, fileName(incidentId)).takeIf { it.isFile }
            ?.let { runCatching { GraphPackCodec.pretty.decodeFromString(RecommendationLog.serializer(), it.readText(Charsets.UTF_8)) }.getOrNull() }

    /** Every stored audit log (the admin dashboard); unreadable files are skipped. */
    @Synchronized fun all(): List<RecommendationLog> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { GraphPackCodec.pretty.decodeFromString(RecommendationLog.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    /** Deletes the audit log of each incident id; returns how many files were removed. */
    @Synchronized fun delete(incidentIds: Collection<String>): Int = incidentIds.count { File(dir, fileName(it)).let { f -> f.isFile && f.delete() } }

    /** Deletes every audit log; returns how many files were removed. */
    @Synchronized fun clear(): Int = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).count { it.delete() }

    /** Incident ids are report ids (`INC-2026-123456`); anything else is made file-safe. */
    private fun fileName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"
}

class InMemoryAuditStore : AuditStore {
    val logs = mutableMapOf<String, RecommendationLog>()
    override fun save(log: RecommendationLog) { logs[log.incidentId] = log }
    override fun load(incidentId: String): RecommendationLog? = logs[incidentId]
}
