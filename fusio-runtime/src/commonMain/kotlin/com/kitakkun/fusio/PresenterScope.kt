package com.kitakkun.fusio

import androidx.compose.runtime.Stable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Context a presenter body runs against. Reads come off [eventFlow]; writes
 * go through [emitEffect] (effects → parent) or through handler-exception
 * catches that the [on] helper funnels into [eventErrorFlow].
 *
 * Marked `@Stable`: the public surface (`eventFlow: val Flow`, the suspend
 * `emitEffect` function, the `eventErrorFlow: val Flow`) never changes after
 * construction. The internal channels mutate as sends/receives happen, but
 * those mutations aren't visible to the composition — Compose only cares
 * about the wrapper's identity and its public read-shape, both of which are
 * reference-stable.
 */
@Stable
public class PresenterScope<Event, Effect>(
    @PublishedApi internal val eventFlow: Flow<Event>,
) {
    private val _effectChannel = Channel<Effect>(Channel.UNLIMITED)
    internal val internalEffectFlow: Flow<Effect> = _effectChannel.receiveAsFlow()

    private val _errorChannel = Channel<Throwable>(Channel.UNLIMITED)

    /**
     * Errors raised while processing events — every `on<E>` handler is wrapped
     * in a try/catch that funnels its `Throwable` here. The presenter stays
     * alive (the offending event doesn't kill the whole composition); observe
     * this flow if you want to log, retry, or escalate.
     *
     * Child scopes' errors bubble up here via `fuse`'s generated
     * `forwardEventErrors` call — a single top-level observer at the root
     * presenter sees every event-processing crash in the tree.
     */
    public val eventErrorFlow: Flow<Throwable> = _errorChannel.receiveAsFlow()

    public suspend fun emitEffect(effect: Effect) {
        _effectChannel.send(effect)
    }

    /**
     * Funnels a handler exception into [eventErrorFlow]. Published via
     * `@PublishedApi` because the `on` helper is `inline` and synthesises
     * the catch block at the call site.
     */
    @PublishedApi
    internal suspend fun recordEventError(t: Throwable) {
        _errorChannel.send(t)
    }

    internal fun close() {
        _effectChannel.close()
        _errorChannel.close()
    }
}
