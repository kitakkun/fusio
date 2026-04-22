package com.github.kitakkun.aria

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

@Composable
fun <Event, Effect, UiState> buildPresenter(
    eventFlow: Flow<Event>,
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Aria<UiState, Effect> {
    val scope = remember { PresenterScope<Event, Effect>(eventFlow) }

    DisposableEffect(Unit) {
        onDispose { scope.close() }
    }

    val uiState = scope.block()

    return Aria(uiState, scope.internalEffectFlow)
}
