package com.example.test_app.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every rule of the planner (spec v2 section 4 as reworked by ADR-048 and ADR-050): relevance, priority, ask-once, caps. Injury is never asked. */
class FollowUpPlannerTest {
    private val p = FollowUpPlanner()
    private val none = emptySet<String>()

    private fun next(s: PlannerState, mode: DialogueMode = DialogueMode.PLAIN, asked: Set<String> = none) = p.next(s, mode, asked)

    private val allFilled = setOf(
        DialogueFields.INJURED_PERSON, DialogueFields.INJURY_TYPE, DialogueFields.LOCATION,
        DialogueFields.DESCRIPTION, DialogueFields.TIME, DialogueFields.IMMEDIATE_ACTIONS, DialogueFields.PEOPLE_INVOLVED,
    )

    @Test fun nothingLeftReturnsNull() {
        assertNull(next(PlannerState(filled = allFilled, injury = false)))
    }

    @Test fun locationIsTheTopQuestionWhenMissing() {
        assertEquals("location", next(PlannerState()))
        assertEquals("location", next(PlannerState(), DialogueMode.URGENT))
    }

    @Test fun aVagueLocationCountsAsMissingEvenWhenFilled() {
        val s = PlannerState(filled = setOf("location"), locationVague = true)
        assertEquals("location", next(s))
        assertNull("ask-once", next(s, asked = setOf("location")))
    }

    @Test fun injuryIsNeverAskedWhateverTheStateOrMode() {
        assertTrue("injury" !in DialogueFields.PRIORITY)
        val states = listOf(PlannerState(), PlannerState(injury = true), PlannerState(injury = false), PlannerState(filled = setOf("location")))
        for (st in states) for (m in DialogueMode.entries) assertTrue("injury" !in p.missing(st, m))
    }

    @Test fun injuredPersonOnlyWhenInjuryTrueAndNotNamed() {
        val base = PlannerState(filled = setOf("location"))
        assertNull(next(base.copy(injury = false)))
        assertEquals("injured_person", next(base.copy(injury = true), DialogueMode.CARE))
        assertEquals("injured_person", next(base.copy(injury = true), DialogueMode.PLAIN))
        assertEquals("injury_type", next(base.copy(injury = true, filled = base.filled + "injured_person"), DialogueMode.CARE))
    }

    @Test fun injuryTypeOnlyInCareMode() {
        val s = PlannerState(filled = setOf("location", "injured_person"), injury = true)
        assertEquals("injury_type", next(s, DialogueMode.CARE))
        assertNull(next(s, DialogueMode.PLAIN))
        assertNull(next(s, DialogueMode.URGENT))
    }

    @Test fun injuryTypeSkippedWhenFilledOrNoInjury() {
        val s = PlannerState(filled = setOf("location", "injured_person", "injury_type"), injury = true)
        assertNull(next(s, DialogueMode.CARE))
        assertNull(next(PlannerState(filled = setOf("location"), injury = false), DialogueMode.CARE))
    }

    @Test fun descriptionOnlyWhenThin() {
        val base = PlannerState(filled = setOf("location", "description"), injury = false)
        assertNull(next(base))
        assertEquals("description", next(base.copy(thinDescription = true)))
    }

    @Test fun timeOnlyWithPastCueAndNoTime() {
        val base = PlannerState(filled = setOf("location", "description"), injury = false)
        assertNull(next(base))
        assertNull(next(base.copy(pastCue = true, filled = base.filled + "time")))
        assertEquals("time", next(base.copy(pastCue = true)))
    }

    @Test fun immediateActionsOnlyForHighSeverityAndNoActionMentioned() {
        val base = PlannerState(filled = setOf("location", "description", "time"), injury = false)
        assertNull(next(base))
        assertEquals("immediate_actions", next(base.copy(severityAtLeastHigh = true)))
        assertNull("an ongoing hazard alone no longer triggers it", next(base.copy(hazardOngoing = true)))
        assertNull("action already mentioned", next(base.copy(severityAtLeastHigh = true, filled = base.filled + "immediate_actions")))
    }

    @Test fun peopleInvolvedOnlyForPersonalIncidents() {
        val base = PlannerState(filled = setOf("location", "description", "time"), injury = false)
        assertNull(next(base))
        assertEquals("people_involved", next(base.copy(personalIncident = true), DialogueMode.CARE))
        assertNull(next(base.copy(personalIncident = true, filled = base.filled + "people_involved"), DialogueMode.CARE))
    }

    @Test fun priorityOrderIsFollowed() {
        val s = PlannerState(
            filled = emptySet(), injury = true, severityAtLeastHigh = true, hazardOngoing = true,
            personalIncident = true, thinDescription = true, pastCue = true,
        )
        assertEquals(
            listOf("location", "description", "time", "immediate_actions", "people_involved", "injured_person", "injury_type"),
            p.missing(s, DialogueMode.CARE),
        )
        assertEquals(DialogueFields.PRIORITY, p.missing(s, DialogueMode.CARE))
    }

