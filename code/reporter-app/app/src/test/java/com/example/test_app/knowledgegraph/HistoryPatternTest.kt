package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.history.HistoricIncident
import com.example.test_app.knowledgegraph.history.HistoryPatternDetector
import com.example.test_app.knowledgegraph.model.HistoryCitation
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.repository.GraphIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec section 15: deterministic recurrence queries with cited incidents. */
class HistoryPatternTest {
    private fun builder(scope: String = "LOCATION", threshold: Int = 3, window: Int = 60, hourWindow: Int? = null): PackBuilder = PackBuilder().incidentType("WATER_LEAK", "WATER").incidentType("FIRE").team("ops")
        .action("inspect-shared-line", text = "Ask authorized plumbing staff to inspect the shared supply and drain path.", team = "team:ops").rec("inspect-shared-line")
        .node("pattern:leak", NodeType.HISTORICAL_PATTERN, "Repeated leak", props("scope" to scope, "incidentTypes" to listOf("WATER_LEAK"), "windowDays" to window, "threshold" to threshold, "hourWindow" to hourWindow, "description" to "Repeated water leaks"))
        .edge("pattern:leak", Relation.TRIGGERS_ACTION, "action:inspect-shared-line", priority = 90)

    private fun detector(b: PackBuilder): HistoryPatternDetector { val index = GraphIndex(b.build()); return HistoryPatternDetector { index } }

    private fun inc(id: String, date: String, type: String = "WATER_LEAK", loc: String? = "krb washroom third floor", zone: String? = "building:krb|third floor", building: String? = "building:krb", assets: List<String> = emptyList()) =
        HistoricIncident(id, type, "${date}T10:00:00+05:30", loc, zone, building, assets)

    @Test fun threeLeaksAtTheSamePlaceWithinSixtyDaysFireThePatternAndCiteTheIncidents() {
        val now = inc("INC-3", "2026-10-10")
        val hits = detector(builder()).detect(now, listOf(inc("INC-1", "2026-09-01"), inc("INC-2", "2026-09-20")))
        val c = hits.single()
        assertEquals("pattern:leak", c.patternId)
        assertEquals(listOf("INC-1", "INC-2", "INC-3"), c.incidentIds)
        assertEquals(listOf("2026-09-01", "2026-09-20", "2026-10-10"), c.dates)
        assertEquals(3, c.count); assertEquals(60, c.windowDays)
    }

    @Test fun twoLeaksAreBelowTheThreshold() {
        assertTrue(detector(builder()).detect(inc("INC-2", "2026-10-10"), listOf(inc("INC-1", "2026-09-20"))).isEmpty())
    }

    @Test fun incidentsOutsideTheWindowOrAtAnotherPlaceOrOfAnotherTypeDoNotCount() {
        val d = detector(builder())
        val cur = inc("INC-4", "2026-10-10")
        val old = inc("INC-1", "2026-06-01")
        val other = inc("INC-2", "2026-09-20", loc = "himalaya washroom first floor")
        val fire = inc("INC-3", "2026-09-25", type = "FIRE")
        val noPlace = inc("INC-5", "2026-09-26", loc = null)
        assertTrue(d.detect(cur, listOf(old, other, fire, noPlace, inc("INC-6", "2026-09-27"))).isEmpty())
    }

    @Test fun theThresholdAndWindowComeFromTheGraphData() {
        val b = builder(threshold = 2, window = 10)
        assertEquals(2, detector(b).detect(inc("INC-2", "2026-10-10"), listOf(inc("INC-1", "2026-10-05"))).single().count)
        assertTrue(detector(b).detect(inc("INC-2", "2026-10-10"), listOf(inc("INC-1", "2026-09-20"))).isEmpty())
    }

    @Test fun zoneAndBuildingScopes() {
        val z = detector(builder(scope = "ZONE"))
        val cur = inc("INC-3", "2026-10-10", loc = "ladies washroom")
        assertEquals(3, z.detect(cur, listOf(inc("INC-1", "2026-09-20", loc = "gents washroom"), inc("INC-2", "2026-09-25", loc = "pantry"))).single().count)
        val bld = detector(builder(scope = "BUILDING"))
        assertTrue(bld.detect(cur.copy(buildingKey = null), listOf(inc("INC-1", "2026-09-20"), inc("INC-2", "2026-09-25"))).isEmpty())
        assertEquals(3, bld.detect(cur, listOf(inc("INC-1", "2026-09-20", zone = null), inc("INC-2", "2026-09-25", zone = null))).single().count)
    }

