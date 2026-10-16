package com.example.test_app.eval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalControllerTest {
    @Test fun publishesProgressThenTheSummary() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = EvalController(this) { input, progress ->
            progress(EvalProgress(1, 3, "E02"))
            gate.await()
            EvalFinished(listOf("3 cases"), "/data/files/eval_runs/x/results.json").also { assertEquals(EvalInput.AUDIO, input) }
        }
        assertEquals(EvalUiState.Idle, c.state.value)
        c.start(EvalInput.AUDIO)
        assertTrue(c.state.value is EvalUiState.Running)
        advanceUntilIdle()
        assertEquals(EvalUiState.Running(EvalInput.AUDIO, 1, 3, "E02"), c.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(EvalUiState.Done(listOf("3 cases"), "/data/files/eval_runs/x/results.json"), c.state.value)
    }

    @Test fun aSecondStartWhileRunningIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val c = EvalController(this) { _, _ ->
            runs++
            gate.await()
            EvalFinished(emptyList(), "p")
        }
        c.start(EvalInput.TRANSCRIPT)
        advanceUntilIdle()
        c.start(EvalInput.TRANSCRIPT)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runs)
        c.start(EvalInput.TRANSCRIPT) // allowed again once finished
        advanceUntilIdle()
        assertEquals(2, runs)
    }

    @Test fun aFailureBecomesAFailedState() = runTest {
        val c = EvalController(this) { _, _ -> error("cases.json is broken") }
        c.start(EvalInput.TRANSCRIPT)
        advanceUntilIdle()
        assertEquals(EvalUiState.Failed("cases.json is broken"), c.state.value)
    }
}
