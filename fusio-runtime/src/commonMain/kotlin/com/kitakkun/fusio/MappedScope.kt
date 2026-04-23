package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Delegates to a sub-presenter and returns its state. Effects emitted by the
 * sub-presenter via its [PresenterScope.emitEffect] are forwarded back to the
 * parent scope through the `@MapFrom` mappings declared on the parent effect
 * subtypes.
 *
 * This function is a stub — the Fusio compiler plugin rewrites every call site
 * into the actual event-mapping / effect-forwarding plumbing. If the plugin is
 * not applied the call throws at runtime.
 *
 * ## Why `inline` / no `@Composable`
 *
 * Declared `inline` so the call site's @Composable context is what governs the
 * block lambda — this function itself doesn't need to be marked @Composable.
 * The IR transformer that rewrites mappedScope runs in `IrGenerationExtension`
 * which fires BEFORE inline lowering, so the call is still visible to us and
 * gets replaced before inlining would ever expand the stub body.
 *
 * The [contract] tells the compiler the block can run at most once. Statically
 * the stub body throws before calling it (`AT_MOST_ONCE`); in practice the IR
 * transformer replaces the call so the block is invoked exactly once in real
 * code. AT_MOST_ONCE is the strongest the stub body can honestly claim.
 */
@OptIn(ExperimentalContracts::class)
public inline fun <ChildEvent, ChildEffect, ChildState> PresenterScope<*, *>.mappedScope(
    block: @Composable PresenterScope<ChildEvent, ChildEffect>.() -> ChildState,
): ChildState {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    error(
        "mappedScope requires the Fusio Compiler Plugin. " +
            "Make sure 'com.kitakkun.fusio' Gradle plugin is applied."
    )
}
