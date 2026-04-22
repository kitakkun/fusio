package com.kitakkun.aria.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.aria.PresenterScope
import com.kitakkun.aria.mappedScope
import com.kitakkun.aria.on

// Sub-presenter — completely independent, reusable.
// Demonstrates nested mappedScope: favorite() itself delegates to counter().
@Composable
fun PresenterScope<FavoriteEvent, FavoriteEffect>.favorite(): FavoriteState {
    var isFavorited by remember { mutableStateOf(false) }

    on<FavoriteEvent.Toggle> { event ->
        isFavorited = !isFavorited
        emitEffect(FavoriteEffect.ShowMessage("Favorite toggled for ${event.id}"))
    }

    // Nested mappedScope — Aria maps FavoriteEvent.IncrementCounter -> CounterEvent.Increment
    // and forwards CounterEffect.CounterChanged -> FavoriteEffect.CounterUpdated.
    val counterState = mappedScope { counter() }

    return FavoriteState(isFavorited = isFavorited, counter = counterState)
}
