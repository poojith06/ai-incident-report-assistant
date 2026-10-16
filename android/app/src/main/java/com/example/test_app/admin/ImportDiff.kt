package com.example.test_app.admin

import com.example.test_app.knowledgegraph.model.GraphPack

/** Counts and ids of what changes when a pack replaces the active one. Ids are capped for display (`...Sample`). */
data class PackDiff(
    val nodesAdded: Int, val nodesChanged: Int, val nodesRemoved: Int,
    val edgesAdded: Int, val edgesChanged: Int, val edgesRemoved: Int,
    val sourcesAdded: Int, val sourcesChanged: Int, val sourcesRemoved: Int,
    val addedSample: List<String>, val changedSample: List<String>, val removedSample: List<String>,
) {
    val isEmpty: Boolean get() = nodesAdded + nodesChanged + nodesRemoved + edgesAdded + edgesChanged + edgesRemoved + sourcesAdded + sourcesChanged + sourcesRemoved == 0

    fun summary(): String =
        "nodes +$nodesAdded ~$nodesChanged -$nodesRemoved, edges +$edgesAdded ~$edgesChanged -$edgesRemoved, sources +$sourcesAdded ~$sourcesChanged -$sourcesRemoved"
}

/** Compares two packs by id (the `version` of a node or edge is ignored: a re-import stamps new versions). Pure. */
object ImportDiff {
    private const val SAMPLE = 8

    fun compute(old: GraphPack, new: GraphPack): PackDiff {
        val on = old.nodes.associateBy { it.id }.mapValues { it.value.copy(version = 0) }
        val nn = new.nodes.associateBy { it.id }.mapValues { it.value.copy(version = 0) }
        val oe = old.edges.associateBy { it.id }.mapValues { it.value.copy(version = 0) }
        val ne = new.edges.associateBy { it.id }.mapValues { it.value.copy(version = 0) }
        val os = old.sources.associateBy { it.id }
        val ns = new.sources.associateBy { it.id }
        fun <T> part(a: Map<String, T>, b: Map<String, T>) = Triple((b.keys - a.keys), (a.keys - b.keys), a.keys.intersect(b.keys).filter { a[it] != b[it] })
        val (na, nr, nc) = part(on, nn)
        val (ea, er, ec) = part(oe, ne)
        val (sa, sr, sc) = part(os, ns)
        return PackDiff(
            na.size, nc.size, nr.size, ea.size, ec.size, er.size, sa.size, sc.size, sr.size,
            (na + sa + ea).sorted().take(SAMPLE), (nc + sc + ec).sorted().take(SAMPLE), (nr + sr + er).sorted().take(SAMPLE),
        )
    }
}
