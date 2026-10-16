package com.example.test_app.flow

import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.Validation
import com.example.test_app.domain.model.textValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The read-out is spoken once per Review entry and once after submit, never on edits. */
class ReadoutPolicyTest {
    private val r = IncidentReport(
        reportId = "INC-2026-000001", status = "x", createdAt = "t", input = ReportInput("voice", "t"),
        fields = mapOf("incident_type" to FieldEntry(textValue("Fire"), 0.9)), validation = Validation(), clarification = Clarification(),
    )

    @Test fun formFilledReadsTheFormOnce() {
        val state = FlowReducer.reduce(FlowState.FillingForm(r, manual = false), FlowEvent.FormFilled(r))
        val text = ReadoutPolicy.textFor(FlowEvent.FormFilled(r), state)
        assertNotNull(text)
        assertTrue(text!!.startsWith("Your report is ready."))
    }

    @Test fun editsStayOnReviewAndDoNotSpeak() {
        val review = FlowState.Reviewing(r)
        val edited = FlowReducer.reduce(review, FlowEvent.FieldEdited(r.copy(status = "edited")))
        assertTrue(edited is FlowState.Reviewing)
        assertNull(ReadoutPolicy.textFor(FlowEvent.FieldEdited(r), edited))
    }

    @Test fun returningToReviewFromAnErrorDoesNotSpeak() {
        val err = FlowState.Error(FlowError.SUBMIT_FAILURE, null, FlowState.Submitting(r), FlowState.Reviewing(r))
        val back = FlowReducer.reduce(err, FlowEvent.GoBack)
        assertEquals(FlowState.Reviewing(r), back)
        assertNull(ReadoutPolicy.textFor(FlowEvent.GoBack, back))
    }

    @Test fun submitSuccessReadsTheConfirmationOnce() {
        val s = FlowReducer.reduce(FlowState.Submitting(r), FlowEvent.SubmitSucceeded(r))
        assertEquals("Report submitted. Reference I N C, 2026, 0 0 0 0 0 1.", ReadoutPolicy.textFor(FlowEvent.SubmitSucceeded(r), s))
        assertNull(ReadoutPolicy.textFor(FlowEvent.ReportAnother, FlowState.Idle))
    }

    @Test fun otherEventsNeverSpeak() {
        assertNull(ReadoutPolicy.textFor(FlowEvent.StartVoice, FlowState.Idle))
        assertNull(ReadoutPolicy.textFor(FlowEvent.Submit, FlowState.Submitting(r)))
    }
}
