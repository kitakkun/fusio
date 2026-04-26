package com.kitakkun.fusio

import androidx.compose.runtime.Stable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Receiver context for a presenter body — both top-level (`buildPresenter
 * { … }`) and sub-presenter
 * (`@Composable fun PresenterScope<Event, Effect>.foo()`).
 *
 * Inside the body you don't construct one of these directly; you read from
 * it via [on] (handle a typed event subtype) and write to it via
 * [emitEffect] (push a side effect out to the parent / UI).
 *
 * ```kotlin
 * @Composable
 * fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState {
 *     var tasks by remember { mutableStateOf(listOf<Task>()) }
 *
 *     on<TaskListEvent.Add> { event ->
 *         tasks = tasks + Task(event.title)
 *         emitEffect(TaskListEffect.Added(event.title))
 *     }
 *
 *     return TaskListState(tasks)
 * }
 * ```
 *
 * ## Effects and event-processing errors
 *
 * Every effect published via [emitEffect] flows out through [Presentation.effectFlow]
 * (or through the parent's effect stream when this scope is fused into a
 * larger presenter via `fuse { … }` and the parent declares `@MapFrom`).
 * Anything an `on<E>` handler throws — except `CancellationException` —
 * lands on [eventErrorFlow] for observers to log or report; the offending
 * event is dropped and the presenter keeps running.
 *
 * Single-consumer semantics: each effect / error is delivered to exactly
 * one collector. If you need to fan out to multiple collectors (e.g.,
 * mirror effects to both UI and analytics), wrap the exposed `Flow` with
 * `shareIn(scope, SharingStarted.Eagerly)` at your call site.
 */
@Stable
public class PresenterScope<Event, Effect>(
    @PublishedApi internal val eventFlow: Flow<Event>,
) {
    // Channel rather than MutableSharedFlow, bounded capacity, default
    // SUSPEND-overflow. See docs/runtime-implementation-notes.md ("Why Channel ... backs effects and
    // event errors") for the buffer-vs-replay rationale.
    private val effectChannel = Channel<Effect>(capacity = BUFFER_CAPACITY)
    internal val internalEffectFlow: Flow<Effect> = effectChannel.receiveAsFlow()

    private val errorChannel = Channel<Throwable>(capacity = BUFFER_CAPACITY)

    /**
     * Stream of exceptions thrown by `on<E>` handlers in this scope (and
     * in any child scopes fused into it). The presenter itself keeps
     * running — observe this flow if you want to log, report, or surface
     * the failure to the user.
     *
     * `CancellationException` is propagated through coroutine cancellation
     * machinery instead, so coroutine cooperative cancellation keeps
     * working as expected.
     */
    public val eventErrorFlow: Flow<Throwable> = errorChannel.receiveAsFlow()

    /**
     * Push [effect] out to whatever is collecting this scope's effect
     * stream — `Presentation.effectFlow` for top-level presenters, the
     * parent's mapped effect stream for fused sub-presenters.
     *
     * Suspends only if the consumer is severely backed up (the buffer
     * holds 64 items by default).
     */
    public suspend fun emitEffect(effect: Effect) {
        effectChannel.send(effect)
    }

    /**
     * Funnels a handler exception into [eventErrorFlow]. Published via
     * `@PublishedApi` because the [on] helper is `inline` and synthesises
     * the catch block at the call site.
     */
    @PublishedApi
    internal suspend fun recordEventError(t: Throwable) {
        errorChannel.send(t)
    }

    internal fun close() {
        effectChannel.close()
        errorChannel.close()
    }
}

// File-private rather than a companion `const val` because the latter
// would expose BUFFER_CAPACITY as a public static field on PresenterScope.
private const val BUFFER_CAPACITY: Int = 64
