package com.kitakkun.fusio.demo

import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo

data class Task(
    val id: Long,
    val title: String,
    val completed: Boolean,
)

enum class TaskFilter { All, Active, Completed }

// ---------- TaskList (sub-presenter) ----------
//
// Owns the list of tasks and all the add / toggle / remove bookkeeping.
// Knows nothing about filtering, UI, or snackbars — just emits effects
// for "something interesting happened" and lets the parent decide what to
// do about it (here, show a snackbar).

sealed interface TaskListEvent {
    data class Add(val title: String) : TaskListEvent
    data class Toggle(val id: Long) : TaskListEvent
    data class Remove(val id: Long) : TaskListEvent
}

sealed interface TaskListEffect {
    data class Added(val title: String) : TaskListEffect
    data class Completed(val title: String) : TaskListEffect
}

data class TaskListState(val tasks: List<Task>)

// ---------- Filter (sub-presenter) ----------
//
// Trivial state-machine — stores the currently selected filter. Its only
// job is to hold one enum value and emit an effect when it changes. Being
// a sibling of TaskList means the two can evolve independently: TaskList
// doesn't need to know the filter exists, and Filter doesn't need access
// to the task list.

sealed interface FilterEvent {
    data class Select(val filter: TaskFilter) : FilterEvent
}

sealed interface FilterEffect {
    data class Changed(val newFilter: TaskFilter) : FilterEffect
}

data class FilterState(val current: TaskFilter)

// ---------- MyScreen (root) ----------
//
// Binds TaskList + Filter as siblings via two `mappedScope { ... }` calls.
// The filter is applied in the parent, which is where the two sub-states
// first come together — neither child can see the other.

sealed interface MyScreenEvent {
    @MapTo(TaskListEvent.Add::class)
    data class AddTask(val title: String) : MyScreenEvent

    @MapTo(TaskListEvent.Toggle::class)
    data class ToggleTask(val id: Long) : MyScreenEvent

    @MapTo(TaskListEvent.Remove::class)
    data class RemoveTask(val id: Long) : MyScreenEvent

    @MapTo(FilterEvent.Select::class)
    data class SelectFilter(val filter: TaskFilter) : MyScreenEvent
}

sealed interface MyScreenEffect {
    @MapFrom(TaskListEffect.Added::class)
    data class ShowTaskAdded(val title: String) : MyScreenEffect

    @MapFrom(TaskListEffect.Completed::class)
    data class ShowTaskCompleted(val title: String) : MyScreenEffect

    @MapFrom(FilterEffect.Changed::class)
    data class ShowFilterChanged(val newFilter: TaskFilter) : MyScreenEffect
}

data class MyScreenUiState(
    val visibleTasks: List<Task>,
    val filter: TaskFilter,
    val totalCount: Int,
    val activeCount: Int,
    val completedCount: Int,
)
