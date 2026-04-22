package com.kitakkun.aria.sample

// Child (sub-presenter) Event/Effect/State types
sealed interface FavoriteEvent {
    data class Toggle(val id: String) : FavoriteEvent
}

sealed interface FavoriteEffect {
    data class ShowMessage(val message: String) : FavoriteEffect
}

data class FavoriteState(
    val isFavorited: Boolean = false,
)
