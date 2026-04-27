package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.on

@Composable
fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState {
    // `nextId` is purely bookkeeping — the UI never sees it. Keeping it in
    // remember alongside `tasks` is a sub-presenter-local concern.
    var nextId by remember { mutableStateOf(1L) }
    var tasks by remember { mutableStateOf(listOf<Task>()) }

    on<TaskListEvent.Add> { event ->
        val trimmed = event.title.trim()
        if (trimmed.isEmpty()) return@on
        tasks = tasks + Task(id = nextId, title = trimmed, completed = false)
        nextId += 1
        emitEffect(TaskListEffect.Added(trimmed))
    }

    on<TaskListEvent.Toggle> { event ->
        val target = tasks.find { it.id == event.id } ?: return@on
        val updated = target.copy(completed = !target.completed)
        tasks = tasks.map { if (it.id == event.id) updated else it }
        // Only fire "Completed" on the false→true edge; un-checking should
        // stay quiet so the snackbar doesn't flap.
        if (!target.completed && updated.completed) {
            emitEffect(TaskListEffect.Completed(updated.title))
        }
    }

    on<TaskListEvent.Remove> { event ->
        tasks = tasks.filterNot { it.id == event.id }
    }

    return TaskListState(tasks)
}
