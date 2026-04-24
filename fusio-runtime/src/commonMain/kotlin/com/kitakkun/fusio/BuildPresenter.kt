package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

@Composable
public fun <Event, Effect, UiState> buildPresenter(
    eventFlow: Flow<Event>,
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Presentation<UiState, Effect> {
    val scope = remember { PresenterScope<Event, Effect>(eventFlow) }

    DisposableEffect(Unit) {
        onDispose { scope.close() }
    }

    val uiState = scope.block()

    return Presentation(
        state = uiState,
        effectFlow = scope.internalEffectFlow,
        handlerErrors = scope.handlerErrors,
    )
}
