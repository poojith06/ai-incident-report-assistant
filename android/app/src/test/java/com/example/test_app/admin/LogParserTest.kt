package com.example.test_app.admin

import com.example.test_app.admin.AdminTestSupport.auditOf
import com.example.test_app.admin.AdminTestSupport.scenarioIncident
import com.example.test_app.admin.AdminTestSupport.snapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Log parsing, filtering and bundling of the admin Logs tab (pure). */
class LogParserTest {
    private val dialogueLines = listOf(
        """{"t":1760000000,"state":"ASK_LOOP","mode":"PLAIN","field":"location","spoken":"Where did this happen?","validator":"pass","outcome":"filled"}""",
        """{"t":1760000010,"state":"ASK_LOOP","mode":"PLAIN","field":"description","spoken":"What did you see?","validator":"fail:two questions","outcome":"no_response"}""",
        """{"t":1760000020,"state":"READBACK","mode":"CARE","spoken":"Logged."}""",
        "",
        "this is not json",
    )
    private val file = DialogueLogFile("INC-1", dialogueLines, 5L)

    @Test fun dialogueLinesBecomeEntriesWithLevelsAndTypes() {
        val e = LogParser.fromDialogue(file)
        assertEquals(4, e.size) // the blank line is skipped
        assertEquals(LogLevel.INFO, e[0].level); assertEquals("ASK_LOOP", e[0].type); assertTrue(e[0].subtitle.contains("location"))
        assertEquals(LogLevel.WARN, e[1].level) // validator failed and no response
        assertEquals(LogLevel.INFO, e[2].level); assertEquals("READBACK", e[2].type)
        assertEquals(LogLevel.ERROR, e[3].level); assertEquals("unreadable", e[3].type); assertEquals("this is not json", e[3].json)
        assertTrue(e.all { it.kind == LogKind.DIALOGUE && it.reportId == "INC-1" })
        assertEquals(e.size, e.map { it.id }.toSet().size)
    }

    @Test fun theJsonOfAnEntryIsPrettyAndParsable() {
        val e = LogParser.fromDialogue(file)[0]
        assertTrue(e.json.contains("\n")); assertEquals("location", Json.parseToJsonElement(e.json).jsonObject["field"]!!.toString().trim('"'))
    }

    @Test fun anAuditLogBecomesAnEntryWithTheIncidentAndCounts() {
        val log = auditOf(scenarioIncident(2))
        val e = LogParser.fromAudit(log)
        assertEquals(LogKind.AUDIT, e.kind); assertEquals(log.incidentId, e.reportId); assertEquals("COMPUTER_FIRE", e.type)
        assertTrue(e.title.contains("recommendation")); assertEquals(LogLevel.INFO, e.level)
        assertTrue(e.subtitle.contains("suppressed")); assertTrue(e.json.contains("recommendationIds"))
        val abstained = LogParser.fromAudit(auditOf(scenarioIncident(2).copy(userConfirmed = false)))
        assertEquals(LogLevel.WARN, abstained.level); assertTrue(abstained.title.contains("abstained"))
    }

    @Test fun anEvalRunBecomesAnEntry() {
        val e = LogParser.fromEval(EvalRunSummary("run-1", "2026-10-10T10:00:00Z", "transcript", 30, "30 cases | pass", "{}"))
        assertEquals(LogKind.EVAL, e.kind); assertEquals("transcript", e.type); assertEquals(null, e.reportId); assertTrue(e.subtitle.contains("30 cases"))
    }

    @Test fun entriesOfASnapshotAreNewestFirstPerKind() {
        val a = auditOf(scenarioIncident(1)).copy(createdAt = "2026-10-01T00:00:00Z", incidentId = "OLD")
        val b = auditOf(scenarioIncident(2)).copy(createdAt = "2026-10-09T00:00:00Z", incidentId = "NEW")
        val s = snapshot(audits = listOf(a, b), dialogue = listOf(DialogueLogFile("A", dialogueLines.take(1), 1), DialogueLogFile("B", dialogueLines.take(1), 9)))
        assertEquals(listOf("NEW", "OLD"), LogParser.entries(s, LogKind.AUDIT).map { it.reportId })
        assertEquals(listOf("B", "A"), LogParser.entries(s, LogKind.DIALOGUE).map { it.reportId })
        assertTrue(LogParser.entries(s, LogKind.EVAL).isEmpty()); assertTrue(LogParser.entries(s, LogKind.OUTBOX).isEmpty())
    }

    @Test fun filtersByLevelTypeAndTextIncludingTheJson() {
        val e = LogParser.fromDialogue(file)
        assertEquals(1, LogParser.filter(e, LogFilter(level = LogLevel.WARN)).size)
        assertEquals(1, LogParser.filter(e, LogFilter(level = LogLevel.ERROR)).size)
        assertEquals(2, LogParser.filter(e, LogFilter(type = "ask_loop")).size)
        assertEquals(1, LogParser.filter(e, LogFilter(query = "two questions")).size) // found in the JSON body
        assertEquals(1, LogParser.filter(e, LogFilter(query = "READBACK")).size)
        assertEquals(4, LogParser.filter(e, LogFilter(query = "  ")).size)
        assertEquals(0, LogParser.filter(e, LogFilter(query = "nothing like it")).size)
        assertEquals(1, LogParser.filter(e, LogFilter(level = LogLevel.WARN, type = "ASK_LOOP", query = "description")).size)
    }

    @Test fun typesAreDistinctAndSorted() {
        assertEquals(listOf("ASK_LOOP", "READBACK", "unreadable"), LogParser.types(LogParser.fromDialogue(file)))
    }

    @Test fun theBundleHoldsEveryKindAsParsedJson() {
        val s = snapshot(audits = listOf(auditOf(scenarioIncident(1))), dialogue = listOf(file))
        val json = LogParser.bundleJson(LogKind.entries.associateWith { LogParser.entries(s, it) }, "2026-10-11T06:00:00Z")
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("2026-10-11T06:00:00Z", root["createdAt"]!!.toString().trim('"'))
        val logs = root["logs"]!!.jsonObject
        assertEquals(setOf("audit", "dialogue", "eval", "outbox"), logs.keys)
        assertEquals(1, (logs["audit"] as kotlinx.serialization.json.JsonArray).size); assertEquals(4, (logs["dialogue"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun filterChangeOfKindIsAKindSwitchNotAnEntryFilter() {
        assertEquals(LogKind.AUDIT, LogFilter().kind)
        assertEquals(listOf("Analysis audit", "Dialogue", "Eval runs", "Outbox"), LogKind.entries.map { it.label })
    }
}
