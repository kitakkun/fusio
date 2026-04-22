package com.github.kitakkun.aria.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.kitakkun.aria.Aria
import com.github.kitakkun.aria.buildPresenter
import com.github.kitakkun.aria.mappedScope
import com.github.kitakkun.aria.on
import kotlinx.coroutines.flow.Flow

// Screen-level Presenter using mappedScope
@Composable
fun myScreenPresenter(
    eventFlow: Flow<MyScreenEvent>,
): Aria<MyScreenUiState, MyScreenEffect> = buildPresenter(eventFlow) {
    var searchQuery by remember { mutableStateOf("") }

    // Local event handling
    on<MyScreenEvent.Search> { event ->
        searchQuery = event.query
    }

    // Delegate to sub-presenter via mappedScope — compiler plugin will
    // generate the PresenterScope creation and invocation
    val favoriteState = mappedScope { favorite() }

    MyScreenUiState(
        favoriteState = favoriteState,
        searchQuery = searchQuery,
    )
}
