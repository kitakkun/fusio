package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Subscribes to events of type [E] from the enclosing [PresenterScope] and
 * runs [handler] for each one.
 *
 * Use this inside a presenter body — top-level (`buildPresenter { … }`) or
 * sub-presenter (`@Composable PresenterScope<E, F>.foo()`) — to wire a
 * sealed event subtype to its behaviour:
 *
 * ```kotlin
 * @Composable
 * fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState {
 *     var tasks by remember { mutableStateOf(listOf<Task>()) }
 *
 *     on<TaskListEvent.Add> { event ->
 *         tasks = tasks + Task(event.title)
 *     }
 *     on<TaskListEvent.Remove> { event ->
 *         tasks = tasks.filterNot { it.id == event.id }
 *     }
 *
 *     return TaskListState(tasks)
 * }
 * ```
 *
 * Calling `on<EventBase>` with the parent sealed type covers every subtype
 * via `filterIsInstance`, so a single handler can catch-all if you want.
 *
 * ## Recomposition
 *
 * The latest [handler] lambda wins on every recomposition — closures over
 * recomposition-dependent values (e.g. a `currentUser` parameter on the
 * enclosing presenter) see fresh values, not the snapshot from first
 * composition. Events in flight aren't dropped while the handler swaps.
 *
 * ## Errors
 *
 * If [handler] throws, the exception lands on
 * [PresenterScope.eventErrorFlow] (and bubbles to the root presenter via
 * `fuse`'s generated forwarding). The offending event is dropped, the
 * presenter keeps running, and subsequent events flow normally — observe
 * the error stream to log or report. `CancellationException` is rethrown
 * so coroutine cancellation works as expected.
 */
@Composable
public inline fun <reified E> PresenterScope<*, *>.on(
    noinline handler: suspend (E) -> Unit,
) {
    val flow = eventFlow
    val scope = this
    // rememberUpdatedState so the LaunchedEffect doesn't restart every time
    // the lambda identity changes — keeps the collect loop alive while
    // letting it pick up the freshest closure.
    val currentHandler by rememberUpdatedState(handler)
    LaunchedEffect(Unit) {
        flow.filterIsInstance<E>().collect { event ->
            try {
                currentHandler(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                scope.recordEventError(t)
            }
        }
    }
}
