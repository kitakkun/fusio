package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.fuse

/**
 * Root presenter for the Todo demo. Composes the TaskList and Filter
 * sub-presenters side by side via two `fuse { ... }` calls and
 * derives the UI-shaped state by intersecting the two.
 *
 * What this function owns:
 *   - Applying the selected filter to the task list (pure derivation).
 *   - Computing the three summary counters for the footer.
 *
 * What it *doesn't* own — and therefore can't accidentally break:
 *   - How tasks get added / toggled / removed  (TaskListPresenter)
 *   - Which filter is selected                 (FilterPresenter)
 *   - Snackbar payloads                        (@MapFrom wiring generates the forwarding)
 */
@Composable
fun myScreenPresenter(): Presentation<MyScreenUiState, MyScreenEffect, MyScreenEvent> = buildPresenter {
    val tasks = fuse { taskList() }
    val filter = fuse { filter() }

    val visible = when (filter.current) {
        TaskFilter.All -> tasks.tasks
        TaskFilter.Active -> tasks.tasks.filterNot { it.completed }
        TaskFilter.Completed -> tasks.tasks.filter { it.completed }
    }

    MyScreenUiState(
        visibleTasks = visible,
        filter = filter.current,
        totalCount = tasks.tasks.size,
        activeCount = tasks.tasks.count { !it.completed },
        completedCount = tasks.tasks.count { it.completed },
    )
}
