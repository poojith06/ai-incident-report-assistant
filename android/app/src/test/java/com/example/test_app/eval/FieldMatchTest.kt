package com.example.test_app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextNormTest {
    @Test fun tokensLowerCaseStripPunctuationApostrophesAndAmPm() {
        assertEquals(listOf("dont", "stop", "its", "10", "am", "now"), TextNorm.tokens("Don't stop, it's 10 a.m. now!"))
        assertEquals(listOf("5", "pm"), TextNorm.tokens("5 P.M."))
        assertEquals(listOf("dont"), TextNorm.tokens("don\u2019t"))
    }

    @Test fun numberWordsBecomeDigits() {
        assertEquals("conference room 2", TextNorm.normalize("Conference Room two"))
        assertEquals("lab 3", TextNorm.normalize("lab three"))
        assertEquals("20 minutes", TextNorm.normalize("twenty minutes"))
    }

    @Test fun stemmingMergesCommonForms() {
        assertEquals("isolat", TextNorm.stem("isolated"))
        assertEquals("isolat", TextNorm.stem("isolate"))
        assertEquals("isolat", TextNorm.stem("isolates"))
        assertEquals("leak", TextNorm.stem("leaking"))
        assertEquals("leak", TextNorm.stem("leaks"))
        assertEquals("slip", TextNorm.stem("slipped"))
        assertEquals("burn", TextNorm.stem("burned"))
        assertEquals("room", TextNorm.stem("rooms"))
        assertEquals("fall", TextNorm.stem("falling"))
        assertEquals("glass", TextNorm.stem("glass"))
        assertEquals("bus", TextNorm.stem("bus"))
        assertEquals("2", TextNorm.stem("2"))
    }

    @Test fun contentTokensDropStopWordsAndStem() {
        assertEquals(
            listOf("maintenanc", "already", "isolat", "area"),
            TextNorm.contentTokens("Maintenance has already isolated the area."),
        )
    }

    @Test fun locationTokensDropPlacePrepositions() {
        assertEquals(listOf("conference", "room", "2", "second", "floor"), TextNorm.locationTokens("near the Conference Room 2, second floor"))
    }
}

class FieldMatchTest {
    private fun m(field: String, expected: String, actual: String?) = FieldMatch.match(field, expected, actual)

    @Test fun kindsPerField() {
        assertEquals(MatchKind.STRICT, FieldMatch.kindOf("incident_type"))
        assertEquals(MatchKind.STRICT, FieldMatch.kindOf("injury"))
        assertEquals(MatchKind.STRICT, FieldMatch.kindOf("severity_level"))
        assertEquals(MatchKind.LOCATION, FieldMatch.kindOf("incident_location"))
        assertEquals(MatchKind.TIME, FieldMatch.kindOf("date_time"))
        assertEquals(MatchKind.OVERLAP, FieldMatch.kindOf("immediate_actions_taken"))
        assertEquals(MatchKind.OVERLAP, FieldMatch.kindOf("persons_involved"))
        assertEquals(MatchKind.OVERLAP, FieldMatch.kindOf("incident_description"))
    }

    @Test fun strictFieldsAreExactOrNormalisedOnly() {
        assertEquals(MatchLevel.EXACT, m("incident_type", "Water Leak", "Water Leak"))
        assertEquals(MatchLevel.NORMALIZED, m("incident_type", "Water Leak", " water leak. "))
        assertEquals(MatchLevel.NONE, m("incident_type", "Water Leak", "Fire"))
        assertEquals(MatchLevel.NONE, m("incident_type", "Water Leak", "Water Leak Alarm"))
        assertEquals(MatchLevel.EXACT, m("injury", "false", "false"))
        assertEquals(MatchLevel.NONE, m("injury", "true", "false"))
    }

    @Test fun emptyOrBlankNeverMatches() {
        assertEquals(MatchLevel.NONE, m("incident_type", "Water Leak", null))
        assertEquals(MatchLevel.NONE, m("incident_type", "Water Leak", "  "))
        assertEquals(MatchLevel.NONE, m("incident_type", " ", "Water Leak"))
    }

    @Test fun locationRelaxedRule() {
        assertEquals(MatchLevel.NORMALIZED, m("incident_location", "Conference Room 2", "conference room two"))
        assertEquals(MatchLevel.NORMALIZED, m("incident_location", "lab 3", "Lab 3"))
        assertEquals(MatchLevel.RELAXED, m("incident_location", "Conference Room 2", "near the Conference Room 2, second floor"))
        assertEquals(MatchLevel.RELAXED, m("incident_location", "Block B", "block b second floor"))
        assertEquals(MatchLevel.RELAXED, m("incident_location", "parking lot", "the parking lot"))
        // less specific, different, or not a place at all
        assertEquals(MatchLevel.NONE, m("incident_location", "Conference Room 2", "Conference Room"))
        assertEquals(MatchLevel.NONE, m("incident_location", "lab 3", "lab"))
        assertEquals(MatchLevel.NONE, m("incident_location", "block C", "block B"))
        assertEquals(MatchLevel.NONE, m("incident_location", "pantry", "GPS 12.97160, 77.59460"))
    }

