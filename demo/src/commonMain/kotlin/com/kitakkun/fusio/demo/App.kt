package com.kitakkun.fusio.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Todo demo. The Compose tree only pushes MyScreenEvent values onto the
 * flow and reads the assembled MyScreenUiState — the fact that two
 * sibling sub-presenters (TaskList and Filter) are actually producing
 * that state is invisible to the UI.
 */
@Composable
fun App() {
    val eventFlow = remember { MutableSharedFlow<MyScreenEvent>(extraBufferCapacity = 64) }
    val effects = remember { mutableStateListOf<MyScreenEffect>() }

    val fusio = myScreenPresenter(eventFlow)
    val state = fusio.state

    LaunchedEffect(fusio.effectFlow) {
        fusio.effectFlow.collect { effects += it }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Todo", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "TaskList + Filter are sibling sub-presenters. The parent just " +
                        "applies the filter to the list — neither child knows the other exists.",
                    style = MaterialTheme.typography.bodySmall,
                )

                NewTaskInput(
                    onSubmit = { title -> eventFlow.tryEmit(MyScreenEvent.AddTask(title)) },
                )

                FilterChips(
                    current = state.filter,
                    onSelect = { eventFlow.tryEmit(MyScreenEvent.SelectFilter(it)) },
                )

                TaskListView(
                    tasks = state.visibleTasks,
                    onToggle = { eventFlow.tryEmit(MyScreenEvent.ToggleTask(it)) },
                    onRemove = { eventFlow.tryEmit(MyScreenEvent.RemoveTask(it)) },
                    modifier = Modifier.weight(1f, fill = true),
                )

                Footer(
                    total = state.totalCount,
                    active = state.activeCount,
                    completed = state.completedCount,
                )

                EffectsLog(
                    effects = effects,
                    onClear = { effects.clear() },
                )
            }
        }
    }
}

@Composable
private fun NewTaskInput(onSubmit: (String) -> Unit) {
    // Draft text is a pure-UI concern — it doesn't need a sub-presenter.
    // Only the *submission* is a parent event.
    var draft by remember { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("New task") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                if (draft.isNotBlank()) {
                    onSubmit(draft)
                    draft = ""
                }
            },
        ) { Text("Add") }
    }
}

@Composable
private fun FilterChips(current: TaskFilter, onSelect: (TaskFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskFilter.entries.forEach { f ->
            FilterChip(
                selected = f == current,
                onClick = { onSelect(f) },
                label = { Text(f.name) },
            )
        }
    }
}

@Composable
private fun TaskListView(
    tasks: List<Task>,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        if (tasks.isEmpty()) {
            Text(
                "No tasks to show. Add one above, or switch the filter.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { onToggle(task.id) },
                        onRemove = { onRemove(task.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.completed, onCheckedChange = { onToggle() })
        Text(
            text = task.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (task.completed) TextDecoration.LineThrough else null,
        )
        TextButton(onClick = onRemove) { Text("✕") }
    }
}

@Composable
private fun Footer(total: Int, active: Int, completed: Int) {
    Text(
        "Total: $total   •   Active: $active   •   Completed: $completed",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun EffectsLog(effects: List<MyScreenEffect>, onClear: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Effects log (${effects.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (effects.isNotEmpty()) {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                }
            }
            if (effects.isEmpty()) {
                Text(
                    "— add, complete, or change the filter to see effects bubble up",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                effects.takeLast(8).forEach { eff ->
                    Text(eff.humanReadable(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun MyScreenEffect.humanReadable(): String = when (this) {
    is MyScreenEffect.ShowTaskAdded -> "✓ Added: $title"
    is MyScreenEffect.ShowTaskCompleted -> "🎉 Completed: $title"
    is MyScreenEffect.ShowFilterChanged -> "Filter → $newFilter"
}
