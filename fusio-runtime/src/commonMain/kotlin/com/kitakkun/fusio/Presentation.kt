package com.kitakkun.fusio

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow

/**
 * The result a Fusio presenter exposes to its UI: the latest [state] to
 * render, the [effectFlow] of one-shot side effects, the [eventErrorFlow]
 * of unexpected exceptions raised while processing events, and a [send]
 * entry point for pushing events back into the presenter.
 *
 * ## Typical use
 *
 * Returned by [buildPresenter] inside a `@Composable` and consumed at the
 * call site:
 *
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val presentation = myScreenPresenter()
 *
 *     // Render
 *     MyScreenContent(state = presentation.state)
 *
 *     // Drive input
 *     Button(onClick = { presentation.send(MyEvent.Click) }) { Text("…") }
 *
 *     // Collect side effects
 *     presentation.OnEffect { effect ->
 *         when (effect) { … }
 *     }
 * }
 * ```
 *
 * `OnEffect` and the parallel `OnEventError` are thin Composable helpers
 * over [LaunchedEffect] + [effectFlow] / [eventErrorFlow] — see [OnEffect]
 * for details.
 *
 * To bridge an external `Flow<Event>` (URL deeplink, navigation, etc.) into
 * the presenter, forward it to [send]:
 *
 * ```kotlin
 * LaunchedEffect(externalFlow) {
 *     externalFlow.collect { presentation.send(it) }
 * }
 * ```
 *
 * ## Type-parameter order
 *
 * `<State, Event, Effect>` reflects the consumer's reading order: [state]
 * is read every recomposition, [send]/[Event] is invoked on every UI
 * interaction, [effectFlow]/[Effect] is collected once per
 * `LaunchedEffect`. (The producer-side types — [buildPresenter] and
 * [PresenterScope] — order their parameters event-first; from the
 * producer's perspective `Event` is the input axis.)
 *
 * ## Errors vs effects
 *
 * Use [Effect] for *expected* outcomes the UI should react to (toasts,
 * navigation, snackbars, validation feedback) — emit them explicitly from
 * `on<E>` handlers via [PresenterScope.emitEffect]. [eventErrorFlow] is
 * for *unexpected* exceptions: it carries whatever an `on<E>` handler
 * threw without unwinding the composition, so observers can log, report,
 * or telemetry-track them. Treat it as a bug-detection channel, not a
 * recovery one.
 *
 * ## Compose stability
 *
 * `Presentation` is `@Stable`: holding the same instance across
 * recompositions never observably mutates. A `@Composable` that takes a
 * `Presentation` parameter therefore skips recomposition when the
 * presenter returns the same instance.
 */
@Stable
public class Presentation<State, Event, Effect>(
    public val state: State,
    public val effectFlow: Flow<Effect>,
    public val eventErrorFlow: Flow<Throwable>,
    public val send: (Event) -> Unit,
) {
    // Hand-written equals / hashCode / toString instead of `data class`;
    // see docs/runtime-implementation-notes.md ("Why Presentation is hand-written") for the ABI
    // reasoning.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Presentation<*, *, *>) return false
        return state == other.state &&
            effectFlow == other.effectFlow &&
            eventErrorFlow == other.eventErrorFlow &&
            send == other.send
    }

    override fun hashCode(): Int {
        var result = state?.hashCode() ?: 0
        result = 31 * result + effectFlow.hashCode()
        result = 31 * result + eventErrorFlow.hashCode()
        result = 31 * result + send.hashCode()
        return result
    }

    override fun toString(): String = "Presentation(state=$state, effectFlow=$effectFlow, eventErrorFlow=$eventErrorFlow, send=$send)"
}
