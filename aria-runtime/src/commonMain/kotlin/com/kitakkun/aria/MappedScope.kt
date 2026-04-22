package com.kitakkun.aria

import androidx.compose.runtime.Composable

/**
 * Delegates to a sub-presenter and returns its state. Effects emitted by the
 * sub-presenter via its [PresenterScope.emitEffect] are forwarded back to the
 * parent scope through the `@MapFrom` mappings declared on the parent effect
 * subtypes.
 *
 * This function is a stub — the Aria compiler plugin rewrites every call site
 * into the actual event-mapping / effect-forwarding plumbing. If the plugin is
 * not applied the call throws at runtime.
 */
@Composable
fun <ChildEvent, ChildEffect, ChildState> PresenterScope<*, *>.mappedScope(
    block: @Composable PresenterScope<ChildEvent, ChildEffect>.() -> ChildState,
): ChildState {
    error(
        "mappedScope requires the Aria Compiler Plugin. " +
            "Make sure 'com.kitakkun.aria' Gradle plugin is applied."
    )
}
