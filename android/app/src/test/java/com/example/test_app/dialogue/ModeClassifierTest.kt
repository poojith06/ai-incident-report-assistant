package com.example.test_app.dialogue

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeClassifierTest {
    private val c = ModeClassifier(TestAssets.assets.modeKeywords)

    private fun rule(vararg texts: String) = c.ruleMode(texts.toList())

    @Test fun plainWhenNoKeywords() {
        assertEquals(DialogueMode.PLAIN, rule("There was a water leak near Conference Room 2 around 10 AM."))
    }

    @Test fun urgentKeywords() {
        assertEquals(DialogueMode.URGENT, rule("There's smoke coming from the server room!"))
        assertEquals(DialogueMode.URGENT, rule("I think there is a gas leak in the lab"))
        assertEquals(DialogueMode.URGENT, rule("He is not breathing"))
        assertEquals(DialogueMode.URGENT, rule("Someone has a knife"))
    }

    @Test fun urgentUsesAnyTranscriptAndWinsOverCare() {
        assertEquals(DialogueMode.URGENT, rule("I am hurt", "fire in the kitchen"))
    }

    @Test fun careKeywords() {
        assertEquals(DialogueMode.CARE, rule("I slipped in the pantry and I'm hurt."))
        assertEquals(DialogueMode.CARE, rule("I cut my hand on the door"))
        assertEquals(DialogueMode.CARE, rule("Someone has been harassing me near the parking lot."))
        assertEquals(DialogueMode.CARE, rule("My bag was stolen"))
    }

    @Test fun wholeWordsOnly() {
        assertEquals(DialogueMode.PLAIN, rule("The fireplace in the lobby is cracked"))
        assertEquals(DialogueMode.PLAIN, rule("The painting is hanging crooked"))
    }

    @Test fun brokeNeedsAPersonOrBone() {
        assertEquals(DialogueMode.PLAIN, rule("The door handle broke."))
        assertEquals(DialogueMode.CARE, rule("I broke my wrist"))
    }

    // ---- negation guard ----

    @Test fun negationNoOneWasHurt() = assertEquals(DialogueMode.PLAIN, rule("No one was hurt but the shelf tipped over"))
    @Test fun negationNobodyGotHurt() = assertEquals(DialogueMode.PLAIN, rule("Nobody got hurt, the printer gave off a spark"))
    @Test fun negationNotInjured() = assertEquals(DialogueMode.PLAIN, rule("Thankfully she is not injured"))
    @Test fun negationNoInjuries() = assertEquals(DialogueMode.PLAIN, rule("Water on the floor, no injuries"))
    @Test fun negationNobodyWasHurtWithFiller() = assertEquals(DialogueMode.PLAIN, rule("Nobody was hurt but the printer gave off a spark in room 4."))
    @Test fun negationContraction() = assertEquals(DialogueMode.PLAIN, rule("Luckily he wasn't hurt"))
    @Test fun negationHinglish() = assertEquals(DialogueMode.PLAIN, rule("koi chot nahi aayi"))

    @Test fun negationExactlyFourTokensBeforeStillNegates() {
        // "no" is the 4th token before "hurt": no, one, really, was -> window of 4 contains it.
        assertEquals(DialogueMode.PLAIN, rule("no one really was hurt"))
    }

    @Test fun negationFiveTokensBeforeDoesNotNegate() {
        // "no" is the 5th token before "hurt": window of 4 misses it.
        assertEquals(DialogueMode.CARE, rule("no one at all really was hurt"))
    }

    @Test fun negationDoesNotCrossClauses() {
        assertEquals(DialogueMode.CARE, rule("No, I am hurt"))
        assertEquals(DialogueMode.CARE, rule("Nobody else saw it. I'm hurt."))
    }

    @Test fun negationOnlyAppliesToInjuryKeywords() {
        // "scared" and "harass" are not injury-related: a negation word nearby does not cancel them.
        assertEquals(DialogueMode.CARE, rule("I am not going to lie, I am scared"))
        assertEquals(DialogueMode.CARE, rule("He did not stop harassing me"))
    }

    @Test fun negationDoesNotCancelUrgent() {
        assertEquals(DialogueMode.URGENT, rule("There is no way out, I am trapped"))
    }

    // ---- distress cues (urgent, with the negation guard) ----

    @Test fun distressPhrasesAreUrgent() {
        assertEquals(DialogueMode.URGENT, rule("Please come quick, I need help"))
        assertEquals(DialogueMode.URGENT, rule("It's very urgent"))
        assertEquals(DialogueMode.URGENT, rule("In the football ground, some guy fell and he is seriously bleeding."))
        assertEquals(DialogueMode.URGENT, rule("She is bleeding badly"))
    }

    @Test fun distressPhrasesAreNegationGuarded() {
        assertEquals(DialogueMode.PLAIN, rule("I don't need help, the leak is small"))
        assertEquals(DialogueMode.PLAIN, rule("We do not need help with this"))
        assertEquals(DialogueMode.PLAIN, rule("It is not very urgent"))
        assertEquals(DialogueMode.PLAIN, rule("no one said they need help"))
    }

    @Test fun nearbyWordsThatAreNotDistressDoNotTrigger() {
        assertEquals(DialogueMode.PLAIN, rule("The helpdesk was informed"))
        assertEquals(DialogueMode.PLAIN, rule("Someone needs helpful tips"))
        // plain "urgent" alone is NOT a mode cue (only "very urgent"); severity cues use it, see SeverityPolicyTest
        assertEquals(DialogueMode.PLAIN, rule("This is urgent but harmless"))
    }

    // ---- model mode ----

    @Test fun modelModeFromInjuryAndSeverity() {
        assertEquals(DialogueMode.PLAIN, c.modelMode(null, null))
        assertEquals(DialogueMode.PLAIN, c.modelMode(false, "Low"))
        assertEquals(DialogueMode.CARE, c.modelMode(true, "Medium"))
        assertEquals(DialogueMode.URGENT, c.modelMode(false, "Critical"))
        assertEquals(DialogueMode.URGENT, c.modelMode(true, "Critical"))
    }

    @Test fun clipLabelsFireAndSmokeEscalateOnlyAboveHalf() {
        assertEquals(DialogueMode.URGENT, c.modelMode(null, null, ClipLabel("fire", 0.7f)))
        assertEquals(DialogueMode.PLAIN, c.modelMode(null, null, ClipLabel("fire", 0.4f)))
        assertEquals(DialogueMode.PLAIN, c.modelMode(null, null, ClipLabel("desk", 0.9f)))
    }

    @Test fun finalModeIsMaxOfRuleAndModel() {
        assertEquals(DialogueMode.CARE, c.classify(listOf("Water on the floor"), true, null))
        assertEquals(DialogueMode.URGENT, c.classify(listOf("smoke"), true, null))
        assertEquals(DialogueMode.URGENT, c.classify(listOf("I am hurt"), null, "Critical"))
    }

    @Test fun personalIncidentDetection() {
        assertEquals(true, c.isPersonalIncident("Harassment", "x"))
        assertEquals(true, c.isPersonalIncident(null, "he threatened me"))
        assertEquals(false, c.isPersonalIncident("Water Leak", "a leak in the pantry"))
    }
}
