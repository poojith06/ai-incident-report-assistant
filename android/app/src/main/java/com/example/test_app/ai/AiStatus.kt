package com.example.test_app.ai

/** State of the on-device models, shown on Home ("Loading models..."). See `doc/SERVICES_AND_MOCKS.md`. */
sealed interface AiStatus {
    /** Mock AI is selected: nothing to load. */
    data object Mock : AiStatus

    /** Real AI is selected and the model is loading off the main thread. The mic stays usable; processing waits. */
    data object Loading : AiStatus

    data object Ready : AiStatus

    /** Loading failed; [message] is for the user. Retried by the next call or by Retry on the error screen. */
    data class Failed(val message: String) : AiStatus
}
