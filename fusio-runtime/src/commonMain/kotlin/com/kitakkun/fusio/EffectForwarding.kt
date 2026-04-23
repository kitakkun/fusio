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
fun <ChildEffect, ParentEffect> forwardEffects(
    childScope: PresenterScope<*, ChildEffect>,
    parentScope: PresenterScope<*, ParentEffect>,
    mapper: (ChildEffect) -> ParentEffect?,
) {
    LaunchedEffect(Unit) {
        childScope.internalEffectFlow.collect { childEffect ->
            val mapped = mapper(childEffect)
            if (mapped != null) parentScope.emitEffect(mapped)
        }
    }
}
