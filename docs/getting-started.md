# Getting started with Fusio

Long-form walkthrough that the README links to.

## Motivation

Without decomposition, a presenter accretes every feature into one body:

```kotlin
@Composable
fun myScreenPresenter(): MyScreenUiState {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    var filter by remember { mutableStateOf(TaskFilter.All) }
    var nextId by remember { mutableStateOf(1L) }
    LaunchedEffect(Unit) { /* AddTask handler */ }
    LaunchedEffect(Unit) { /* ToggleTask handler */ }
    LaunchedEffect(Unit) { /* SelectFilter handler */ }
    // …each new feature adds another branch…
    return MyScreenUiState(/* … */)
}
```

The natural fix is to split it. But the manual split forces I/O
boilerplate at **both seams** — events flowing down to children,
effects flowing back up:

```kotlin
@Composable
fun myScreenPresenter(): MyScreenUiState {
    // Down: per-child event channel + a dispatch switch.
    val taskListEvents = remember { MutableSharedFlow<TaskListEvent>(extraBufferCapacity = 64) }
    val filterEvents   = remember { MutableSharedFlow<FilterEvent>(extraBufferCapacity = 64) }
    fun dispatch(event: MyScreenEvent) = when (event) {
        is MyScreenEvent.AddTask      -> taskListEvents.tryEmit(TaskListEvent.Add(event.title))
        is MyScreenEvent.ToggleTask   -> taskListEvents.tryEmit(TaskListEvent.Toggle(event.id))
        is MyScreenEvent.RemoveTask   -> taskListEvents.tryEmit(TaskListEvent.Remove(event.id))
        is MyScreenEvent.SelectFilter -> filterEvents.tryEmit(FilterEvent.Select(event.filter))
    }

    // Up: a callback per effect subtype the parent cares about.
    var lastSnack by remember { mutableStateOf<String?>(null) }
    val tasks = taskListPresenter(
        events = taskListEvents,
        onAdded     = { title -> lastSnack = "Added: $title" },
        onCompleted = { title -> lastSnack = "Completed: $title" },
    )
    val filter = filterPresenter(
        events = filterEvents,
        onChanged = { f -> lastSnack = "Filter → $f" },
    )

    // Each new parent event = a `dispatch` branch; each new child effect
    // = a callback on every child's signature.
    return MyScreenUiState(tasks, filter, lastSnack)
}
```

Fusio resolves both seams. Routing is declared once on the parent's
sealed event / effect types — `@MapTo` for down, `@MapFrom` for up —
and the compiler synthesises the per-seam plumbing. Children stay
parameterless extensions on `PresenterScope<…>`; the parent stays a
composition step.

### Why not roll your own routing?

Two non-Fusio shortcuts dodge the dispatch switch.

**Option A — child event extends parent (inheritance):**

```kotlin
sealed interface MyScreenEvent
sealed interface TaskListEvent : MyScreenEvent { /* … */ }

val tasks = taskListPresenter(events = events.filterIsInstance<TaskListEvent>())
```

**Option B — parent wraps child events (composition):**

```kotlin
sealed interface MyScreenEvent {
    data class TaskList(val event: TaskListEvent) : MyScreenEvent
}

val tasks = taskListPresenter(
    events = events.filterIsInstance<MyScreenEvent.TaskList>().map { it.event },
)
```

Both work for events and (symmetrically) for effects. Both pay:

- **The UI names the child.** A sends `TaskListEvent.Add(title)`; B
  sends `MyScreenEvent.TaskList(TaskListEvent.Add(title))`. Either
  way, refactor TaskList → SmartList and every UI call site changes.
  `@MapTo` lets the UI stay on domain names like
  `MyScreenEvent.AddTask(title)`.
- **A couples the child to the parent.** `TaskListEvent : MyScreenEvent`
  blocks reusing TaskList from another screen. B avoids this but
  keeps the UI-leak.
- **Neither renames across the seam.** Parent `AddTask(title)` ≠
  child `Add(title)` is impossible without copying. Fusio's per-property
  mapper handles the rename, FIR-checked.
- **No exhaustiveness check.** Sealed inheritance / wrapping doesn't
  enforce that every parent subtype actually reaches a child. Fusio's
  `MISSING_EVENT_HANDLER` checker does.

## What you write

- `buildPresenter { … }` — screen-level entry, returns
  `Presentation<State, Event, Effect>`. UI calls `presentation.send(event)`.
- `on<Event> { … }` — typed handler inside a presenter body.
- `fuse { subPresenter() }` — fuse a child sub-presenter into the
  current scope. The compiler plugin rewrites this into the actual
  event-mapping / effect-forwarding plumbing at IR time.
- `@MapTo(ChildEvent::class)` on a parent event subtype — "route me
  into this child."
