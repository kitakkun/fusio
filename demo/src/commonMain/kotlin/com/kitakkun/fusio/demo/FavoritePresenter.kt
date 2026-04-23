package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.mappedScope
import com.kitakkun.fusio.on

@Composable
fun PresenterScope<FavoriteEvent, FavoriteEffect>.favorite(): FavoriteState {
    var isFavorited by remember { mutableStateOf(false) }

    on<FavoriteEvent.Toggle> { event ->
        isFavorited = !isFavorited
        emitEffect(FavoriteEffect.ShowMessage("Favorite toggled for ${event.id}"))
    }

    // Nested sub-presenter — CounterEvent/Effect are remapped to/from
    // FavoriteEvent.IncrementCounter / FavoriteEffect.CounterValue via the
    // @MapTo / @MapFrom annotations on those subtypes. The Fusio compiler
    // plugin rewrites this call site at IR time.
    val counterState = mappedScope { counter() }

    return FavoriteState(
        isFavorited = isFavorited,
        counter = counterState,
    )
}
