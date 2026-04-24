package com.kitakkun.fusio

import androidx.compose.runtime.Composable

/**
 * Shape of a Fusio sub-presenter body — a `@Composable` extension on
 * [PresenterScope] that returns the child [State].
 *
 * Re-exported as a named type so presenter signatures at the type level
 * (fields, function parameters, generic constraints) don't have to repeat
 * the `@Composable PresenterScope<Event, Effect>.() -> State` incantation
 * verbatim.
 */
public typealias PresenterBody<Event, Effect, State> =
    @Composable PresenterScope<Event, Effect>.() -> State

/**
 * Binds a sub-presenter's `<Event, Effect, State>` types at its declaration
 * site and returns the body as a [PresenterBody] (i.e. a `@Composable
 * PresenterScope<Event, Effect>.() -> State` lambda).
 *
 * This is a stylistic helper, not a new abstraction — the body is returned
 * unchanged. Sub-presenters declared via [presenter] and ones declared as
 * free-function `@Composable PresenterScope<E, F>.foo(): S` are
 * interchangeable; `fuse { foo() }` works on both forms.
 *
 * ## Why
 *
 * The free-function shape reads:
 *
 * ```kotlin
 * @Composable
 * fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState { … }
 * ```
 *
 * which puts the `<Event, Effect>` in the extension-receiver position —
 * visually heavy for what is essentially metadata about the presenter.
 * [presenter] flips that emphasis: the three type parameters sit on a
 * single factory call, the body itself is a plain lambda with `on<>` /
 * `emitEffect` available on the implicit `this`:
 *
 * ```kotlin
 * val taskList = presenter<TaskListEvent, TaskListEffect, TaskListState> {
 *     var tasks by remember { mutableStateOf(listOf<Task>()) }
 *     on<TaskListEvent.Add> { event ->
 *         tasks = tasks + Task(event.title)
 *         emitEffect(TaskListEffect.Added(event.title))
 *     }
 *     TaskListState(tasks)
 * }
 * ```
 *
 * Functionally identical; pick whichever style reads best for your
 * codebase.
 */
public fun <Event, Effect, State> presenter(
    body: PresenterBody<Event, Effect, State>,
): PresenterBody<Event, Effect, State> = body
