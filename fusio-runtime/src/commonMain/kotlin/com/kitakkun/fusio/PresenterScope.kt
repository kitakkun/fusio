package com.kitakkun.fusio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

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
