package com.github.kitakkun.aria

import androidx.compose.runtime.Composable

// Stub that will be replaced by the IR Transformer.
// Throws at runtime if compiler plugin is not applied.
@Composable
fun <ChildEvent, ChildEffect, ChildState> PresenterScope<*, *>.mappedScope(
    block: @Composable PresenterScope<ChildEvent, ChildEffect>.() -> Aria<ChildState, ChildEffect>,
): ChildState {
    error(
        "mappedScope requires the Aria Compiler Plugin. " +
            "Make sure 'com.github.kitakkun.aria' Gradle plugin is applied."
    )
}
