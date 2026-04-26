package com.kitakkun.fusio.test

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Driver and assertion surface for the scenario block of [testPresenter]
 * and [testSubPresenter].
 *
 * Inside the scenario you push events with [send] (or just [advance] the
 * frame clock), inspect produced state via [state] / [stateHistory] /
 * [assertState] / [awaitState], pop side effects with
 * [awaitEffect] / [pendingEffects], assert silence with [expectNoEffects],
 * and handle event-processing errors with [awaitEventError] /
 * [expectNoEventErrors].
 *
 * ```kotlin
 * testPresenter(presenter = { todoPresenter() }) {
 *     send(TodoEvent.Add("milk"))
 *     awaitState { it.items.size == 1 }
 *
 *     val toast = awaitEffect<TodoEffect.Toast>()
 *     assertEquals("added", toast.message)
 *     expectNoEffects()
 * }
 * ```
 *
 * ## Reading state
 *
 * [state] is the latest snapshot — back-to-back reads return the same
 * value unless a frame advanced between them. [stateHistory] is every
 * distinct state value observed since the scenario started, useful for
 * asserting that a transient `loading = true` actually surfaced even if
 * the final state is `loading = false`.
 *
 * ## Effects and event errors
 *
 * Both are FIFO queues, not snapshots:
 * - [awaitEffect] pops the next effect (suspending up to `timeout`);
 *   [pendingEffects] is the unpopped remainder.
 * - [awaitEventError] does the same for `on<E>`-handler exceptions
 *   surfaced via `Presentation.eventErrorFlow`.
 *
 * Pair the await calls with [expectNoEffects] / [expectNoEventErrors]
 * when you want to lock down "exactly the things I awaited, nothing
 * extra".
 *
 * ## Timeouts
 *
 * All await-style methods accept a [Duration]. Under the virtual clock
 * `kotlinx-coroutines-test` installs, the 1-second defaults are virtual
 * milliseconds — generous for most tests, bump only when a presenter
 * genuinely schedules work beyond that horizon.
 */
public interface PresenterScenario<Event, State, Effect> {
    /** The most-recent state produced by the presenter. */
    public val state: State

    /** Every distinct state value produced since the scenario started, oldest first. */
    public val stateHistory: List<State>

    /** Effects that have been emitted but not yet consumed by [awaitEffect]. */
    public val pendingEffects: List<Effect>

    /** Event errors that have been recorded but not yet consumed by [awaitEventError]. */
    public val pendingEventErrors: List<Throwable>

    /** Push [event] into the presenter's event flow and advance one frame. */
    public suspend fun send(event: Event)

    /** Advance the frame clock by one tick without sending anything. */
    public suspend fun advance()

    /**
     * Suspend until [predicate] matches the current state, returning the
     * matched state. Fails the test if [timeout] elapses first.
     *
     * Pass [message] to annotate the timeout failure with the call site's
     * intent (e.g. `"after login completes"`) — predicate source isn't
     * otherwise recoverable in the failure text.
     */
    public suspend fun awaitState(
        message: String? = null,
        timeout: Duration = 1.seconds,
        predicate: (State) -> Boolean,
    ): State

    /**
     * Synchronous sibling of [awaitState] — checks [predicate] against the
     * current [state] once and fails the test if it doesn't match. Use
     * after a [send] + [awaitState] pair when you want to layer extra
     * assertions on the already-landed state without re-waiting.
     */
    public fun assertState(message: String? = null, predicate: (State) -> Boolean): State

    /**
     * Suspend until the next effect is available and return it, or fail
     * if [timeout] elapses with the queue still empty.
     */
    public suspend fun awaitEffect(message: String? = null, timeout: Duration = 1.seconds): Effect

    /**
     * Fail the test if any effect is already queued or arrives within
     * [within]. Pair with [awaitEffect] to lock down "exactly the
     * effects I awaited, nothing more".
     */
    public suspend fun expectNoEffects(message: String? = null, within: Duration = 50.milliseconds)

    /**
     * Suspend until the next `on<E>`-handler exception is available and
     * return it, or fail if [timeout] elapses with no error recorded.
     */
    public suspend fun awaitEventError(message: String? = null, timeout: Duration = 1.seconds): Throwable

    /**
     * Fail the test if any event-processing error is already recorded or
     * arrives within [within]. Symmetric with [expectNoEffects] for
     * pinning down "no `on<E>` handler threw".
     */
    public suspend fun expectNoEventErrors(message: String? = null, within: Duration = 50.milliseconds)
}

/**
 * Pop the next effect and assert it's a [T], returning it narrowed.
 *
 * Fails the test if the next effect is a different subtype — skipping
 * non-matching effects would let bugs that emit the wrong effect first
 * slip past. Use it like:
 *
 * ```kotlin
 * send(TodoEvent.Add("milk"))
 * val toast = awaitEffect<TodoEffect.Toast>()
 * assertEquals("added", toast.message)
 * ```
 *
 * The receiver uses star projections so call sites supply only [T] —
 * `awaitEffect<Toast>()` rather than spelling out every other type
 * parameter.
 */
public suspend inline fun <reified T : Any> PresenterScenario<*, *, *>.awaitEffect(
    message: String? = null,
    timeout: Duration = 1.seconds,
): T {
    val eff = awaitEffect(message, timeout)
    if (eff !is T) {
        throw AssertionError(
            buildString {
                append("awaitEffect<${T::class.simpleName ?: T::class}>")
                if (message != null) append(" ($message)")
                append(" got ${eff?.let { it::class.simpleName } ?: "null"}: $eff")
            },
        )
    }
    return eff
}

/**
 * Pop the next event-processing error and assert it's a [T], returning it
 * narrowed.
 *
 * Same shape as the effect-side [awaitEffect] reified overload: fails the
 * test if the next recorded error isn't a [T]. Use it to pin both that an
 * `on<E>` handler did throw on bad input and that it threw the *expected*
 * exception type:
 *
 * ```kotlin
 * send(TodoEvent.Add(""))
 * val err = awaitEventError<EmptyTitleException>()
 * assertEquals("title is empty", err.message)
 * ```
 */
public suspend inline fun <reified T : Throwable> PresenterScenario<*, *, *>.awaitEventError(
    message: String? = null,
    timeout: Duration = 1.seconds,
): T {
    val err = awaitEventError(message, timeout)
    if (err !is T) {
        throw AssertionError(
            buildString {
                append("awaitEventError<${T::class.simpleName ?: T::class}>")
                if (message != null) append(" ($message)")
                append(" got ${err::class.simpleName ?: err::class}: $err")
            },
        )
    }
    return err
}
