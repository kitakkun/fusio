package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * ## Lifecycle semantics
 *
 * The internal event flow and the [PresenterScope] are both `remember`ed
 * with no key, so they survive every recomposition for the lifetime of the
 * surrounding composition. The flow is a `MutableSharedFlow` with
 * [EVENT_BUFFER_CAPACITY] extra slots — enough headroom for typical UI
 * burst patterns without unbounded memory.
 *
 * The [DisposableEffect] keyed on the scope calls [PresenterScope.close]
 * when the composition exits, so effect / handler-error channels close
 * deterministically and no background collector leaks.
 *
 * ## Bridging external event sources
 *
 * `Presentation.send` is the only input path. To drive a presenter from an
 * external `Flow<Event>` (URL deeplink, navigation event, …), bridge it in
 * the caller:
 *
 * ```kotlin
 * val presentation = buildPresenter<MyEvent, …, …> { /* body */ }
 * LaunchedEffect(externalFlow) {
 *     externalFlow.collect { presentation.send(it) }
 * }
 * ```
 */
@Composable
public fun <Event, Effect, UiState> buildPresenter(
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Presentation<UiState, Event, Effect> {
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
        // tryEmit returns false on overflow; we ignore it. The buffer is
        // sized so this shouldn't happen under realistic UI cadence; if a
        // workload really needs back-pressure, expose buffer as a parameter.
        send = { event -> eventFlow.tryEmit(event) },
    )
}

/**
 * Buffer headroom for the internal `MutableSharedFlow`. Sized for typical
 * UI input cadence — clicks, text changes, navigation — without imposing
 * unbounded memory growth. If a real workload exceeds this, lift it to a
 * `buildPresenter` parameter.
 */
private const val EVENT_BUFFER_CAPACITY: Int = 64
