package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.SourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec sections 12, 13 and 20: approval, source and expiry filtering, for ordinary users and for the admin preview. */
class EngineApprovalTest {
    private fun graph(configure: PackBuilder.() -> Unit): PackBuilder = PackBuilder().incidentType("FIRE").team("ops").apply(configure)

    @Test fun anApprovedRecommendationOnAnApprovedSourceIsShown() {
        val set = PackBuilder.basic("isolate").run()
        assertEquals(listOf("rec:isolate"), set.ids())
        val item = set.immediateActions.single()
        assertEquals("sop:ok", item.sourceId); assertEquals("Test guidance", item.sourceLabel); assertFalse(item.unverifiedDraft)
        assertEquals("Test guidance", set.guidanceLabel)
        assertTrue(item.provenance.single().contains("incident-type:FIRE -TRIGGERS_ACTION-> action:isolate"))
    }

    @Test fun draftAndReviewedRecommendationsAreHiddenFromReportersAndLabelledForAdmins() {
        for (status in listOf(ApprovalStatus.DRAFT, ApprovalStatus.REVIEWED)) {
            val b = graph { action("a").rec("a", status = status).trigger("FIRE", "a") }
            val reporter = b.run()
            assertTrue(reporter.ids().isEmpty()); assertTrue(reporter.whyNot("rec:a")!!.contains("not approved"))
            val admin = b.run(audience = Audience.ADMIN_PREVIEW)
            assertEquals(listOf("rec:a"), admin.ids()); assertTrue(admin.allRecommendations.single().unverifiedDraft)
            assertTrue(admin.warnings.any { it.contains("draft") })
        }
    }

    @Test fun disabledRecommendationsAreHiddenFromEveryone() {
        val b = graph { action("a").rec("a", status = ApprovalStatus.DISABLED).trigger("FIRE", "a") }
        assertTrue(b.run().ids().isEmpty()); assertTrue(b.run(audience = Audience.ADMIN_PREVIEW).ids().isEmpty())
        assertTrue(b.run().whyNot("rec:a")!!.contains("DISABLED"))
    }

    @Test fun anExpiredRecommendationStatusOrDateIsNeverShown() {
        val byStatus = graph { action("a").rec("a", status = ApprovalStatus.EXPIRED).trigger("FIRE", "a") }
        assertTrue(byStatus.run(audience = Audience.ADMIN_PREVIEW).ids().isEmpty())
        val byDate = graph { action("a").rec("a", expiresAt = "2026-10-10T00:00:00Z").trigger("FIRE", "a") }
        assertTrue(byDate.run().ids().isEmpty()); assertTrue(byDate.run().whyNot("rec:a")!!.contains("expired"))
        val future = graph { action("a").rec("a", expiresAt = "2027-01-01T00:00:00Z").trigger("FIRE", "a") }
        assertEquals(listOf("rec:a"), future.run().ids())
    }

    /** Required negative test: do not show expired SOP guidance. */
    @Test fun guidanceOnAnExpiredSopIsNotShownToAnyone() {
        val b = graph {
            source(KgSource("sop:old", "Old SOP", approvedBy = "R", approvedAt = OK_AT, expiresAt = "2026-09-01T00:00:00+05:30", status = SourceStatus.APPROVED))
            action("a").rec("a", source = "sop:old").trigger("FIRE", "a")
        }
        for (audience in Audience.entries) {
            val set = b.run(audience = audience)
            assertTrue(set.ids().isEmpty()); assertTrue(set.whyNot("rec:a")!!.contains("expired"))
        }
    }

    @Test fun anUnreadableExpiryFailsClosed() {
        val b = graph {
            source(KgSource("sop:bad", "Bad expiry", approvedBy = "R", approvedAt = OK_AT, expiresAt = "soon", status = SourceStatus.APPROVED))
            action("a").rec("a", source = "sop:bad").trigger("FIRE", "a")
        }
        assertTrue(b.run().ids().isEmpty())
    }

