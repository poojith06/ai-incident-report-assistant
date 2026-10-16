package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.rules.MedicalOutputGuard
import com.example.test_app.knowledgegraph.rules.PhoneNumberGuard
import com.example.test_app.knowledgegraph.rules.SafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec sections 3, 13 and 20: the safety and medical hard filters and every required negative test. */
class EngineSafetyTest {
    private fun with(text: String, userCan: Boolean = true, auth: Boolean = false, medicalClass: String? = null, configure: PackBuilder.() -> Unit = {}): PackBuilder =
        PackBuilder().incidentType("FIRE").team("ops").apply(configure).action("x", text = text, userCan = userCan, auth = auth, medicalClass = medicalClass).rec("x").trigger("FIRE", "x")

    private fun blocked(text: String, userCan: Boolean = true, i: com.example.test_app.knowledgegraph.model.ValidatedIncident = incident()): String? =
        with(text, userCan).run(i).whyNot("rec:x")

    /** Required negative test: do not advise restarting fire-damaged equipment. */
    @Test fun doesNotAdviseRestartingFireDamagedEquipment() {
        for (t in listOf("Restart the computer to check whether it still works.", "Power on the damaged desktop and look for errors.", "Reboot the machine.")) {
            assertTrue(t, blocked(t, true, incident(hazards = listOf("SMOKE")))!!.contains("restarting"))
            // even an "authorized" instruction must not suggest restarting equipment after a fire
            assertTrue(t, blocked(t, false, incident(hazards = listOf("ELECTRICAL_DAMAGE")))!!.contains("restarting"))
        }
        assertNull("a prohibition is fine", blocked("Do not restart or power on the damaged equipment.", true, incident(hazards = listOf("SMOKE"))))
    }

    /** Required negative test: do not advise touching an electrical device near water. */
    @Test fun doesNotAdviseTouchingAnElectricalDeviceNearWater() {
        val i = incident(type = "FIRE", hazards = listOf("WATER_NEAR_POWER"))
        for (t in listOf("Unplug the power strip.", "Move the extension cord away from the water.", "Touch the switch to check if it is off.")) {
            assertTrue(t, blocked(t, true, i) != null)
        }
        assertNull(blocked("Do not touch the power strip. Keep others away from it.", true, i))
    }

    @Test fun anAuthorizedOnlyActionIsNeverAddressedToTheReporter() {
        val set = with("Ask authorized electricians to isolate the supply.", userCan = true, auth = true).run()
        assertTrue(set.whyNot("rec:x")!!.contains("authorized-only"))
        assertEquals(listOf("rec:x"), with("Ask authorized electricians to isolate the supply.", userCan = false, auth = true).run().ids())
    }

    @Test fun reporterActionsCannotSendTheUserIntoDangerOrToFightFiresOrHandleChemicals() {
        assertTrue(blocked("Go into the room and check for smoke.")!!.contains("enter"))
        assertTrue(blocked("Extinguish the fire with the nearest extinguisher.")!!.contains("fight a fire"))
        assertTrue(blocked("Switch off the main switch of the lab.")!!.contains("electrical"))
        assertTrue(blocked("Shut off the gas valve.")!!.contains("shut-off"))
        assertTrue(blocked("Clean up the spill with paper towels.")!!.contains("chemicals"))
        assertNull(blocked("Move away and keep others out of the area until responders arrive."))
        assertNull(blocked("Stay away from the room and report it to the lab safety officer."))
    }

    @Test fun technicalVerbsAreFineWhenAddressedToAuthorizedStaff() {
        assertEquals(listOf("rec:x"), with("Ask authorized electricians to isolate the power and inspect the wiring.", userCan = false, auth = true).run().ids())
    }

    @Test fun medicalClassesOutsideTheAllowedSetAreBlocked() {
        assertTrue(with("Ask first-aid responders to attend.", userCan = false, auth = false, medicalClass = "TREATMENT").run().whyNot("rec:x")!!.contains("medical action class"))
        for (c in SafetyPolicy.ALLOWED_MEDICAL_CLASSES) {
            assertEquals(listOf("rec:x"), with("Contact campus medical services or qualified emergency responders and follow their instructions.", false, false, c).run().ids())
        }
    }

    /** Required negative tests: no diagnosis of a sports injury, no medicine or dosage. */
    @Test fun medicalOutputGuardBlocksDiagnosisMedicineDosageAndTreatment() {
        val diagnoses = listOf("You have a sprained ankle.", "It is a fracture, so rest it.", "This looks like a concussion.", "The player has a torn ligament.", "Diagnosis: heat stroke.")
        val medicine = listOf("Take paracetamol for the pain.", "Give him 500 mg of ibuprofen.", "Take two tablets after food.", "Apply the ointment twice a day.", "Dosage is one pill.")
        val treatment = listOf("Treat the burn with cold water.", "Apply ice and a bandage.", "Use a splint to hold the arm.", "A home remedy is to drink water.")
        for (t in diagnoses + medicine + treatment) assertTrue(t, MedicalOutputGuard.violations(t).isNotEmpty())
        for (t in listOf("Ask campus medical services or qualified emergency responders to attend.", "Stop play and keep others away from the area.", "Follow the responders' instructions."))
            assertTrue(t, MedicalOutputGuard.isClean(t))
    }

