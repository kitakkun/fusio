package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Forwards effects from [childScope] to [parentScope] through the
 * `@MapFrom`-derived [mapper] (subtypes that don't map produce `null`
 * and are dropped).
 *
 * **Not part of the user-facing API.** The Fusio compiler plugin emits
 * a call to this function automatically next to every `fuse { … }`
 * rewrite, with [mapper] generated from the parent effect type's
 * `@MapFrom` annotations. Application code shouldn't need to invoke
 * it directly. It's `public` only because the plugin-generated code
 * lives in the consumer module and needs to reach this symbol.
 */
@Composable
public fun <ChildEffect, ParentEffect> forwardEffects(
    childScope: PresenterScope<*, ChildEffect>,
    parentScope: PresenterScope<*, ParentEffect>,
    mapper: (ChildEffect) -> ParentEffect?,
) {
    // Keyed on both scopes; see docs/runtime-implementation-notes.md ("Why each LaunchedEffect ... is
    // keyed on both scopes") for the reparent-vs-steady-state reasoning.
    LaunchedEffect(childScope, parentScope) {
        childScope.internalEffectFlow.collect { childEffect ->
            val mapped = mapper(childEffect)
            if (mapped != null) parentScope.emitEffect(mapped)
        }
    }
}