    @Test fun timeParsesToMinutesOfDay() {
        assertEquals(600, TimeOfDay.minutes("10 AM"))
        assertEquals(600, TimeOfDay.minutes("around 10 a.m."))
        assertEquals(600, TimeOfDay.minutes("ten am"))
        assertEquals(610, TimeOfDay.minutes("10:10 am"))
        assertEquals(930, TimeOfDay.minutes("3:30 PM"))
        assertEquals(930, TimeOfDay.minutes("15:30"))
        assertEquals(720, TimeOfDay.minutes("noon"))
        assertEquals(720, TimeOfDay.minutes("12 pm"))
        assertEquals(0, TimeOfDay.minutes("12 AM"))
        assertEquals(0, TimeOfDay.minutes("midnight"))
        assertEquals(600, TimeOfDay.minutes("2026-10-11T10:00:00+05:30"))
        assertNull(TimeOfDay.minutes("this morning"))
        assertNull(TimeOfDay.minutes("13 pm"))
    }

    @Test fun timeRelaxedRule() {
        assertEquals(MatchLevel.NORMALIZED, m("date_time", "10 AM", "10 am"))
        assertEquals(MatchLevel.NORMALIZED, m("date_time", "ten am", "10 AM"))
        assertEquals(MatchLevel.RELAXED, m("date_time", "10 AM", "2026-10-11T10:00:00+05:30"))
        assertEquals(MatchLevel.RELAXED, m("date_time", "10 AM", "2026-01-01T10:05:00Z"))
        assertEquals(MatchLevel.RELAXED, m("date_time", "around 10 AM", "10:00"))
        assertEquals(MatchLevel.RELAXED, m("date_time", "3:30 PM", "15:30"))
        assertEquals(MatchLevel.RELAXED, m("date_time", "noon", "12 PM"))
        assertEquals(MatchLevel.RELAXED, m("date_time", "12 AM", "midnight"))
        // tolerance: 15 minutes inclusive
        assertEquals(MatchLevel.RELAXED, m("date_time", "10 AM", "10:15 am"))
        assertEquals(MatchLevel.NONE, m("date_time", "10 AM", "10:16 am"))
        assertEquals(MatchLevel.NONE, m("date_time", "10 AM", "10 PM"))
    }

    @Test fun timeWithoutParsableClockFallsBackToText() {
        assertEquals(MatchLevel.NORMALIZED, m("date_time", "this morning", "This morning."))
        assertEquals(MatchLevel.RELAXED, m("date_time", "this morning", "sometime this morning"))
        assertEquals(MatchLevel.NONE, m("date_time", "this morning", "yesterday evening"))
    }

    @Test fun overlapRelaxedRule() {
        assertEquals(MatchLevel.RELAXED, m("immediate_actions_taken", "Area isolated", "Maintenance has already isolated the area."))
        assertEquals(MatchLevel.RELAXED, m("injury_type", "twisted ankle", "I twisted my ankle badly"))
        assertEquals(MatchLevel.NORMALIZED, m("persons_involved", "Ravi; Priya", "Ravi, Priya"))
        assertEquals(MatchLevel.RELAXED, m("persons_involved", "Ravi; Priya", "Priya (injured), Ravi"))
        assertEquals(MatchLevel.NONE, m("persons_involved", "Ravi; Priya", "Ravi"))
        // 2 of 3 content words = 0.67 >= 0.6, 1 of 3 is not
        assertEquals(MatchLevel.RELAXED, m("immediate_actions_taken", "wet floor sign", "wet floor"))
        assertEquals(MatchLevel.NONE, m("immediate_actions_taken", "wet floor sign", "wet"))
        // an expected text without content words can only match normalised
        assertEquals(MatchLevel.NONE, m("incident_description", "the", "a b"))
    }
}

class MappingTest {
    @Test fun classifiesCorrectMisplacedWrongAndMissing() {
        val expected = mapOf(
            "incident_location" to "Conference Room 2",
            "date_time" to "10 AM",
            "incident_type" to "Water Leak",
            "injury" to "false",
        )
        val actual = mapOf<String, String?>(
            "incident_location" to "Conference Room 2 around 10 AM", // right, but the time ended up here too
            "date_time" to null, // the time is in the location field only
            "incident_type" to "Fire",
            "injury" to null,
            "incident_description" to "Water leak in Conference Room 2 around 10 AM", // never counts as "elsewhere"
        )
        val p = Mapping.classify(expected, actual)
        assertEquals(Placement.CORRECT, p["incident_location"])
        assertEquals(Placement.MISPLACED, p["date_time"])
        assertEquals(Placement.WRONG, p["incident_type"])
        assertEquals(Placement.MISSING, p["injury"])
        assertEquals(0.5, Mapping.placementRate(correct = 1, misplaced = 1)!!, 1e-9)
    }

    @Test fun descriptionIsNotASearchTarget() {
        val p = Mapping.classify(
            mapOf("incident_location" to "Block B"),
            mapOf("incident_location" to null, "incident_description" to "Fire in Block B"),
        )
        assertEquals(Placement.MISSING, p["incident_location"])
    }

    @Test fun valueInAnotherFieldIsMisplaced() {
        val p = Mapping.classify(
            mapOf("incident_location" to "server room"),
            mapOf("incident_location" to "the lab", "immediate_actions_taken" to "server room"),
        )
        assertEquals(Placement.MISPLACED, p["incident_location"])
    }

    @Test fun placementRateIsNullWhenNothingWasExtracted() {
        assertNull(Mapping.placementRate(0, 0))
        assertEquals(1.0, Mapping.placementRate(3, 0)!!, 1e-9)
        assertEquals(0.0, Mapping.placementRate(0, 2)!!, 1e-9)
    }
}
