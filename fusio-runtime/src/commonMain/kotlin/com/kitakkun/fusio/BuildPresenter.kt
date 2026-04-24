package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

/**
 * ## Lifecycle semantics
 *
 * The [PresenterScope] is memoized with [eventFlow] as the key — if the
 * caller passes a different Flow identity on a subsequent recomposition,
 * the old scope is disposed and a fresh one starts. This surfaces
 * "I accidentally passed an unstable event flow" as a visible state
 * reset rather than silently keeping stale collection running against
 * the first-ever flow. Keep your `eventFlow` reference stable across
 * recompositions (e.g., hoist it into a `remember { MutableSharedFlow() }`
 * in the caller) unless you *want* reset-on-swap semantics.
 *
 * The [DisposableEffect] keyed on the scope calls [PresenterScope.close]
 * when the scope goes away — either on composition exit or when a new
 * scope replaces the old one — so effect / handler-error channels are
 * closed deterministically and no background collector leaks.
 */
@Composable
public fun <Event, Effect, UiState> buildPresenter(
    eventFlow: Flow<Event>,
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Presentation<UiState, Effect> {
    val scope = remember(eventFlow) { PresenterScope<Event, Effect>(eventFlow) }

    DisposableEffect(scope) {
        onDispose { scope.close() }
    }

    val uiState = scope.block()

    return Presentation(
        state = uiState,
        effectFlow = scope.internalEffectFlow,
        handlerErrors = scope.handlerErrors,
    )
}
