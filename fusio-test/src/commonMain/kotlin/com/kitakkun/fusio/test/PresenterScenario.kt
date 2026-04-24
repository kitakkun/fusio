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
 * ## Handler-error semantics
 *
 * Exceptions thrown inside `on<E>` handlers are caught by the runtime and
 * routed into the root `PresenterScope.handlerErrors` stream (including
 * errors bubbled up from `fuse`d children). Same queue shape as effects:
 * [awaitHandlerError] pops, [pendingHandlerErrors] peeks. Useful for
 * asserting that a handler *does* throw on bad input without letting the
 * exception derail the rest of the scenario.
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

    /** Handler errors that have been recorded but not yet consumed by [awaitHandlerError]. */
    public val pendingHandlerErrors: List<Throwable>

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
     * Fail-fast sibling of [awaitState]: checks [predicate] against the
     * current [state] once and throws [AssertionError] if it doesn't match.
     * Use this after a [send] + [awaitState] pair when you want to layer
     * additional assertions on the landed state without re-waiting.
     *
     * [message] gets prepended to the failure description to preserve the
     * call-site intent — predicate source text isn't otherwise recoverable.
     */
    public fun assertState(message: String? = null, predicate: (State) -> Boolean): State

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

    /**
     * Suspends until a handler error is available and returns it. Fails if
     * none arrives within [timeout].
     */
    public suspend fun awaitHandlerError(timeout: Duration = 1.seconds): Throwable

    /**
     * Fails if any handler error is already queued or arrives within
     * [within]. Symmetric with [expectNoEffects] for asserting that the
     * presenter's `on<>` handlers ran without swallowing any exception.
     */
    public suspend fun expectNoHandlerErrors(within: Duration = 50.milliseconds)
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

/**
 * Reified sugar over [PresenterScenario.awaitHandlerError] that narrows
 * the result to a specific [Throwable] subtype [T]. Fails fast if the
 * next recorded error isn't a [T], matching the strictness of the
 * effect-side [awaitEffect].
 */
public suspend inline fun <reified T : Throwable> PresenterScenario<*, *, *>.awaitHandlerError(
    timeout: Duration = 1.seconds,
): T {
    val err = awaitHandlerError(timeout)
    if (err !is T) {
        throw AssertionError(
            "awaitHandlerError<${T::class.simpleName ?: T::class}> got " +
                "${err::class.simpleName ?: err::class}: $err",
        )
    }
    return err
}
