package com.example.test_app.knowledgegraph.rules

import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.ValidatedIncident

/**
 * Spec section 3 and 13: what a reporter-facing recommendation may ask the user to do, and the hard filters of the engine that depend on
 * the action text. Rules (each returns a reason string when it blocks):
 *  - an action that is `requiresAuthorization` must not be addressed to the user (`userCanPerform`);
 *  - a user-performable action must not tell the user to enter a danger area, fight a fire, touch or switch electrical equipment,
 *    operate a utility shut-off, handle chemicals or clean up spills, or restart / power on damaged equipment;
 *  - near water and power together nothing may be touched; fire-damaged equipment is never restarted (the verbs are blocked for every
 *    user-performable action, and for ANY action when the incident has fire, smoke or electrical damage: "restart" is never advice then);
 *  - a medical action may only be first aid that is already approved, contacting responders or following responders.
 * Prohibitions ("Do not touch ...", "Stay away from ...") are allowed: a verb preceded by a negation within [NEGATION_WINDOW] characters is fine.
 */
object SafetyPolicy {
    private const val NEGATION_WINDOW = 28
    private val NEGATIONS = Regex("(do not|don't|dont|never|avoid|stay away from|keep away from|keep others away from|keep clear of|must not|should not)", RegexOption.IGNORE_CASE)

    private class Rule(val name: String, val verbs: Regex)

    private val USER_RULES = listOf(
        Rule("tells the user to enter a danger area", Regex("\\b(enter|go into|go inside|walk into|re-?enter|return to the (room|area|building))\\b", RegexOption.IGNORE_CASE)),
        Rule("tells the user to fight a fire", Regex("\\b(extinguish\\w*|fight\\w*( the)? fire|put out)\\b", RegexOption.IGNORE_CASE)),
        Rule("tells the user to touch or operate electrical equipment", Regex("\\b(touch\\w*|unplug\\w*|plug in|switch(ing)? (it )?(off|on)|turn(ing)? (it )?(off|on)|flip\\w*|reset\\w*|disconnect\\w*)\\b", RegexOption.IGNORE_CASE)),
        Rule("tells the user to operate a utility shut-off", Regex("\\b(shut(ting)? off|close the (valve|gas|main)|open the valve|main switch|isolate the (power|gas|water))\\b", RegexOption.IGNORE_CASE)),
        Rule("tells the user to handle chemicals or clean up", Regex("\\b(clean up|clean the|mop\\w*|wipe\\w*|neutrali[sz]e\\w*|absorb\\w*|sweep\\w*|pick up|dispose|handle\\w*)\\b", RegexOption.IGNORE_CASE)),
    )
    private val RESTART = Regex("\\b(restart\\w*|reboot\\w*|power(ing)? on|power(ing)? up|boot up|switch(ing)? it on|turn(ing)? it on|try(ing)? it again)\\b", RegexOption.IGNORE_CASE)
    private val DAMAGE_HAZARDS = setOf("FIRE", "SMOKE", "ELECTRICAL_DAMAGE", "OVERHEATING", "BATTERY_SWELLING")
    private val FIRE_TYPES = Regex("FIRE|SMOKE|SPARK|OVERHEAT|EXPOSED_WIRE|ELECTRICAL")
    val ALLOWED_MEDICAL_CLASSES = setOf("FIRST_AID_IMMEDIATE", "CONTACT_RESPONDERS", "FOLLOW_RESPONDER")

    /** Reasons the ACTION [action] must be suppressed for [incident]; empty = acceptable. */
    fun check(action: KgNode, incident: ValidatedIncident): List<String> {
        val reasons = mutableListOf<String>()
        val text = listOfNotNull(action.str("title"), action.str("canonicalText")).joinToString(". ")
        val userCan = action.bool("userCanPerform") == true
        val needsAuth = action.bool("requiresAuthorization") == true
        if (needsAuth && userCan) reasons += "authorized-only action addressed to the reporter"
        if (userCan) {
            for (rule in USER_RULES) if (unnegatedMatch(rule.verbs, text)) reasons += rule.name
            val waterAndPower = incident.activeHazards.any { it.equals("WATER_NEAR_POWER", true) }
            if (waterAndPower && unnegatedMatch(Regex("\\b(touch\\w*|unplug\\w*|plug\\w*|move\\w*|pull\\w*|grab\\w*)\\b", RegexOption.IGNORE_CASE), text)) {
                reasons += "tells the user to touch a device near water and power"
            }
        }
        val damaged = incident.activeHazards.any { it.uppercase() in DAMAGE_HAZARDS } || incident.type?.uppercase()?.let { FIRE_TYPES.containsMatchIn(it) } == true
        if ((userCan || damaged) && unnegatedMatch(RESTART, text)) reasons += "advises restarting or powering on possibly damaged equipment"
        val medicalClass = action.str("medicalClass")
        if (medicalClass != null && medicalClass !in ALLOWED_MEDICAL_CLASSES) reasons += "medical action class $medicalClass is not allowed"
        return reasons.distinct()
    }

    /** true when [verbs] matches [text] somewhere without a negation just before it. */
    fun unnegatedMatch(verbs: Regex, text: String): Boolean = verbs.findAll(text).any { m ->
        val from = (m.range.first - NEGATION_WINDOW).coerceAtLeast(0)
        !NEGATIONS.containsMatchIn(text.substring(from, m.range.first))
    }
}
