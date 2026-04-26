package com.kitakkun.fusio

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow

/**
 * The value a Fusio presenter produces: a snapshot of screen [state], the
 * [effectFlow] of side effects, the [handlerErrors] stream of any crashes
 * swallowed by `on<E>` handlers, and a [send] entry point the UI uses to
 * push events into the presenter.
 *
 * The name reflects what Fusio is doing at the call site — fusing the
 * sub-presenters' private state trees into one screen-level state and their
 * effect flows into one outbound channel, then returning both alongside the
 * input handle.
 *
 * ## Why not a `data class`
 *
 * `data class`es auto-generate `copy` / `componentN`, which pins the public
 * ABI to the exact field list — adding, removing, or reordering a field
 * becomes a binary-incompatible change for downstream consumers. A
 * Maven-Central-grade library shouldn't leak that surface; callers of
 * `Presentation` read `.state` / `.effectFlow` / `.handlerErrors` directly
 * and don't need destructuring or `copy()`. The explicit [equals] /
 * [hashCode] / [toString] below retain the ergonomics that actually matter
 * (structural equality in tests and readable log output) without the ABI tax.
 *
 * ## Why `@Stable`
 *
 * Compose would otherwise treat `Presentation` as unstable because one of
 * its generic parameters appears as a `Flow<Effect>` — and Flows carry no
 * structural-equality guarantee. At the wrapper level this class IS stable:
 * the properties are `val`s set once at construction, the instance never
 * mutates observably, and `equals`/`hashCode` are consistent. Marking it
 * `@Stable` lets composables that take a `Presentation` parameter skip
 * recomposition when the same instance is passed twice.
 *
 * ## All four properties are required
 *
 * No defaults. A presenter author deciding not to propagate handler errors
 * should pass `emptyFlow()` explicitly, so the decision is visible at the
 * call site rather than being silently inherited from a default.
 * `buildPresenter` wires the real channels from its `PresenterScope`;
 * hand-rolled `Presentation(state, effectFlow, emptyFlow(), {})` is the
 * correct form when composing a presenter without `buildPresenter`.
 */
@Stable
public class Presentation<State, Effect, Event>(
    public val state: State,
    public val effectFlow: Flow<Effect>,
    public val handlerErrors: Flow<Throwable>,
    public val send: (Event) -> Unit,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Presentation<*, *, *>) return false
        return state == other.state &&
            effectFlow == other.effectFlow &&
            handlerErrors == other.handlerErrors &&
            send == other.send
    }

    override fun hashCode(): Int {
        var result = state?.hashCode() ?: 0
        result = 31 * result + effectFlow.hashCode()
        result = 31 * result + handlerErrors.hashCode()
        result = 31 * result + send.hashCode()
        return result
    }

    override fun toString(): String =
        "Presentation(state=$state, effectFlow=$effectFlow, handlerErrors=$handlerErrors, send=$send)"
}
