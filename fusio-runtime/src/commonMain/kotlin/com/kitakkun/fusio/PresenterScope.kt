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
 * Both streams need two simultaneous properties that no `SharedFlow`
 * configuration delivers cleanly:
 *
 * 1. **Buffer until a collector arrives** — Compose's lifecycle has small
 *    windows where an `emitEffect` can fire before the consuming
 *    `LaunchedEffect` (in `forwardEffects` or the user's UI) is active.
 *    `Channel` queues those values; they're delivered as soon as the
 *    collector starts. `MutableSharedFlow(replay = 0)` would *drop* values
 *    emitted with no current subscriber.
 * 2. **No replay on re-subscribe** — recomposition can cancel and restart
 *    a `LaunchedEffect` collector. We never want a past navigation /
 *    toast effect to re-fire when a new collector subscribes.
 *    `MutableSharedFlow(replay = N)` would re-emit the last `N` items to
 *    each new subscriber.
 *
 * `Channel` satisfies both: items live in a FIFO buffer until consumed,
 * and once consumed they're gone — a fresh collector starts from "now".
 * (Single-consumer-only is a side effect of this choice, not the goal.
 * Callers who genuinely need fan-out should wrap the exposed `Flow` with
 * `shareIn(scope, SharingStarted.Eagerly)`.)
 *
 * Capacity is bounded (file-private constant) so an absent or slow collector
 * can't grow memory unboundedly. With the default `BufferOverflow.SUSPEND`,
 * a runaway emitter or stalled consumer suspends the producer — the
 * resulting test timeout / log signal is exactly the failure mode we want
 * to surface, vs. a silent leak. Realistic UI cadence (single-digit
 * effects per second) sits well within this headroom.
 */
@Stable
public class PresenterScope<Event, Effect>(
    @PublishedApi internal val eventFlow: Flow<Event>,
) {
    // See class KDoc ("Why `Channel`, not `MutableSharedFlow`") for the
    // rationale behind both channels and the bounded-capacity choice.
    private val _effectChannel = Channel<Effect>(capacity = BUFFER_CAPACITY)
    internal val internalEffectFlow: Flow<Effect> = _effectChannel.receiveAsFlow()

    private val _errorChannel = Channel<Throwable>(capacity = BUFFER_CAPACITY)

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

/**
 * Bound for the effect / event-error channels in [PresenterScope]. Sized for
 * typical UI cadence (single-digit effects per second) with several seconds of
 * headroom; a sustained overflow indicates either a runaway producer or a
 * stalled consumer, both of which we want to surface as a producer suspension
 * rather than a silent leak. File-private to keep the constant out of the
 * public API surface (`const val` inside the class would expose it as a
 * static field on `PresenterScope`).
 */
private const val BUFFER_CAPACITY: Int = 64
