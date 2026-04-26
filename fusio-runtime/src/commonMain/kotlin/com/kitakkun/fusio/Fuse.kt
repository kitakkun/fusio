package com.kitakkun.fusio

import androidx.compose.runtime.Composable
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Fuses a sub-presenter's scope into the current one and returns its
 * state.
 *
 * Use inside a parent presenter body to compose a child presenter
 * declared as `@Composable fun PresenterScope<ChildEvent, ChildEffect>.foo()`:
 *
 * ```kotlin
 * @Composable
 * fun myScreenPresenter(): Presentation<MyScreenUiState, MyScreenEvent, MyScreenEffect> = buildPresenter {
 *     val tasks  = fuse { taskList() }   // ChildEvent = TaskListEvent, etc.
 *     val filter = fuse { filter() }
 *     MyScreenUiState(tasks, filter)
 * }
 * ```
 *
 * Routing between parent and child is declared by annotations — not by
 * arguments to `fuse`:
 *
 * - `@MapTo(ChildEvent.X::class)` on a parent event subtype → events of
 *   that parent subtype are translated into the child subtype and fed
 *   into the child's `on<>` handlers.
 * - `@MapFrom(ChildEffect.Y::class)` on a parent effect subtype → effects
 *   the child emits via [PresenterScope.emitEffect] are lifted into the
 *   matching parent effect and surface on `Presentation.effectFlow`.
 *
 * The Fusio compiler plugin rewrites every `fuse { … }` call site at IR
 * time to generate the event-mapping / effect-forwarding plumbing from
 * those annotations. **If the Fusio Gradle plugin is not applied, this
 * function throws at runtime** — apply `id("com.kitakkun.fusio")` in
 * your build script.
 *
 * The block runs at most once per call (`InvocationKind.AT_MOST_ONCE`)
 * — the compiler plugin's rewrite invokes it exactly once; the un-rewritten
 * stub never reaches it.
 */
@OptIn(ExperimentalContracts::class)
public inline fun <ChildEvent, ChildEffect, ChildState> PresenterScope<*, *>.fuse(
    block: @Composable PresenterScope<ChildEvent, ChildEffect>.() -> ChildState,
): ChildState {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    error(
        "fuse { ... } requires the Fusio Compiler Plugin. " +
            "Make sure 'com.kitakkun.fusio' Gradle plugin is applied.",
    )
}