    @Test fun anUnverifiedSourceHidesTheGuidanceFromReportersOnly() {
        val b = graph {
            source(KgSource("sop:draft", "Needs review", status = SourceStatus.NEEDS_ADMIN_VERIFICATION))
            action("a").rec("a", source = "sop:draft").trigger("FIRE", "a")
        }
        val reporter = b.run()
        assertTrue(reporter.ids().isEmpty()); assertTrue(reporter.whyNot("rec:a")!!.contains("not approved"))
        val admin = b.run(audience = Audience.ADMIN_PREVIEW)
        assertEquals(listOf("rec:a"), admin.ids()); assertTrue(admin.allRecommendations.single().unverifiedDraft)
    }

    @Test fun aRejectedSourceIsNeverUsed() {
        val b = graph {
            source(KgSource("sop:no", "Rejected", status = SourceStatus.REJECTED))
            action("a").rec("a", source = "sop:no").trigger("FIRE", "a")
        }
        assertTrue(b.run(audience = Audience.ADMIN_PREVIEW).ids().isEmpty())
    }

    @Test fun aSourcelessRecommendationOrActionIsSuppressed() {
        val noSupport = graph { action("a").rec("a", source = null).trigger("FIRE", "a") }
        assertTrue(noSupport.run().whyNot("rec:a")!!.contains("sourceless"))
        val noRecord = graph { action("b").trigger("FIRE", "b") }
        assertTrue(noRecord.run().whyNot("action:b")!!.contains("no recommendation record"))
        val dangling = graph { action("c").rec("c", source = "sop:missing").trigger("FIRE", "c") }
        assertTrue(dangling.run().whyNot("rec:c")!!.contains("not found"))
    }

    @Test fun anApprovedRecommendationWithoutReviewerOrTimeIsHiddenFromReporters() {
        assertTrue(graph { action("a").rec("a", approvedBy = null).trigger("FIRE", "a") }.run().ids().isEmpty())
        assertTrue(graph { action("a").rec("a", approvedAt = null).trigger("FIRE", "a") }.run().whyNot("rec:a")!!.contains("approval record incomplete"))
    }

    @Test fun unapprovedInactiveOrSourcelessEdgesAreNotTraversed() {
        val unapproved = graph { action("a").rec("a").trigger("FIRE", "a", status = ApprovalStatus.DRAFT) }
        assertTrue(unapproved.run().ids().isEmpty())
        assertEquals(listOf("rec:a"), unapproved.run(audience = Audience.ADMIN_PREVIEW).ids())
        val inactive = graph { action("a").rec("a").edge("incident-type:FIRE", com.example.test_app.knowledgegraph.model.Relation.TRIGGERS_ACTION, "action:a", active = false) }
        assertTrue(inactive.run().ids().isEmpty()); assertTrue(inactive.run(audience = Audience.ADMIN_PREVIEW).ids().isEmpty())
        val badRef = graph { action("a").rec("a").edge("incident-type:FIRE", com.example.test_app.knowledgegraph.model.Relation.TRIGGERS_ACTION, "action:a", sourceRef = "sop:missing") }
        assertTrue(badRef.run().ids().isEmpty())
    }

    @Test fun anInactiveActionOrNodeIsDisabled() {
        val b = graph { action("a").rec("a").trigger("FIRE", "a") }
        val pack = b.build()
        val off = pack.copy(nodes = pack.nodes.map { if (it.id == "action:a") it.copy(active = false) else it })
        val set = engineFor(off).recommend(incident())
        assertTrue(set.ids().isEmpty()); assertNotNull(set.whyNot("action:a"))
        val recOff = pack.copy(nodes = pack.nodes.map { if (it.id == "rec:a") it.copy(active = false) else it })
        assertTrue(engineFor(recOff).recommend(incident()).ids().isEmpty())
    }

    @Test fun anUnconfirmedIncidentIsRejected() {
        val set = PackBuilder.basic("a").run(incident(confirmed = false))
        assertTrue(set.abstained); assertTrue(set.ids().isEmpty())
        assertTrue(set.suppressed.single().reason.contains("not confirmed"))
    }

    @Test fun theDemoLabelAppearsOnlyWhenAShownItemUsesALabelledSource() {
        val b = graph {
            source(KgSource("sop:plain", "No label", approvedBy = "R", approvedAt = OK_AT, status = SourceStatus.APPROVED))
            action("a").rec("a", source = "sop:plain").trigger("FIRE", "a")
        }
        assertNull(b.run().guidanceLabel)
    }
}