    @Test fun assetScopeNeedsTheSameAssetTypeAtTheSamePlace() {
        val d = detector(builder(scope = "ASSET"))
        val cur = inc("INC-3", "2026-10-10", assets = listOf("UPS"))
        assertEquals(3, d.detect(cur, listOf(inc("INC-1", "2026-09-20", assets = listOf("ups")), inc("INC-2", "2026-09-25", assets = listOf("UPS")))).single().count)
        assertTrue(d.detect(cur, listOf(inc("INC-1", "2026-09-20", assets = listOf("DESKTOP")), inc("INC-2", "2026-09-25", assets = emptyList()))).isEmpty())
    }

    @Test fun timeClusterNeedsTheSameBuildingAndSimilarTimeOfDay() {
        val d = detector(builder(scope = "TIME_CLUSTER", hourWindow = 2))
        fun at(id: String, date: String, hour: String) = inc(id, date).copy(createdAt = "${date}T$hour:00+05:30")
        val cur = at("INC-3", "2026-10-10", "10")
        assertEquals(3, d.detect(cur, listOf(at("INC-1", "2026-09-20", "09"), at("INC-2", "2026-09-25", "11"))).single().count)
        assertTrue(d.detect(cur, listOf(at("INC-1", "2026-09-20", "03"), at("INC-2", "2026-09-25", "22"))).isEmpty())
    }

    @Test fun theCurrentIncidentIsNotCountedTwiceAndHistoryOfAnotherTypeIsIgnored() {
        val cur = inc("INC-3", "2026-10-10")
        val hits = detector(builder(threshold = 3)).detect(cur, listOf(cur, inc("INC-1", "2026-09-20"), inc("INC-2", "2026-09-25")))
        assertEquals(listOf("INC-1", "INC-2", "INC-3"), hits.single().incidentIds)
        assertTrue(detector(builder()).detect(cur.copy(type = "FIRE"), listOf(inc("INC-1", "2026-09-20"), inc("INC-2", "2026-09-25"))).isEmpty())
        assertTrue(detector(builder()).detect(cur.copy(type = null), emptyList()).isEmpty())
    }

    @Test fun theHistoryRecordHoldsNoPersonalData() {
        val names = HistoricIncident::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(names.none { it.contains("reporter", true) || it.contains("injur", true) || it.contains("person", true) || it.contains("medical", true) })
    }

    @Test fun aRecurringPatternAddsItsActionToTheEngineWithTheCitation() {
        val b = builder().trigger("WATER_LEAK", "inspect-shared-line", priority = 10).let { it }
        val cite = HistoryCitation("pattern:leak", "Repeated water leaks", listOf("INC-1", "INC-2", "INC-3"), listOf("2026-09-01", "2026-09-20", "2026-10-10"), 3, 60)
        val none = b.run(incident(type = "WATER_LEAK"))
        assertTrue(none.allRecommendations.single().history.isEmpty())
        val withHistory = b.run(incident(type = "WATER_LEAK"), history = listOf(cite))
        val item = withHistory.allRecommendations.single()
        assertEquals(cite, item.history.single())
        assertEquals(listOf(cite), withHistory.historyCitations)
        assertTrue(item.provenance.any { it.startsWith("pattern:leak -TRIGGERS_ACTION->") })
        assertTrue(item.score > none.allRecommendations.single().score)
    }

    @Test fun aPatternOnlyActionAppearsOnlyWhenTheHistoryFires() {
        val b = builder()
        val cite = HistoryCitation("pattern:leak", "Repeated water leaks", listOf("INC-1", "INC-2", "INC-3"), listOf("2026-09-01", "2026-09-20", "2026-10-10"), 3, 60)
        assertTrue(b.run(incident(type = "WATER_LEAK")).ids().isEmpty())
        assertEquals(listOf("rec:inspect-shared-line"), b.run(incident(type = "WATER_LEAK"), history = listOf(cite)).ids())
    }

    @Test fun aHistorySuggestionWithoutSupportingIncidentsIsRefused() {
        val empty = HistoryCitation("pattern:leak", "x", emptyList(), emptyList(), 0, 60)
        val set = builder().run(incident(type = "WATER_LEAK"), history = listOf(empty))
        assertTrue(set.ids().isEmpty()); assertTrue(set.suppressed.any { it.reason.contains("without supporting incident ids") })
        val unknown = builder().run(incident(type = "WATER_LEAK"), history = listOf(HistoryCitation("pattern:nope", "x", listOf("A"), listOf("2026-01-01"), 1, 1)))
        assertTrue(unknown.suppressed.any { it.reason.contains("missing or inactive") })
        assertNull(unknown.historyCitations.firstOrNull())
    }
}
