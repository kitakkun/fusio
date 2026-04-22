package com.github.kitakkun.aria.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.kitakkun.aria.Aria
import com.github.kitakkun.aria.PresenterScope
import com.github.kitakkun.aria.on
import kotlinx.coroutines.flow.emptyFlow

// Sub-presenter — completely independent, reusable
@Composable
fun PresenterScope<FavoriteEvent, FavoriteEffect>.favorite(): Aria<FavoriteState, FavoriteEffect> {
    var isFavorited by remember { mutableStateOf(false) }

    on<FavoriteEvent.Toggle> { event ->
        isFavorited = !isFavorited
        emitEffect(FavoriteEffect.ShowMessage("Favorite toggled for ${event.id}"))
    }

    return Aria(
        state = FavoriteState(isFavorited = isFavorited),
        effectFlow = emptyFlow(), // effects go through PresenterScope.internalEffectFlow
    )
}
