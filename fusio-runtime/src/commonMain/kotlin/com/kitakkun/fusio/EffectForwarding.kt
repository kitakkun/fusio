package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Runtime helper used by Fusio's IR transformer to forward child presenter effects
 * back into the parent presenter's effect channel.
 *
 * The compiler plugin generates the [mapper] lambda from `@MapFrom` annotations on
 * the parent Effect sealed subtypes. Each mapping becomes a branch in a `when`
 * expression.
 *
 * We keep this in runtime (not generated as raw IR) so the compiler plugin only
 * has to synthesize the mapping lambda body — not the whole LaunchedEffect /
 * collect / emit plumbing.
 */
@Composable
public fun <ChildEffect, ParentEffect> forwardEffects(
    childScope: PresenterScope<*, ChildEffect>,
    parentScope: PresenterScope<*, ParentEffect>,
    mapper: (ChildEffect) -> ParentEffect?,
) {
    // Keyed on the two scopes so a reparented child (scope identity change)
    // restarts collection against the new source instead of silently
    // observing the original forever. Under the `remember { … }` wrap the
    // IR transformer emits around the child scope's creation, identity is
    // stable across recompositions — the keyed form pays nothing at
    // steady state and correctly restarts when identity really shifts.
    LaunchedEffect(childScope, parentScope) {
        childScope.internalEffectFlow.collect { childEffect ->
            val mapped = mapper(childEffect)
            if (mapped != null) parentScope.emitEffect(mapped)
        }
    }
}
