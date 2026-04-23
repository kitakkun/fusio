package com.kitakkun.fusio.test

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Driver + assertion surface a [testPresenter] / [testSubPresenter] scenario
 * block runs against.
 *
 * The scenario owns the headless Compose runtime behind it. Calls here push
 * events into the presenter, tick the frame clock, and inspect what the
 * presenter produced in return.
 *
 * ## Read semantics
 *
 * [state] is a snapshot — calling it twice in a row returns the same value
 * unless a frame advanced between the calls. [stateHistory] records every
 * distinct state value observed since the scenario started, which lets tests
 * assert that a transient "loading" state was actually visible even if the
 * final state is "loaded".
 *
 * ## Effect semantics
 *
 * Effects are a queue, not a stream snapshot — [awaitEffect] pops the next
 * one, and [pendingEffects] reads the unpopped remainder. [expectNoEffects]
 * fails if anything is queued or arrives within its window, so pair it with
 * [awaitEffect] calls when you want to assert "exactly this one effect, no
 * more".
 *
 * ## Timeouts
 *
 * All await-style methods accept a [Duration]. Under the virtual clock the
 * framework installs, 1-second defaults are generous — bump them only if a
 * presenter genuinely schedules work beyond that horizon.
 */
public interface PresenterScenario<Event, State, Effect> {
    /** The most-recent state produced by the presenter. */
    public val state: State

    /** Every distinct state value produced since the scenario started, oldest first. */
    public val stateHistory: List<State>

    /** Effects that have been emitted but not yet consumed by [awaitEffect]. */
    public val pendingEffects: List<Effect>

    /** Push [event] into the presenter's event flow and advance one frame. */
    public suspend fun send(event: Event)

    /** Advance the frame clock by one tick without sending anything. */
    public suspend fun advance()

    /**
     * Suspends until [predicate] matches on the current state, or fails if
     * [timeout] elapses first. Returns the state that matched.
     */
    public suspend fun awaitState(
        timeout: Duration = 1.seconds,
        predicate: (State) -> Boolean,
    ): State

    /**
     * Suspends until an effect is available and returns it. Fails if none
     * arrives within [timeout].
     */
    public suspend fun awaitEffect(timeout: Duration = 1.seconds): Effect

    /**
     * Fails if any effect is already queued or arrives within [within]. Use
     * this to lock down "no extra effects beyond the ones I explicitly
     * awaited".
     */
    public suspend fun expectNoEffects(within: Duration = 50.milliseconds)
}

/**
 * Reified sugar over [PresenterScenario.awaitEffect] that narrows the result
 * to a specific subtype [T]. Fails fast (Turbine-style) if the next effect
 * is a different subtype — skipping non-matching effects would mask bugs
 * where the wrong effect was emitted first.
 *
 * Uses star projections on the receiver so call sites only need to supply
 * [T] (`awaitEffect<Toast>()`); Kotlin would otherwise demand all four
 * type arguments because the interface's bare `awaitEffect(timeout)`
 * occupies the same name. The `T : Any` bound matches `reified`'s real
 * constraint — effects are the concrete payload of a sealed hierarchy and
 * are never themselves nullable at the call site.
 */
public suspend inline fun <reified T : Any> PresenterScenario<*, *, *>.awaitEffect(
    timeout: Duration = 1.seconds,
): T {
    val eff = awaitEffect(timeout)
    if (eff !is T) {
        throw AssertionError(
            "awaitEffect<${T::class.simpleName ?: T::class}> got " +
                "${eff?.let { it::class.simpleName } ?: "null"}: $eff",
        )
    }
    return eff
}
