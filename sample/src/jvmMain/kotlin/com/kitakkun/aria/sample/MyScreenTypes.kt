package com.kitakkun.aria.sample

import com.kitakkun.aria.MapFrom
import com.kitakkun.aria.MapTo

// Parent (screen-level) Event/Effect/State types
sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent

    @MapTo(FavoriteEvent.IncrementCounter::class)
    data object IncrementCounter : MyScreenEvent

    data class Search(val query: String) : MyScreenEvent
}

sealed interface MyScreenEffect {
    @MapFrom(FavoriteEffect.ShowMessage::class)
    data class ShowSnackbar(val message: String) : MyScreenEffect

    @MapFrom(FavoriteEffect.CounterUpdated::class)
    data class CounterValue(val newValue: Int) : MyScreenEffect
}

data class MyScreenUiState(
    val favoriteState: FavoriteState = FavoriteState(),
    val searchQuery: String = "",
)