    // ---- ask-once ----

    @Test fun askedFieldsAreSkipped() {
        val s = PlannerState()
        assertEquals("location", next(s))
        assertNull("ask-once: nothing else is eligible", next(s, asked = setOf("location")))
        assertEquals("description", next(s.copy(thinDescription = true), asked = setOf("location")))
        assertNull(next(s.copy(thinDescription = true), asked = setOf("location", "description")))
    }

    @Test fun askOnceHoldsOverAFullWalk() {
        val s = PlannerState(
            injury = true, severityAtLeastHigh = true, hazardOngoing = true, personalIncident = true,
            thinDescription = true, pastCue = true,
        )
        val asked = linkedSetOf<String>()
        while (true) {
            val f = p.next(s, DialogueMode.CARE, asked) ?: break
            assertTrue("'$f' returned twice", asked.add(f))
        }
        assertEquals(DialogueFields.PRIORITY.toSet(), asked)
    }

    @Test fun nextFieldNeverInAskedForAnyState() {
        val states = listOf(
            PlannerState(), PlannerState(injury = true), PlannerState(injury = false, thinDescription = true, pastCue = true),
            PlannerState(hazardOngoing = true, personalIncident = true),
        )
        for (s in states) for (m in DialogueMode.entries) {
            val asked = linkedSetOf<String>()
            repeat(10) {
                val f = p.next(s, m, asked) ?: return@repeat
                assertFalse(f in asked)
                asked += f
            }
        }
    }

    @Test fun remainingExcludesAskedAndMissingIncludesThem() {
        val s = PlannerState(injury = true)
        assertEquals(listOf("location", "injured_person"), p.missing(s, DialogueMode.PLAIN))
        assertEquals(listOf("injured_person"), p.remaining(s, DialogueMode.PLAIN, setOf("location")))
    }

    // ---- caps ----

    @Test fun capsPerMode() {
        val two = setOf("description", "location")
        assertEquals(2, DialogueMode.PLAIN.maxQuestions)
        assertEquals(3, DialogueMode.CARE.maxQuestions)
        assertEquals(2, DialogueMode.URGENT.maxQuestions)
        assertFalse(p.capReached(DialogueMode.PLAIN, setOf("description")))
        assertTrue(p.capReached(DialogueMode.PLAIN, two))
        assertFalse(p.capReached(DialogueMode.CARE, two))
        assertTrue(p.capReached(DialogueMode.CARE, setOf("description", "location", "time")))
        assertTrue(p.capReached(DialogueMode.URGENT, two))
    }

    @Test fun totalCapCountsOtherQuestionsLikeTheSafetyCheck() {
        // STRICT total of 2 per report: planner fields + the safety check (otherQuestions = 1)
        assertFalse(p.capReached(DialogueMode.CARE, setOf("description"), otherQuestions = 0, maxTotal = 2))
        assertTrue(p.capReached(DialogueMode.CARE, setOf("description"), otherQuestions = 1, maxTotal = 2))
        assertTrue(p.capReached(DialogueMode.CARE, setOf("description", "location"), otherQuestions = 0, maxTotal = 2))
        assertFalse(p.capReached(DialogueMode.URGENT, emptySet(), otherQuestions = 1, maxTotal = 2))
        assertTrue(p.capReached(DialogueMode.URGENT, setOf("description"), otherQuestions = 1, maxTotal = 2))
    }

    @Test fun perModeCapStillAppliesWhenLowerThanTheTotal() {
        val two = setOf("description", "location")
        assertTrue(p.capReached(DialogueMode.PLAIN, two, otherQuestions = 0, maxTotal = 3))
        assertTrue(p.capReached(DialogueMode.PLAIN, two, otherQuestions = 0, maxTotal = 10))
        assertFalse(p.capReached(DialogueMode.CARE, two, otherQuestions = 0, maxTotal = 10))
    }

    @Test fun neverAskedFieldsAreNotInThePriorityList() {
        val never = setOf("incident_type", "severity", "equipment", "witnesses", "recommended_follow_up", "reporter", "report_timestamp", "gps")
        assertTrue(DialogueFields.PRIORITY.none { it in never })
    }

    // ---- audit explanation ----

    @Test fun explainGivesAReasonForEveryField() {
        val s = PlannerState(filled = setOf("description", "time", "immediate_actions", "people_involved"), injury = false)
        val why = p.explain(s, DialogueMode.PLAIN, setOf("location"))
        assertEquals(DialogueFields.PRIORITY.toSet(), why.keys)
        assertEquals("skip: already asked", why["location"])
        assertTrue(why.values.all { it.startsWith("ask: ") || it.startsWith("skip: ") })
        assertFalse("injury has no row any more", "injury" in why)
    }

    @Test fun explainSaysWhyEachFieldIsAskedOrSkipped() {
        val ask = p.explain(PlannerState(), DialogueMode.PLAIN, none)
        assertEquals("ask: location missing (required for routing)", ask["location"])
        assertEquals("skip: nobody known to be hurt", ask["injured_person"])
    }
}
