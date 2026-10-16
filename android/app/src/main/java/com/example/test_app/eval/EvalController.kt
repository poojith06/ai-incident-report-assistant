package com.example.test_app.eval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the developer menu shows about an evaluation run. */
sealed interface EvalUiState {
    data object Idle : EvalUiState

    data class Running(val input: EvalInput, val done: Int, val total: Int, val currentId: String?) : EvalUiState

    /** [lines] is the headline summary ([EvalReport.headline]); [resultsPath] the `results.json` on the device. */
    data class Done(val lines: List<String>, val resultsPath: String) : EvalUiState

    data class Failed(val message: String) : EvalUiState
}

/** What a finished run hands to the UI. */
class EvalFinished(val lines: List<String>, val resultsPath: String)

/** Starts one evaluation run at a time on [scope] and publishes its progress. [execute] does the actual work (the app container). */
class EvalController(
    private val scope: CoroutineScope,
    private val execute: suspend (EvalInput, (EvalProgress) -> Unit) -> EvalFinished,
) {
    private val _state = MutableStateFlow<EvalUiState>(EvalUiState.Idle)
    val state: StateFlow<EvalUiState> = _state

    private var job: Job? = null

    /** Ignored while a run is in progress. */
    fun start(input: EvalInput) {
        if (job?.isActive == true) return
        _state.value = EvalUiState.Running(input, 0, 0, null)
        job = scope.launch {
            _state.value = try {
                val finished = execute(input) { p -> _state.value = EvalUiState.Running(input, p.done, p.total, p.currentId) }
                EvalUiState.Done(finished.lines, finished.resultsPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EvalUiState.Failed(e.message ?: e::class.simpleName ?: "Evaluation failed")
            }
        }
    }
}