    @Test fun anActionWithDiagnosisOrMedicineTextIsSuppressedWithTheRule() {
        for (t in listOf("You have a sprained ankle, ask staff to look.", "Take paracetamol 500 mg and wait.", "Ask staff to treat the wound.")) {
            val why = with(t, userCan = false, auth = false).run(incident(type = "FIRE", injury = true)).whyNot("rec:x")
            assertTrue("$t -> $why", why!!.contains("medical output rule"))
        }
    }

    @Test fun anInjuryIncidentCarriesTheMedicalFooter() {
        val set = PackBuilder.basic("a").run(incident(injury = true))
        assertEquals(MedicalOutputGuard.FOOTER, set.medicalFooter)
        assertTrue(set.medicalFooter!!.contains("not medical advice"))
        assertNull(PackBuilder.basic("a").run(incident(injury = false)).medicalFooter)
    }

    @Test fun aMedicalIncidentTypeShowsTheFooterEvenWhenInjuryIsUnknown() {
        val b = PackBuilder().incidentType("FALL", "MEDICAL").team("ops").action("a").rec("a").trigger("FALL", "a")
        assertEquals(MedicalOutputGuard.FOOTER, b.run(incident(type = "FALL", injury = null)).medicalFooter)
    }

    /** Required negative test: do not activate an unverified contact. */
    @Test fun anUnverifiedContactIsNeverShown() {
        val b = PackBuilder.basic("a").node(
            "contact:1", NodeType.EMERGENCY_CONTACT, "c", props("teamId" to "team:ops", "number" to "040 1234 5678", "verifiedAt" to null, "expiresAt" to null, "enabled" to true),
        )
        val team = b.run().responsibleTeams.single()
        assertNull(team.contactNumber); assertFalse(team.verified)
    }

    @Test fun onlyAVerifiedEnabledUnexpiredContactIsShown() {
        fun contact(verified: VerificationStatus, enabled: Boolean, verifiedAt: String?, expiresAt: String?) = PackBuilder.basic("a").node(
            "contact:1", NodeType.EMERGENCY_CONTACT, "c", props("teamId" to "team:ops", "number" to "040 1234 5678", "verifiedAt" to verifiedAt, "expiresAt" to expiresAt, "enabled" to enabled), verified,
        ).run().responsibleTeams.single().contactNumber
        assertEquals("040 1234 5678", contact(VerificationStatus.VERIFIED, true, OK_AT, null))
        assertEquals("040 1234 5678", contact(VerificationStatus.VERIFIED, true, OK_AT, "2027-01-01T00:00:00Z"))
        assertNull(contact(VerificationStatus.VERIFIED, false, OK_AT, null))
        assertNull(contact(VerificationStatus.VERIFIED, true, null, null))
        assertNull(contact(VerificationStatus.VERIFIED, true, OK_AT, "2026-01-01T00:00:00Z"))
        assertNull(contact(VerificationStatus.NEEDS_ADMIN_VERIFICATION, true, OK_AT, null))
        assertNull(contact(VerificationStatus.REJECTED, true, OK_AT, null))
    }

    @Test fun textWithAnUnverifiedPhoneNumberIsSuppressedButAVerifiedNumberMayAppear() {
        val text = "Ask the security desk on 040 2345 6789 to attend."
        assertTrue(with(text, userCan = false).run().whyNot("rec:x")!!.contains("phone number"))
        val verified = with(text, userCan = false) {
            node("contact:s", NodeType.EMERGENCY_CONTACT, "s", props("teamId" to "team:ops", "number" to "040 2345 6789", "verifiedAt" to OK_AT, "enabled" to true), VerificationStatus.VERIFIED)
        }
        assertEquals(listOf("rec:x"), verified.run().ids())
        assertTrue(PhoneNumberGuard.containsUnverifiedNumber("Call 1234 now"))
        assertFalse(PhoneNumberGuard.containsUnverifiedNumber("Stay at least away from the room."))
    }

    @Test fun neverClaimsHelpWasDispatched() {
        val set = PackBuilder.basic("a").run()
        val all = (set.allRecommendations.map { it.renderedText } + set.warnings).joinToString(" ").lowercase()
        assertFalse(all.contains("dispatched") || all.contains("on the way") || all.contains("notified"))
    }

    @Test fun adminPreviewDoesNotRelaxTheSafetyFilters() {
        val b = with("Extinguish the fire.")
        assertTrue(b.run(audience = Audience.ADMIN_PREVIEW).ids().isEmpty())
        val draftMedical = with("Take paracetamol.", userCan = false) // approved status irrelevant: the text is blocked
        assertTrue(draftMedical.run(audience = Audience.ADMIN_PREVIEW).ids().isEmpty())
    }

    @Test fun aDraftApprovalStatusStillGoesThroughTheSafetyChecks() {
        val b = PackBuilder().incidentType("FIRE").team("ops").action("x", "Extinguish the fire.", userCan = true, auth = false).rec("x", status = ApprovalStatus.DRAFT).trigger("FIRE", "x")
        assertTrue(b.run(audience = Audience.ADMIN_PREVIEW).ids().isEmpty())
    }
}
