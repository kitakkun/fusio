package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Runtime helper used by Fusio's IR transformer to lift a child presenter's
 * swallowed `on<E>` handler crashes into the parent presenter's
 * [PresenterScope.eventErrorFlow] stream. The compiler plugin emits a call
 * to this function *unconditionally* next to every `fuse { … }` rewrite —
 * error forwarding doesn't need per-annotation configuration the way
 * [forwardEffects] does, because errors don't need type remapping: a
 * `Throwable` flows through unchanged.
 *
 * Structure mirrors [forwardEffects] so the two are easy to keep in sync:
 * a single `LaunchedEffect(Unit)` collects from the child and republishes
 * to the parent via the scope's `@PublishedApi` recorder.
 */
@Composable
public fun forwardEventErrors(
    childScope: PresenterScope<*, *>,
    parentScope: PresenterScope<*, *>,
) {
    // Keyed on the two scopes for the same reason [forwardEffects] is —
    // correct behaviour when the IR transformer emits a reparented scope,
    // no-op restart in steady-state thanks to the `remember { … }`
    // wrapping that keeps identity stable.
    LaunchedEffect(childScope, parentScope) {
        childScope.eventErrorFlow.collect { error ->
            parentScope.recordEventError(error)
        }
    }
}
