package com.kitakkun.fusio

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The value a Fusio presenter produces: a snapshot of screen [state] paired
 * with the [effectFlow] of side effects and the [handlerErrors] stream of
 * any crashes swallowed by `on<E>` handlers.
 *
 * The name reflects what Fusio is doing at the call site — fusing the
 * sub-presenters' private state trees into one screen-level state and their
 * effect flows into one outbound channel, then returning both as a pair.
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
 * ## `handlerErrors` default
 *
 * Defaults to an empty flow so code that built `Presentation` manually
 * before the field existed (the common case: `Presentation(state,
 * effectFlow)`) stays source-compatible. `buildPresenter` wires the real
 * stream from its `PresenterScope`.
 */
@Stable
public class Presentation<State, Effect>(
    public val state: State,
    public val effectFlow: Flow<Effect>,
    public val handlerErrors: Flow<Throwable> = emptyFlow(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Presentation<*, *>) return false
        return state == other.state &&
            effectFlow == other.effectFlow &&
            handlerErrors == other.handlerErrors
    }

    override fun hashCode(): Int {
        var result = state?.hashCode() ?: 0
        result = 31 * result + effectFlow.hashCode()
        result = 31 * result + handlerErrors.hashCode()
        return result
    }

    override fun toString(): String =
        "Presentation(state=$state, effectFlow=$effectFlow, handlerErrors=$handlerErrors)"
}
