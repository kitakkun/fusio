package com.kitakkun.fusio.test.internal

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import com.kitakkun.fusio.test.PresenterScenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Single-threaded scenario impl. Read the [PresenterScenario] KDoc first
 * for the contract; this file is the mechanical wiring.
 *
 * ## Frame pacing
 *
 * Each [advance] bumps a synthetic frame time by 16 ms and signals the
 * [BroadcastFrameClock]. `Snapshot.sendApplyNotifications()` then promotes
 * any state writes the presenter did into readable snapshots, and `yield()`
 * lets the recomposer coroutine actually run. Under the
 * `UnconfinedTestDispatcher` [testPresenter] installs, the recomposer runs
 * greedily after each yield — no `runCurrent` / `advanceUntilIdle` dance
 * needed.
 *
 * ## Error handling
 *
 * [recompositionError] is written by the coroutine that drives the
 * recomposer; [checkError] throws at the next suspension point so the
 * scenario fails with the presenter's actual exception instead of hanging
 * on a subsequent `awaitState`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PresenterScenarioImpl<Event, State, Effect>(
    private val events: MutableSharedFlow<Event>,
    private val stateHolder: StateHolder<State>,
    override val stateHistory: List<State>,
    private val effectChannel: Channel<Effect>,
    private val clock: BroadcastFrameClock,
    private val scheduler: TestCoroutineScheduler,
    private val recompositionError: ErrorRef,
) : PresenterScenario<Event, State, Effect> {

    private var frameTimeNanos = 0L
    private val _pendingEffects: MutableList<Effect> = mutableListOf()

    override val state: State
        get() {
            checkError()
            return stateHolder.current
                ?: error(
                    "No state has been observed yet. The initial frame hasn't run — " +
                        "this usually means testPresenter's init advance was skipped.",
                )
        }

    override val pendingEffects: List<Effect>
        get() {
            drainEffectChannel()
            return _pendingEffects.toList()
        }

    override suspend fun send(event: Event) {
        checkError()
        events.emit(event)
        advance()
    }

    override suspend fun advance() {
        checkError()
        frameTimeNanos += FRAME_INTERVAL_NANOS
        clock.sendFrame(frameTimeNanos)
        Snapshot.sendApplyNotifications()
        // `delay` on the test scheduler advances virtual time AND yields,
        // letting (a) timeout-based `awaitState` / `awaitEffect` deadlines
        // actually elapse and (b) the recomposer coroutine pick up the
        // frame tick. Plain `yield()` wouldn't advance time, so deadline
        // loops would spin forever in virtual time.
        delay(FRAME_INTERVAL_MILLIS)
        checkError()
    }

    override suspend fun awaitState(
        timeout: Duration,
        predicate: (State) -> Boolean,
    ): State {
        val deadline = scheduler.currentTime + timeout.inWholeMilliseconds
        while (true) {
            checkError()
            stateHolder.current?.let { current ->
                if (predicate(current)) return current
            }
            if (scheduler.currentTime >= deadline) {
                throw AssertionError(
                    buildFailureMessage(
                        header = "awaitState timed out after $timeout.",
                        extras = mapOf("Latest state" to "${stateHolder.current}"),
                    ),
                )
            }
            advance()
        }
    }

    override fun assertState(message: String?, predicate: (State) -> Boolean): State {
        checkError()
        val current = stateHolder.current
            ?: throw AssertionError(
                "assertState: no state observed yet. Call `advance()` or await first.",
            )
        if (!predicate(current)) {
            throw AssertionError(
                buildFailureMessage(
                    header = "assertState failed${message?.let { " ($it)" } ?: ""}.",
                    extras = mapOf("Current state" to "$current"),
                ),
            )
        }
        return current
    }

    override suspend fun awaitEffect(timeout: Duration): Effect {
        drainEffectChannel()
        if (_pendingEffects.isNotEmpty()) return _pendingEffects.removeAt(0)

        checkError()
        return withTimeoutOrNull(timeout) {
            effectChannel.receive()
        } ?: throw AssertionError(
            buildFailureMessage(
                header = "awaitEffect timed out after $timeout.",
                extras = mapOf(
                    "No effect emitted during the window" to "",
                    "Current state" to "${stateHolder.current}",
                ),
            ),
        )
    }

    override suspend fun expectNoEffects(within: Duration) {
        drainEffectChannel()
        if (_pendingEffects.isNotEmpty()) {
            throw AssertionError(
                buildFailureMessage(
                    header = "expectNoEffects: ${_pendingEffects.size} effect(s) already queued.",
                    extras = mapOf("Queued" to "$_pendingEffects"),
                ),
            )
        }
        val received = withTimeoutOrNull(within) { effectChannel.receive() }
        if (received != null) {
            throw AssertionError(
                buildFailureMessage(
                    header = "expectNoEffects: received $received within $within.",
                    extras = emptyMap(),
                ),
            )
        }
    }

    /**
     * Builds a multi-line failure message that includes scenario context
     * (state history, pending effects) under the given [header]. Every
     * `await*` / `assertState` / `expectNoEffects` failure funnels through
     * here so the error text is uniform and actually informative — bare
     * "awaitState timed out" was hiding what the presenter was doing.
     */
    private fun buildFailureMessage(
        header: String,
        extras: Map<String, String>,
    ): String = buildString {
        appendLine(header)
        extras.forEach { (label, value) ->
            if (value.isEmpty()) appendLine("  $label")
            else appendLine("  $label: $value")
        }
        // Snapshot these before rendering so a concurrent mutation (in
        // principle impossible under our single-threaded dispatcher, but
        // cheap insurance) can't tear the output.
        val history = stateHistory.toList()
        if (history.isNotEmpty()) {
            appendLine("  Observed states (oldest first):")
            history.forEachIndexed { i, s ->
                appendLine("    [$i] $s")
            }
        }
        drainEffectChannel()
        if (_pendingEffects.isNotEmpty()) {
            appendLine("  Pending effects: $_pendingEffects")
        }
    }.trimEnd()

    private fun drainEffectChannel() {
        while (true) {
            val result = effectChannel.tryReceive()
            if (result.isSuccess) {
                _pendingEffects += result.getOrThrow()
            } else {
                break
            }
        }
    }

    private fun checkError() {
        recompositionError.value?.let { throw it }
    }

    private companion object {
        // 60 fps synthetic frame cadence. The exact value doesn't matter — what
        // matters is that `sendFrame(t)` receives a monotonically increasing `t`
        // for each advance so Compose's change-detection treats each tick as a
        // new frame.
        const val FRAME_INTERVAL_NANOS: Long = 16_000_000L

        // Matching ms delay handed to the test scheduler in [advance] so
        // virtual-time deadlines make progress.
        const val FRAME_INTERVAL_MILLIS: Long = 16L
    }
}

/**
 * Mutable holder for the most-recent observed state. Separated from the
 * `MutableState<State?>` that the composition writes so the scenario-level
 * code doesn't need to reach into Compose just to read the value.
 */
internal class StateHolder<State>(
    // Not @Volatile: the scenario body and the recomposer coroutine are
    // interleaved on the same thread under `UnconfinedTestDispatcher`, so
    // JVM-style volatile semantics aren't needed — and `@Volatile` isn't
    // available on JS / Wasm targets at all.
    var current: State? = null,
)

/**
 * Simple mutable error ref the recomposer-driver coroutine writes into on
 * failure. Single-threaded as above; a plain `var` is safe — no atomicfu
 * needed.
 */
internal class ErrorRef(
    var value: Throwable? = null,
)
