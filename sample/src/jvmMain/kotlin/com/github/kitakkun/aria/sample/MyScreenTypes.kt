package com.github.kitakkun.aria.sample

import com.github.kitakkun.aria.MapFrom
import com.github.kitakkun.aria.MapTo

// Parent (screen-level) Event/Effect/State types
sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent

    data class Search(val query: String) : MyScreenEvent
}

sealed interface MyScreenEffect {
    @MapFrom(FavoriteEffect.ShowMessage::class)
    data class ShowSnackbar(val message: String) : MyScreenEffect
}

data class MyScreenUiState(
    val favoriteState: FavoriteState = FavoriteState(),
    val searchQuery: String = "",
)
