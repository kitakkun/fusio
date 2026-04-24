package com.kitakkun.fusio.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.on
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Self-tests for the `testPresenter` / `testSubPresenter` harness. We
 * reuse real fusio-runtime primitives (`buildPresenter`, `on<>`) rather
 * than stand-ins so these tests double as smoke coverage for the runtime
 * under the test framework's headless Compose setup.
 */
class TestPresenterTest {

    // ---- Fixtures -----------------------------------------------------

    private sealed interface CounterEvent {
        data object Increment : CounterEvent
        data object Reset : CounterEvent
    }

    private sealed interface CounterEffect {
        data class Toast(val message: String) : CounterEffect
        data object Navigated : CounterEffect
    }

    /**
     * A realistic presenter: takes extra arguments (the [initial] seed)
     * alongside the event flow, so this exercises the "bind your own
     * dependencies inside the lambda" path documented in the design doc.
     */
    @Composable
    private fun counterPresenter(
        events: Flow<CounterEvent>,
        initial: Int,
    ): Presentation<Int, CounterEffect> = buildPresenter(events) {
        var count by remember { mutableStateOf(initial) }
        on<CounterEvent.Increment> { count += 1 }
        on<CounterEvent.Reset> {
            count = initial
            emitEffect(CounterEffect.Toast("reset"))
        }
        count
    }

    // ---- Scenario API -------------------------------------------------

