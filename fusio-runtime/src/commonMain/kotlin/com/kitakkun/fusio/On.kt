package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Registers a handler for events of type [E] arriving on the enclosing
 * [PresenterScope]'s event flow.
 *
 * ## Error handling
 *
 * If [handler] throws, the exception is caught and routed into
 * [PresenterScope.handlerErrors] instead of propagating up the
 * `LaunchedEffect` scope (which would kill the composition). The offending
 * event is effectively dropped and subsequent events continue to flow.
 * [CancellationException] is always re-thrown so coroutine cooperative
 * cancellation keeps working.
 *
 * Observe `scope.handlerErrors` at the root presenter (or on the parent
 * scope a child `fuse`d into) to react to crashes — log, emit a user-
 * facing error effect, increment a metric, etc.
 */
@Composable
public inline fun <reified E> PresenterScope<*, *>.on(
    noinline handler: suspend (E) -> Unit,
) {
    val flow = eventFlow
    val scope = this
    LaunchedEffect(Unit) {
        flow.filterIsInstance<E>().collect { event ->
            try {
                handler(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                scope.recordHandlerError(t)
            }
        }
    }
}
