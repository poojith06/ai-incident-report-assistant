package com.example.test_app.admin

import com.example.test_app.knowledgegraph.PackBuilder
import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo PIN gate, the import diff and the editable analysis settings (pure). */
class PinImportDiffSettingsTest {
    // ---- PIN ----

    @Test fun theDefaultPinIs0000AndOnlyFourDigitsAreValid() {
        assertEquals("0000", AdminPin.DEFAULT)
        assertTrue(AdminPin.isValidFormat("1234")); assertTrue(AdminPin.isValidFormat("0000"))
        for (bad in listOf("", "123", "12345", "12a4", "١٢٣٤", " 123", "12 4")) assertFalse(bad, AdminPin.isValidFormat(bad))
    }

    @Test fun checkUsesTheStoredPinOrTheDefault() {
        assertTrue(AdminPin.check("0000", null)); assertFalse(AdminPin.check("1111", null))
        assertTrue(AdminPin.check("4821", "4821")); assertFalse(AdminPin.check("0000", "4821"))
        assertFalse(AdminPin.check("12", "12")) // a malformed entry never matches
        assertEquals("0000", AdminPin.effective("garbage")); assertTrue(AdminPin.check("0000", "garbage"))
    }

    @Test fun changingThePinNeedsTheOldOneAGoodNewOneAndAMatchingConfirm() {
        assertEquals(AdminPin.Change.Ok("1234"), AdminPin.change("0000", "1234", "1234", null))
        assertTrue((AdminPin.change("9999", "1234", "1234", null) as AdminPin.Change.Rejected).reason.contains("current"))
        assertTrue((AdminPin.change("0000", "12", "12", null) as AdminPin.Change.Rejected).reason.contains("4 digits"))
        assertTrue((AdminPin.change("0000", "1234", "4321", null) as AdminPin.Change.Rejected).reason.contains("different"))
        assertTrue((AdminPin.change("0000", "0000", "0000", null) as AdminPin.Change.Rejected).reason.contains("same"))
        assertEquals(AdminPin.Change.Ok("5555"), AdminPin.change("1234", "5555", "5555", "1234"))
    }

    // ---- import diff ----

    private fun base() = PackBuilder.basic("a", "b").build()

    @Test fun identicalPacksHaveAnEmptyDiffEvenWithNewVersionStamps() {
        val p = base()
        val restamped = p.copy(packVersion = 9, nodes = p.nodes.map { it.copy(version = 5) }, edges = p.edges.map { it.copy(version = 7) })
        assertTrue(ImportDiff.compute(p, restamped).isEmpty)
    }

    @Test fun addedChangedAndRemovedAreCountedPerTable() {
        val old = base()
        val new = old.copy(
            nodes = old.nodes.filter { it.id != "team:ops" }.map { if (it.id == "action:a") it.copy(name = "renamed") else it } + com.example.test_app.knowledgegraph.model.KgNode("hazard:new", NodeType.HAZARD, "new"),
            edges = old.edges.filter { it.relation != Relation.OWNED_BY } + com.example.test_app.knowledgegraph.model.KgEdge("edge:new", "hazard:new", Relation.MAY_CAUSE, "hazard:new") +
                old.edges.first { it.relation == Relation.TRIGGERS_ACTION }.copy(priority = 77),
            sources = old.sources.filter { it.id != "sop:ok" } + KgSource("sop:new", "New source"),
        )
        val d = ImportDiff.compute(old, new)
        assertEquals(1, d.nodesAdded); assertEquals(1, d.nodesChanged); assertEquals(1, d.nodesRemoved)
        assertEquals(1, d.edgesAdded); assertEquals(1, d.edgesChanged); assertEquals(2, d.edgesRemoved)
        assertEquals(1, d.sourcesAdded); assertEquals(0, d.sourcesChanged); assertEquals(1, d.sourcesRemoved)
        assertFalse(d.isEmpty); assertTrue(d.addedSample.contains("hazard:new")); assertTrue(d.removedSample.contains("team:ops")); assertTrue(d.changedSample.contains("action:a"))
        assertTrue(d.summary().contains("nodes +1 ~1 -1"))
    }

    @Test fun aChangedSourceCountsAsChanged() {
        val old = base()
        val new = old.copy(sources = old.sources.map { it.copy(title = "other") })
        assertEquals(1, ImportDiff.compute(old, new).sourcesChanged)
    }

    @Test fun theSamplesAreCapped() {
        val old = base()
        val many = old.copy(nodes = old.nodes + (1..30).map { com.example.test_app.knowledgegraph.model.KgNode("n:$it", NodeType.HAZARD, "n$it") })
        val d = ImportDiff.compute(old, many)
        assertEquals(30, d.nodesAdded); assertEquals(8, d.addedSample.size)
        assertEquals(ApprovalStatus.APPROVED.name, "APPROVED")
    }

    // ---- analysis settings ----

    @Test fun defaultsMatchTheEngineDefaultsAndRoundTrip() {
        val s = AnalysisSettings()
        assertEquals(EngineConfig(), s.toEngineConfig())
        assertEquals(s, AnalysisSettings.fromEngineConfig(EngineConfig()))
        assertEquals(s, AnalysisSettings.decode(AnalysisSettings.encode(s)))
        assertTrue(s.problems().isEmpty())
    }

    @Test fun editedSettingsReachTheEngineConfig() {
        val cfg = AnalysisSettings(abstainBelowEvidence = 0.5, immediate = 5, preventive = 0, wGraphPriority = 0.6, maxConsequences = 2).toEngineConfig()
        assertEquals(0.5, cfg.abstainBelowEvidence, 0.0); assertEquals(5, cfg.limits.immediate); assertEquals(0, cfg.limits.preventive); assertEquals(0.6, cfg.weights.graphPriority, 0.0); assertEquals(2, cfg.maxConsequences)
        assertEquals(1, cfg.limits.questions) // the one-question rule is not editable
    }

    @Test fun invalidValuesAreReportedAndNeverStored() {
        assertTrue(AnalysisSettings(abstainBelowEvidence = 1.5).problems().isNotEmpty())
        assertTrue(AnalysisSettings(immediate = 11).problems().isNotEmpty()); assertTrue(AnalysisSettings(recovery = -1).problems().isNotEmpty())
        assertTrue(AnalysisSettings(wAsset = 2.0).problems().isNotEmpty()); assertTrue(AnalysisSettings(wEvidence = Double.NaN).problems().isNotEmpty())
        assertEquals(AnalysisSettings(), AnalysisSettings.decode(AnalysisSettings.encode(AnalysisSettings(immediate = 99))))
    }

    @Test fun damagedOrMissingJsonGivesTheDefaults() {
        assertEquals(AnalysisSettings(), AnalysisSettings.decode(null)); assertEquals(AnalysisSettings(), AnalysisSettings.decode("{ broken")); assertEquals(AnalysisSettings(), AnalysisSettings.decode(""))
        assertEquals(5, AnalysisSettings.decode("""{"immediate": 5, "unknownKey": 1}""").immediate)
    }
}
