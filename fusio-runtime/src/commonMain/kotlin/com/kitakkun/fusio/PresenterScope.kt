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
 *
 * ## Why `Channel`, not `MutableSharedFlow`, backs effects and event errors
 *
 * Both streams represent **consume-once** values: an effect (toast,
 * navigation, snackbar) should fire once, and an event-processing error
 * should be observed once for logging / metrics. `Channel` enforces this
 * by giving each item to exactly one collector and dropping it from the
 * buffer afterwards. A `SharedFlow` would broadcast to every collector,
 * which silently turns a "log AND show toast" pair into a double-fire.
 *
 * `Channel.UNLIMITED` is the buffer policy here so [emitEffect] and the
 * internal error recorder never suspend the producer — the reasonable
 * presenter cadence is well below "millions of effects per frame", and a
 * runaway effect emitter signals a bug we want surfaced rather than
 * silently back-pressured.
 *
 * If a caller genuinely needs fan-out (e.g. forward the same effect both
 * to UI and to an analytics sink), wrap the exposed Flow with
 * `shareIn(scope, SharingStarted.Eagerly)` at the call site. Making fan-out
 * an explicit opt-in keeps the default safe.
 */
@Stable
public class PresenterScope<Event, Effect>(
    @PublishedApi internal val eventFlow: Flow<Event>,
) {
    // See class KDoc ("Why `Channel`, not `MutableSharedFlow`") for the
    // rationale behind both channels.
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