    @Test
    fun initial_state_is_visible_before_any_event() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 42) },
    ) {
        assertEquals(42, state)
    }

    @Test
    fun send_advances_state_and_awaitState_unblocks() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        awaitState { it == 0 }
        send(CounterEvent.Increment)
        awaitState { it == 1 }
        send(CounterEvent.Increment)
        awaitState { it == 2 }
    }

    @Test
    fun stateHistory_records_every_distinct_state() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        // Event -> LaunchedEffect collector -> state write -> next frame is
        // async, so `awaitState` between sends makes sure each intermediate
        // value actually lands before the next send overwrites it.
        awaitState { it == 0 }
        send(CounterEvent.Increment); awaitState { it == 1 }
        send(CounterEvent.Increment); awaitState { it == 2 }
        send(CounterEvent.Reset); awaitState { it == 0 }
        assertTrue(
            stateHistory.containsAll(listOf(0, 1, 2)),
            "stateHistory=$stateHistory",
        )
    }

    @Test
    fun awaitEffect_returns_emitted_effect() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 5) },
    ) {
        send(CounterEvent.Reset)
        val toast = awaitEffect<CounterEffect.Toast>()
        assertEquals("reset", toast.message)
    }

    @Test
    fun awaitEffect_fails_on_type_mismatch() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        send(CounterEvent.Reset) // emits Toast
        assertFailsWith<AssertionError> {
            awaitEffect<CounterEffect.Navigated>()
        }
    }

    @Test
    fun expectNoEffects_passes_when_quiet() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        send(CounterEvent.Increment) // no effect emitted
        expectNoEffects(within = 20.milliseconds)
    }

    @Test
    fun expectNoEffects_fails_when_effect_is_queued() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        send(CounterEvent.Reset) // emits Toast
        assertFailsWith<AssertionError> {
            expectNoEffects()
        }
    }

    @Test
    fun awaitState_times_out_when_predicate_never_matches() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        assertFailsWith<AssertionError> {
            awaitState(timeout = 50.milliseconds) { it == 99 }
        }
    }

    // ---- testSubPresenter ---------------------------------------------

    @Test
    fun testSubPresenter_wraps_a_sub_presenter_in_buildPresenter() =
        // Explicit <E, S, Eff> — this sub-presenter doesn't emit any effect,
        // so the compiler has nothing to infer the Effect type from.
        testSubPresenter<CounterEvent, Int, CounterEffect>(
            subPresenter = {
                var count by remember { mutableStateOf(0) }
                on<CounterEvent.Increment> { count += 1 }
                count
            },
        ) {
            assertEquals(0, state)
            send(CounterEvent.Increment)
            awaitState { it == 1 }
        }

    // ---- Phase 2: assertState (fail-fast predicate) -------------------

    @Test
    fun assertState_returns_current_state_when_predicate_matches() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 7) },
    ) {
        val snapshot = assertState { it == 7 }
        assertEquals(7, snapshot)
    }

    @Test
    fun assertState_fails_fast_without_waiting() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        val err = assertFailsWith<AssertionError> {
            assertState(message = "expected 99") { it == 99 }
        }
        // The failure message threads through the caller-supplied label,
        // the current state, and the observed-history trace.
        val msg = err.message ?: ""
        assertTrue("expected 99" in msg, "missing label in: $msg")
        assertTrue("Current state" in msg, "missing state in: $msg")
        assertTrue("Observed states" in msg, "missing history in: $msg")
    }

    // ---- Phase 2: improved failure messages --------------------------

    @Test
    fun awaitState_timeout_message_includes_history_and_pending_effects() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        send(CounterEvent.Reset) // emits Toast into the effect queue
        val err = assertFailsWith<AssertionError> {
            awaitState(timeout = 30.milliseconds) { it == 99 }
        }
        val msg = err.message ?: ""
        assertTrue("Observed states" in msg, "missing history in: $msg")
        assertTrue("Pending effects" in msg, "missing pending effects in: $msg")
    }

    @Test
    fun await_message_annotation_threads_into_failure_text() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        val err = assertFailsWith<AssertionError> {
            awaitState(message = "after reset", timeout = 20.milliseconds) { it == 99 }
        }
        assertTrue(
            "after reset" in (err.message ?: ""),
            "caller-supplied message should appear in failure text, got: ${err.message}",
        )

        send(CounterEvent.Reset) // emits a Toast
        val err2 = assertFailsWith<AssertionError> {
            expectNoEffects(message = "handlers should be silent")
        }
        assertTrue(
            "handlers should be silent" in (err2.message ?: ""),
            "caller-supplied message missing in expectNoEffects failure",
        )
    }

    // ---- Phase 2: recordStateHistory = false -------------------------

    @Test
    fun recordStateHistory_false_leaves_history_empty() = testPresenter(
        recordStateHistory = false,
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        send(CounterEvent.Increment)
        awaitState { it == 1 }
        send(CounterEvent.Increment)
        awaitState { it == 2 }
        // `state` still reflects the latest value — only the accumulated
        // history list is suppressed.
        assertEquals(2, state)
        assertEquals(emptyList(), stateHistory)
    }

    // ---- Handler-error surfacing -------------------------------------

    @Test
    fun awaitHandlerError_returns_exception_thrown_inside_on() = testPresenter<CounterEvent, Int, CounterEffect>(
        presenter = { events ->
            buildPresenter(events) {
                on<CounterEvent.Increment> {
                    throw IllegalStateException("handler crashed")
                }
                0
            }
        },
    ) {
        send(CounterEvent.Increment)
        val err = awaitHandlerError<IllegalStateException>()
        assertEquals("handler crashed", err.message)
        // Presenter stays alive — state hasn't been corrupted, further
        // events can be sent without the scenario tearing down.
        assertState { it == 0 }
    }

    @Test
    fun expectNoHandlerErrors_passes_when_handlers_run_cleanly() = testPresenter(
        presenter = { events -> counterPresenter(events, initial = 0) },
    ) {
        send(CounterEvent.Increment)
        awaitState { it == 1 }
        expectNoHandlerErrors(within = 20.milliseconds)
    }

    // ---- Handler freshness (rememberUpdatedState regression guard) ----

    private sealed interface FreshnessEvent {
        data object Tick : FreshnessEvent     // bumps recomposition-varying local
        data object Echo : FreshnessEvent     // replays the currently-captured value
    }

    private sealed interface FreshnessEffect {
        data class Captured(val value: Int) : FreshnessEffect
    }

    /**
     * Regression guard: before `on<E>` wrapped its handler in
     * `rememberUpdatedState`, the first-composition's handler lambda (with
     * its first-composition capture of local values) ran forever. The
     * `Tick` event bumps a remembered state, so after `advance()` the
     * block re-runs and a new handler is created that captures the bumped
     * value. The `Echo` handler should see the LATEST captured value, not
     * the one from the initial composition.
     */
    @Test
    fun on_handler_captures_latest_recomposition_value() = testPresenter<FreshnessEvent, Int, FreshnessEffect>(
        presenter = { events ->
            buildPresenter(events) {
                var count by remember { mutableStateOf(0) }
                val capturedCount = count
                on<FreshnessEvent.Tick> { count += 1 }
                on<FreshnessEvent.Echo> { emitEffect(FreshnessEffect.Captured(capturedCount)) }
                count
            }
        },
    ) {
        awaitState { it == 0 }

        send(FreshnessEvent.Tick)
        awaitState { it == 1 }  // count bumped, block re-executed, capturedCount == 1

        send(FreshnessEvent.Echo)
        val first = awaitEffect<FreshnessEffect.Captured>()
        assertEquals(1, first.value, "Echo handler should read the latest capturedCount")

        send(FreshnessEvent.Tick)
        awaitState { it == 2 }

        send(FreshnessEvent.Echo)
        val second = awaitEffect<FreshnessEffect.Captured>()
        assertEquals(2, second.value)
    }
}
