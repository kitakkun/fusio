package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Collects each value pushed to this presentation's [Presentation.effectFlow]
 * and forwards it to [handler]. The collection is launched in a
 * [LaunchedEffect] keyed on the flow itself, so it survives recomposition
 * and re-subscribes only when the underlying flow changes (effectively
 * once per `buildPresenter` call site).
 *
 * Equivalent to writing:
 *
 * ```kotlin
 * LaunchedEffect(presentation.effectFlow) {
 *     presentation.effectFlow.collect(handler)
 * }
 * ```
 *
 * but without leaking the flow type at the call site.
 *
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val presentation = myScreenPresenter()
 *
 *     presentation.OnEffect { effect ->
 *         when (effect) {
 *             is MyEffect.ShowSnackbar -> snackbar.showSnackbar(effect.message)
 *             is MyEffect.Navigate    -> navController.navigate(effect.route)
 *         }
 *     }
 *
 *     MyScreenContent(state = presentation.state)
 * }
 * ```
 *
 * Single-consumer semantics: `effectFlow` is backed by a `Channel` under
 * the hood, so each effect is delivered exactly once. Calling [OnEffect]
 * twice on the same `Presentation` racing for the same effects is therefore
 * not supported — split the effect type or wrap the flow at a higher level
 * if multi-cast is required.
 */
@Composable
public fun <Effect> Presentation<*, *, Effect>.OnEffect(
    handler: suspend (Effect) -> Unit,
) {
    LaunchedEffect(effectFlow) {
        effectFlow.collect(handler)
    }
}

/**
 * Collects exceptions raised by `on<E>` handlers (i.e. values pushed to
 * this presentation's [Presentation.eventErrorFlow]) and forwards each to
 * [handler]. Same shape as [OnEffect] — just the bug-detection channel
 * rather than the side-effect channel.
 *
 * ```kotlin
 * presentation.OnEventError { error -> Logger.error("presenter crashed", error) }
 * ```
 *
 * This is intended for telemetry / logging only. `eventErrorFlow` is *not*
 * a recovery mechanism — see [Presentation.eventErrorFlow] for the full
 * rationale.
 */
@Composable
public fun Presentation<*, *, *>.OnEventError(
    handler: suspend (Throwable) -> Unit,
) {
    LaunchedEffect(eventErrorFlow) {
        eventErrorFlow.collect(handler)
    }
}
