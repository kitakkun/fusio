package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Forwards every event-processing error raised in [childScope] up to
 * [parentScope]'s error stream so a single root-level observer sees every
 * `on<E>` handler crash in the presenter tree.
 *
 * **Not part of the user-facing API.** The Fusio compiler plugin emits
 * a call to this function automatically next to every `fuse { … }`
 * rewrite — application code shouldn't need to invoke it directly. It's
 * `public` only because the plugin-generated code lives in the consumer
 * module and needs to reach this symbol.
 */
@Composable
public fun forwardEventErrors(
    childScope: PresenterScope<*, *>,
    parentScope: PresenterScope<*, *>,
) {
    // Keyed on both scopes; see docs/runtime-implementation-notes.md ("Why each LaunchedEffect ... is
    // keyed on both scopes") for the reparent-vs-steady-state reasoning.
    LaunchedEffect(childScope, parentScope) {
        childScope.eventErrorFlow.collect { error ->
            parentScope.recordEventError(error)
        }
    }
}