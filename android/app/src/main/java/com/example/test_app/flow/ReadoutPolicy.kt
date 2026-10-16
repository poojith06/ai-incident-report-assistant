package com.example.test_app.flow

import com.example.test_app.dialogue.FormReadout
import com.example.test_app.dialogue.LanguagePack

/**
 * Which text (if any) is spoken after a flow transition. The read-out is tied to the EVENT, not to the state: `Reviewing` is
 * re-entered on every field edit and must not speak again, so only the first entry (`FormFilled`) reads the form, and only
 * `SubmitSucceeded` reads the confirmation. After a dialogue that ended with its read-back statement (ADR-048) the automatic form
 * read-out is skipped (`dialogueReadBack`), it would repeat the same news; the speaker button still reads the form. Pure; used by `IncidentFlowViewModel.dispatch`. See `doc/STATE_MACHINE.md`.
 */
object ReadoutPolicy {
    /** [pack] = the language of the read-out (the report's language); null = English. */
    fun textFor(event: FlowEvent, newState: FlowState, dialogueReadBack: Boolean = false, pack: LanguagePack? = null): String? = when {
        // The dialogue's read-back statement ("Logged: ... Please review and tap submit.") was just spoken: do not read the form again.
        event is FlowEvent.FormFilled && newState is FlowState.Reviewing && dialogueReadBack -> null
        event is FlowEvent.FormFilled && newState is FlowState.Reviewing -> FormReadout.review(newState.report, pack)
        event is FlowEvent.SubmitSucceeded && newState is FlowState.Submitted -> FormReadout.submitted(newState.report, pack)
        else -> null
    }
}