- `@MapFrom(ChildEffect::class)` on a parent effect subtype — "lift
  this child effect up as me."

FIR checkers enforce: same-name/type properties on mapped subtypes,
`@MapFrom` exhaustiveness, and `on<E>` exhaustiveness (configurable
via `fusio { eventHandlerExhaustiveSeverity = … }` —
see [event-handler-exhaustiveness.md](event-handler-exhaustiveness.md)).

## Example: a Todo screen with two sibling sub-presenters

Sub-presenter types stay decoupled — no parent knowledge:

```kotlin
sealed interface TaskListEvent {
    data class Add(val title: String) : TaskListEvent
    data class Toggle(val id: Long) : TaskListEvent
    data class Remove(val id: Long) : TaskListEvent
}
sealed interface TaskListEffect {
    data class Added(val title: String) : TaskListEffect
    data class Completed(val title: String) : TaskListEffect
}
sealed interface FilterEvent { data class Select(val filter: TaskFilter) : FilterEvent }
sealed interface FilterEffect { data class Changed(val newFilter: TaskFilter) : FilterEffect }
```

The parent declares the fusion via annotations:

```kotlin
sealed interface MyScreenEvent {
    @MapTo(TaskListEvent.Add::class)    data class AddTask(val title: String) : MyScreenEvent
    @MapTo(TaskListEvent.Toggle::class) data class ToggleTask(val id: Long) : MyScreenEvent
    @MapTo(TaskListEvent.Remove::class) data class RemoveTask(val id: Long) : MyScreenEvent
    @MapTo(FilterEvent.Select::class)   data class SelectFilter(val filter: TaskFilter) : MyScreenEvent
}
sealed interface MyScreenEffect {
    @MapFrom(TaskListEffect.Added::class)     data class ShowTaskAdded(val title: String) : MyScreenEffect
    @MapFrom(TaskListEffect.Completed::class) data class ShowTaskCompleted(val title: String) : MyScreenEffect
    @MapFrom(FilterEffect.Changed::class)     data class ShowFilterChanged(val newFilter: TaskFilter) : MyScreenEffect
}
```

Each sub-presenter is a `@Composable` extension on its own
`PresenterScope<…>`:

```kotlin
@Composable
fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState { /* … */ }

@Composable
fun PresenterScope<FilterEvent, FilterEffect>.filter(): FilterState { /* … */ }
```

The screen-level presenter fuses them:

```kotlin
@Composable
fun myScreenPresenter(): Presentation<MyScreenUiState, MyScreenEvent, MyScreenEffect> =
    buildPresenter {
        val tasks  = fuse { taskList() }
        val filter = fuse { filter() }

        val visible = when (filter.current) {
            TaskFilter.All       -> tasks.tasks
            TaskFilter.Active    -> tasks.tasks.filterNot { it.completed }
            TaskFilter.Completed -> tasks.tasks.filter    { it.completed }
        }
        MyScreenUiState(visible, filter.current, /* …counts… */)
    }
```

UI calls `presentation.send(MyScreenEvent.AddTask("Buy milk"))`. At
runtime that flows: `@MapTo` → `TaskListEvent.Add("Buy milk")` →
`taskList()`'s `on<TaskListEvent.Add>` → `TaskListEffect.Added("Buy
milk")` → `@MapFrom` → `MyScreenEffect.ShowTaskAdded("Buy milk")` on
`presentation.effectFlow`. All synthesised by the compiler plugin —
no reflection.

A runnable version lives in `demo/`:

```
cd demo
../gradlew runJvm
```

## Project layout

```
fusio-annotations/      @MapTo, @MapFrom                    (KMP)
fusio-runtime/          Presentation, PresenterScope, buildPresenter, on, fuse stub
                                                             (KMP + Compose Multiplatform)
fusio-test/             testPresenter / testSubPresenter harness  (KMP, same targets as runtime)
fusio-compiler-plugin/  FIR checkers + IR transformer       (JVM, single shaded jar)
fusio-gradle-plugin/    KGP integration                     (included build)
demo/                   Compose Desktop Todo app
```

## Build commands

```
./gradlew build                       # compile + test every target, every module
./gradlew :fusio-runtime:allTests     # runtime tests on every platform
./gradlew :fusio-runtime:jvmTest      # JVM only (fastest feedback)
cd demo && ../gradlew runJvm          # launch the Todo demo
```

Gradle 9 configuration cache is on; incremental rebuilds finish under
a second after the first run.

### Plugin ordering

Fusio's IR transformer must run **before** the Compose compiler plugin
so it can rewrite `fuse { … }` before Compose injects `$composer` /
`$changed` into `@Composable` lambdas. The Fusio Gradle plugin
injects `-Xcompiler-plugin-order=com.kitakkun.fusio>androidx.compose.compiler.plugins.kotlin`
automatically — applying the plugin is enough.
