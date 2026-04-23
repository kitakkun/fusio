package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

@Composable
fun <Event, Effect, UiState> buildPresenter(
    eventFlow: Flow<Event>,
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Fusio<UiState, Effect> {
    val scope = remember { PresenterScope<Event, Effect>(eventFlow) }

    DisposableEffect(Unit) {
        onDispose { scope.close() }
    }

    val uiState = scope.block()

    return Fusio(uiState, scope.internalEffectFlow)
}
