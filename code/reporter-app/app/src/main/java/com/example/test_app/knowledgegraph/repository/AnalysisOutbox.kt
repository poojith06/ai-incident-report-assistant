package com.example.test_app.knowledgegraph.repository

import com.example.test_app.knowledgegraph.IncidentAnalysisExport
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import java.io.File

/**
 * Queue of analysis exports waiting to be synced to the future admin dashboard. There is NO network code: a future sync job reads
 * [pending], uploads each document and calls [acknowledge]. Re-running the analysis of an incident replaces its queued document.
 */
interface AnalysisOutbox {
    fun enqueue(export: IncidentAnalysisExport)

    /** Incident ids with a queued document, oldest first. */
    fun pending(): List<String>

    fun read(incidentId: String): IncidentAnalysisExport?

    /** The document was delivered: remove it from the queue. */
    fun acknowledge(incidentId: String)
}

/** The queue as `<dir>/<incidentId>.json` files (`files/kg/outbox/`). */
class FileAnalysisOutbox(private val dir: File) : AnalysisOutbox {
    @Synchronized override fun enqueue(export: IncidentAnalysisExport) {
        dir.mkdirs()
        val file = File(dir, name(export.incident.incidentId))
        val tmp = File(dir, name(export.incident.incidentId) + ".tmp")
        tmp.writeText(GraphPackCodec.pretty.encodeToString(IncidentAnalysisExport.serializer(), export) + "\n", Charsets.UTF_8)
        if (file.exists()) file.delete()
        check(tmp.renameTo(file)) { "could not queue the analysis export" }
    }

    @Synchronized override fun pending(): List<String> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.lastModified() }.map { it.name.removeSuffix(".json") }

    @Synchronized override fun read(incidentId: String): IncidentAnalysisExport? =
        File(dir, name(incidentId)).takeIf { it.isFile }
            ?.let { runCatching { GraphPackCodec.pretty.decodeFromString(IncidentAnalysisExport.serializer(), it.readText(Charsets.UTF_8)) }.getOrNull() }

    @Synchronized override fun acknowledge(incidentId: String) { File(dir, name(incidentId)).delete() }

    private fun name(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"
}

class InMemoryAnalysisOutbox : AnalysisOutbox {
    private val queue = linkedMapOf<String, IncidentAnalysisExport>()
    override fun enqueue(export: IncidentAnalysisExport) { queue[export.incident.incidentId] = export }
    override fun pending(): List<String> = queue.keys.toList()
    override fun read(incidentId: String) = queue[incidentId]
    override fun acknowledge(incidentId: String) { queue.remove(incidentId) }
}
