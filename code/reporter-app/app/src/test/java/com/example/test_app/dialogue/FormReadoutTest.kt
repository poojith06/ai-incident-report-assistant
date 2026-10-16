package com.example.test_app.dialogue

import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.TimeSource
import com.example.test_app.domain.model.Validation
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.textValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormReadoutTest {
    private fun t(v: String) = FieldEntry(textValue(v), 0.9)

    private fun report(
        vararg fields: Pair<String, FieldEntry>,
        missing: List<String> = emptyList(),
        timeSource: String? = null,
        id: String = "INC-2026-665948",
    ) = IncidentReport(
        reportId = id, status = "x", createdAt = "2026-10-11T09:30:00+05:30", input = ReportInput("voice", "x"),
        fields = mapOf(*fields), validation = Validation(missingFields = missing), clarification = Clarification(), timeSource = timeSource,
    )

    private val fire = report(
        "incident_type" to t("Fire"), "incident_location" to t("the AC in the lab"), "severity_level" to t("Critical"),
        "injury" to FieldEntry(boolValue(true), 0.9), timeSource = TimeSource.DEFAULT_NOW,
    )

    private val forbidden = listOf("notified", "notify", "alerted", "dispatched", "help is on the way", "on its way", "authorities", "has been called")

    private fun assertClean(line: String) {
        val l = line.lowercase()
        for (f in forbidden) assertFalse("\"$f\" in: $line", l.contains(f))
        for (p in TestAssets.assets.blocked.phrases) assertFalse("blocked \"$p\" in: $line", TextMatchProxy.contains(l, p))
    }

    @Test fun completeForm() {
        val line = FormReadout.review(
            report(
                "incident_type" to t("Water Leak"), "incident_location" to t("Conference Room 2"),
                "date_time" to t("2026-10-11T10:00:00+05:30"), "severity_level" to t("Low"),
                "injury" to FieldEntry(boolValue(false), 0.9),
            ),
        )
        assertEquals(
            "Your report is ready. Water Leak, at Conference Room 2, 10 AM. Severity low. Nobody was hurt. Please review and tap submit.", line,
        )
        assertClean(line)
    }

    @Test fun defaultedTimeIsJustNowAndSpokenTimeUsesAmPm() {
        assertTrue(FormReadout.review(fire).contains("Fire, at the AC in the lab, just now."))
        val afternoon = report("incident_type" to t("Spill"), "date_time" to t("2026-10-11T15:30:00+05:30"), timeSource = TimeSource.SPOKEN)
        assertTrue(FormReadout.review(afternoon).contains("Spill, 3:30 PM."))
    }

    @Test fun severeAddsContactsSentenceWithoutClaimingAnything() {
        val line = FormReadout.review(fire)
        assertEquals(
            "Your report is ready. Fire, at the AC in the lab, just now. Severity critical. Someone was hurt. " +
                "Emergency contact numbers are shown after you submit. Please review and tap submit.", line,
        )
        assertClean(line)
        assertTrue(FormReadout.review(report("incident_type" to t("Gas Leak"), "severity_level" to t("High"))).contains("Emergency contact numbers are shown after you submit."))
        assertFalse(FormReadout.review(report("incident_type" to t("Spill"), "severity_level" to t("Medium"))).contains("Emergency"))
    }

    @Test fun missingAndFlaggedFieldsAreNamedUpToThree() {
        val r = report(
            "incident_type" to t("Fire"), "severity_level" to t("High"),
            "incident_location" to FieldEntry(status = FieldStatus.NO_RESPONSE),
            "incident_description" to FieldEntry(status = FieldStatus.MISSING_FLAGGED),
            "immediate_actions_taken" to FieldEntry(status = FieldStatus.UNVERIFIED),
            "persons_involved" to FieldEntry(status = FieldStatus.UNKNOWN_REPORTER_UNSURE),
            missing = listOf("incident_location", "injury_outcome"),
        )
        val line = FormReadout.review(r)
        assertTrue(line, line.contains("Still missing: location, description, actions taken."))
        assertTrue(line.endsWith("Please review the highlighted fields and tap submit."))
        assertFalse("only three are named, injury never", line.contains("people involved") || line.contains("injury"))
        assertClean(line)
    }

    @Test fun oneMissingField() {
        val line = FormReadout.review(report("incident_type" to t("Fire"), missing = listOf("incident_location")))
        assertTrue(line, line.contains("Still missing: location."))
    }

    @Test fun emptyFormGetsAGenericLine() {
        assertEquals("Your report is ready. Please review and tap submit.", FormReadout.review(report()))
    }

    @Test fun wordCapHoldsInTheWorstCase() {
        val r = report(
            "incident_type" to t("Electrical Hazard"),
            "incident_location" to t("the third floor corridor next to the east stairwell and the old server room"),
            "severity_level" to t("Critical"), "injury" to FieldEntry(boolValue(true), 0.9),
            "incident_description" to FieldEntry(status = FieldStatus.NO_RESPONSE),
            "immediate_actions_taken" to FieldEntry(status = FieldStatus.UNVERIFIED),
            "persons_involved" to FieldEntry(status = FieldStatus.MISSING_FLAGGED),
            missing = listOf("injury_outcome"), timeSource = TimeSource.DEFAULT_NOW,
        )
        val line = FormReadout.review(r)
        assertTrue("${FormReadout.wordCount(line)} words: $line", FormReadout.wordCount(line) <= FormReadout.MAX_WORDS)
        assertTrue("severe sentence is kept", line.contains("Emergency contact numbers"))
        assertClean(line)
    }

    @Test fun submittedLineReadsTheReferenceDigitByDigit() {
        assertEquals("INC-2026-665948 as speech", "I N C, 2026, 6 6 5 9 4 8", FormReadout.speakableId("INC-2026-665948"))
        assertEquals("Report submitted. Reference I N C, 2026, 6 6 5 9 4 8.", FormReadout.submitted(report("severity_level" to t("Low"))))
    }

    @Test fun submittedLineForSevereReportsPointsToTheContactsOnScreen() {
        val line = FormReadout.submitted(fire)
        assertEquals("Report submitted. Reference I N C, 2026, 6 6 5 9 4 8. You can call the emergency contacts on screen.", line)
        assertClean(line)
    }

    @Test fun submittedLineNeverClaimsAnAction() {
        for (r in listOf(fire, report(), report("severity_level" to t("Medium")))) assertClean(FormReadout.submitted(r))
    }
}

/** Whole-word check without depending on the internal `TextMatch` from a different package. */
private object TextMatchProxy {
    fun contains(text: String, phrase: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase.lowercase()).replace("\\ ", "\\s+") + "(?![\\p{L}\\p{N}])").containsMatchIn(text)
}
