package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Screen-level entry point. Runs [block] inside a freshly-`remember`ed
 * [PresenterScope] and packages the result as a [Presentation] the UI
 * can consume.
 *
 * ```kotlin
 * @Composable
 * fun myScreenPresenter(): Presentation<MyState, MyEvent, MyEffect> = buildPresenter {
 *     var count by remember { mutableStateOf(0) }
 *
 *     on<MyEvent.Increment> { count += 1 }
 *     on<MyEvent.Reset> { count = 0 }
 *
 *     MyState(count)
 * }
 * ```
 *
 * The returned `Presentation` carries:
 * - `state` — what [block] returned, ready to render
 * - `effectFlow` — every value passed to `emitEffect` inside [block]
 * - `eventErrorFlow` — any exception thrown by an `on<E>` handler
 * - `send` — the entry point to push an event back into [block]
 *
 * The internal event channel is allocated once per composition and lives
 * for the lifetime of the call site. UI sends events via
 * `presentation.send(event)`; there's no external `MutableSharedFlow` to
 * thread in. To inject events from a separate `Flow` (deep link,
 * navigation event, etc.), bridge it from the caller:
 *
 * ```kotlin
 * val presentation = myScreenPresenter()
 * LaunchedEffect(externalFlow) {
 *     externalFlow.collect { presentation.send(it) }
 * }
 * ```
 *
 * When the surrounding composition exits, the scope's effect and
 * event-error channels are closed and any background collectors are torn
 * down — no extra cleanup required from the caller.
 */
@Composable
public fun <Event, Effect, UiState> buildPresenter(
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Presentation<UiState, Event, Effect> {
    // remember without a key: the event flow and scope live for the whole
    // composition lifetime. DisposableEffect closes channels on dispose so
    // no background collector leaks.
    val eventFlow = remember {
        MutableSharedFlow<Event>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    }
    val scope = remember { PresenterScope<Event, Effect>(eventFlow) }

    DisposableEffect(scope) {
        onDispose { scope.close() }
    }

    val uiState = scope.block()

    return Presentation(
        state = uiState,
        effectFlow = scope.internalEffectFlow,
        eventErrorFlow = scope.eventErrorFlow,
        // tryEmit drops on overflow; the 64-slot buffer below is sized for
        // typical UI cadence so this shouldn't trip in practice.
        send = { event -> eventFlow.tryEmit(event) },
    )
}

// Buffer headroom for the internal MutableSharedFlow. 64 covers typical UI
// input bursts (clicks, text changes, navigation) without unbounded growth.
private const val EVENT_BUFFER_CAPACITY: Int = 64
