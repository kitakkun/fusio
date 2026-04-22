package com.kitakkun.aria.sample

import com.kitakkun.aria.MapFrom
import com.kitakkun.aria.MapTo

// Sub-presenter Event/Effect/State types.
// FavoriteEvent maps inward from MyScreenEvent (via @MapTo on parent subtypes)
// and outward to CounterEvent (via @MapTo on FavoriteEvent subtypes).
sealed interface FavoriteEvent {
    data class Toggle(val id: String) : FavoriteEvent

    @MapTo(CounterEvent.Increment::class)
    data object IncrementCounter : FavoriteEvent
}

sealed interface FavoriteEffect {
    data class ShowMessage(val message: String) : FavoriteEffect

    @MapFrom(CounterEffect.CounterChanged::class)
    data class CounterUpdated(val newValue: Int) : FavoriteEffect
}

data class FavoriteState(
    val isFavorited: Boolean = false,
    val counter: CounterState = CounterState(),
)
