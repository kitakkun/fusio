package com.github.kitakkun.aria.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.kitakkun.aria.Aria
import com.github.kitakkun.aria.buildPresenter
import com.github.kitakkun.aria.on
import kotlinx.coroutines.flow.Flow

// Screen-level Presenter
@Composable
fun myScreenPresenter(
    eventFlow: Flow<MyScreenEvent>,
): Aria<MyScreenUiState, MyScreenEffect> = buildPresenter(eventFlow) {
    var searchQuery by remember { mutableStateOf("") }

    // Local event handling
    on<MyScreenEvent.Search> { event ->
        searchQuery = event.query
    }

    // NOTE: mappedScope would be used here once IR transformer is implemented:
    // val favoriteState = mappedScope { favorite() }
    // For now, use the sub-presenter directly (without event/effect bridging):
    // val favoriteResult = favorite()  // requires matching PresenterScope type

    MyScreenUiState(
        favoriteState = FavoriteState(),
        searchQuery = searchQuery,
    )
}
