package com.kitakkun.fusio

import androidx.compose.runtime.Stable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Context a presenter body runs against. Reads come off [eventFlow]; writes
 * go through [emitEffect].
 *
 * Marked `@Stable`: the public surface (`eventFlow: val Flow`, the suspend
 * `emitEffect` function) never changes after construction. The internal
 * effect channel mutates as sends/receives happen, but those mutations
 * aren't visible to the composition — Compose only cares about the
 * wrapper's identity and its public read-shape, both of which are
 * reference-stable.
 */
@Stable
public class PresenterScope<Event, Effect>(
    @PublishedApi internal val eventFlow: Flow<Event>,
) {
    private val _effectChannel = Channel<Effect>(Channel.UNLIMITED)
    internal val internalEffectFlow: Flow<Effect> = _effectChannel.receiveAsFlow()

    public suspend fun emitEffect(effect: Effect) {
        _effectChannel.send(effect)
    }

    internal fun close() {
        _effectChannel.close()
    }
}
