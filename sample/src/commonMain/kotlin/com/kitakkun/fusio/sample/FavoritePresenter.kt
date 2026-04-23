package com.kitakkun.fusio.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.mappedScope
import com.kitakkun.fusio.on

// Sub-presenter — completely independent, reusable.
// Demonstrates nested mappedScope: favorite() itself delegates to counter().
@Composable
fun PresenterScope<FavoriteEvent, FavoriteEffect>.favorite(): FavoriteState {
    var isFavorited by remember { mutableStateOf(false) }

    on<FavoriteEvent.Toggle> { event ->
        isFavorited = !isFavorited
        emitEffect(FavoriteEffect.ShowMessage("Favorite toggled for ${event.id}"))
    }

    // Nested mappedScope — Fusio maps FavoriteEvent.IncrementCounter -> CounterEvent.Increment
    // and forwards CounterEffect.CounterChanged -> FavoriteEffect.CounterUpdated.
    val counterState = mappedScope { counter() }

    return FavoriteState(isFavorited = isFavorited, counter = counterState)
}
