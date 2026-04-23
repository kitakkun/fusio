package com.kitakkun.fusio.demo

import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo

// ---------- Counter (leaf) ----------

sealed interface CounterEvent {
    data object Increment : CounterEvent
}

sealed interface CounterEffect {
    data class Value(val newValue: Int) : CounterEffect
}

data class CounterState(val value: Int)

// ---------- Favorite (mid — hosts Counter) ----------

sealed interface FavoriteEvent {
    data class Toggle(val id: String) : FavoriteEvent

    @MapTo(CounterEvent.Increment::class)
    data object IncrementCounter : FavoriteEvent
}

sealed interface FavoriteEffect {
    data class ShowMessage(val message: String) : FavoriteEffect

    @MapFrom(CounterEffect.Value::class)
    data class CounterValue(val newValue: Int) : FavoriteEffect
}

data class FavoriteState(
    val isFavorited: Boolean,
    val counter: CounterState,
)

// ---------- MyScreen (root) ----------

sealed interface MyScreenEvent {
    data class Search(val query: String) : MyScreenEvent

    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent

    @MapTo(FavoriteEvent.IncrementCounter::class)
    data object IncrementCounter : MyScreenEvent
}

sealed interface MyScreenEffect {
    @MapFrom(FavoriteEffect.ShowMessage::class)
    data class ShowSnackbar(val message: String) : MyScreenEffect

    @MapFrom(FavoriteEffect.CounterValue::class)
    data class CounterValue(val newValue: Int) : MyScreenEffect
}

data class MyScreenUiState(
    val favoriteState: FavoriteState,
    val searchQuery: String,
)
