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
                    "awaitState timed out after $timeout. Latest state: ${stateHolder.current}",
                )
            }
            advance()
        }
    }

    override suspend fun awaitEffect(timeout: Duration): Effect {
        drainEffectChannel()
        if (_pendingEffects.isNotEmpty()) return _pendingEffects.removeAt(0)

        checkError()
        return withTimeoutOrNull(timeout) {
            effectChannel.receive()
        } ?: throw AssertionError("awaitEffect timed out after $timeout.")
    }

    override suspend fun expectNoEffects(within: Duration) {
        drainEffectChannel()
        if (_pendingEffects.isNotEmpty()) {
            throw AssertionError(
                "expectNoEffects: ${_pendingEffects.size} effect(s) already queued: $_pendingEffects",
            )
        }
        val received = withTimeoutOrNull(within) { effectChannel.receive() }
        if (received != null) {
            throw AssertionError("expectNoEffects: received $received within $within.")
        }
    }

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
