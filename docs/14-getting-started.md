# Getting started with Fusio

This is the long-form walkthrough that the README links to. It motivates
the architecture, shows what you write, and steps through a complete
Todo screen end-to-end.

## The problem Fusio solves

Without decomposition, a screen-level presenter accretes every
feature into one body:

```kotlin
@Composable
fun myScreenPresenter(): MyScreenUiState {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    var filter by remember { mutableStateOf(TaskFilter.All) }
    var nextId by remember { mutableStateOf(1L) }
    // …more state every time the screen learns a new feature…

    LaunchedEffect(Unit) { /* AddTask handler */ }
    LaunchedEffect(Unit) { /* ToggleTask handler */ }
    LaunchedEffect(Unit) { /* RemoveTask handler */ }
    LaunchedEffect(Unit) { /* SelectFilter handler */ }
    // …each new feature adds another branch…

    val visible = when (filter) { … }
    return MyScreenUiState(visible, filter, …)
}
```

The natural fix is to split it. But the *manual* split forces I/O
boilerplate at **every seam, in both directions**: events have to flow
down from the parent into the right child, and effects have to flow
back up from each child into the parent.

```kotlin
@Composable
fun myScreenPresenter(): MyScreenUiState {
    // Down direction: the parent has to manufacture an event channel
    // per child, then route every parent UI event into the right one.
    val taskListEvents = remember {
        MutableSharedFlow<TaskListEvent>(extraBufferCapacity = 64)
    }
    val filterEvents = remember {
        MutableSharedFlow<FilterEvent>(extraBufferCapacity = 64)
    }
    fun dispatch(event: MyScreenEvent) = when (event) {
        is MyScreenEvent.AddTask      -> taskListEvents.tryEmit(TaskListEvent.Add(event.title))
        is MyScreenEvent.ToggleTask   -> taskListEvents.tryEmit(TaskListEvent.Toggle(event.id))
        is MyScreenEvent.RemoveTask   -> taskListEvents.tryEmit(TaskListEvent.Remove(event.id))
        is MyScreenEvent.SelectFilter -> filterEvents.tryEmit(FilterEvent.Select(event.filter))
    }

    // Up direction (one of several manual options — here, callbacks):
    // each child takes a callback per effect subtype the parent cares
    // about, and the children have to invoke them at the right moments.
    // Alternatives (Flow-return, sealed inheritance, composition wrap)
    // are covered in the "Why not roll your own routing?" subsection
    // below — they share the same costs.
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

    // Both seams scale with every new feature: a new parent event subtype
    // means a new branch in `dispatch`; a new child effect subtype means
    // a new callback parameter on each child's signature.
    return MyScreenUiState(tasks, filter, lastSnack)
}
```

That's the I/O boilerplate Fusio resolves. You declare the routing
**once** on the parent's sealed event / effect types — `@MapTo` for the
down direction, `@MapFrom` for the up — and the compiler synthesises
the per-seam plumbing. The children stay parameterless extensions on
`PresenterScope<…>` (no `events` argument, no per-effect callbacks),
and the parent stays a composition step (no event channels to
manufacture, no `dispatch` switch to maintain).

### Why not roll your own routing?

Two non-Fusio shortcuts dodge the `dispatch` switch above. Both have
costs `@MapTo` doesn't.

**Option A — child event extends the parent (inheritance).**

```kotlin
sealed interface MyScreenEvent
sealed interface TaskListEvent : MyScreenEvent {
    data class Add(val title: String) : TaskListEvent
}
sealed interface FilterEvent : MyScreenEvent {
    data class Select(val filter: TaskFilter) : FilterEvent
}

@Composable
fun myScreenPresenter(events: Flow<MyScreenEvent>): MyScreenUiState {
    val tasks  = taskListPresenter(events = events.filterIsInstance<TaskListEvent>())
    val filter = filterPresenter(events = events.filterIsInstance<FilterEvent>())
    // …
}
```

**Option B — parent wraps child events as its own subtypes (composition).**

```kotlin
sealed interface MyScreenEvent {
    data class TaskList(val event: TaskListEvent) : MyScreenEvent
    data class Filter(val event: FilterEvent)     : MyScreenEvent
}

@Composable
fun myScreenPresenter(events: Flow<MyScreenEvent>): MyScreenUiState {
    val tasks  = taskListPresenter(
        events = events.filterIsInstance<MyScreenEvent.TaskList>().map { it.event },
    )
    val filter = filterPresenter(
        events = events.filterIsInstance<MyScreenEvent.Filter>().map { it.event },
    )
    // …
}
```

Both shorten the down direction. But:

- **Both leak the sub-presenter identity to the UI.** Inheritance has
  the UI send `TaskListEvent.Add(title)` directly. Composition has
  it send `MyScreenEvent.TaskList(TaskListEvent.Add(title))` —
  slightly better because the parent type wraps it, but the UI still
  names the child. Refactor TaskList → SmartList and every UI call
  site changes. Fusio's `@MapTo` lets the UI send domain names
  (`MyScreenEvent.AddTask(title)`) and hides the implementation
  routing.
- **Inheritance couples the child to the parent.** `TaskListEvent :
  MyScreenEvent` makes `TaskListEvent` un-reusable in any other
  screen. Composition is decoupled here — the wrapper lives on the
  parent — but the UI-leak above stays.
- **Neither lets you rename across the seam.** When the parent's
  domain name (`AddTask(title)`) differs from the child's
  implementation name (`Add(title)`), inheritance forces them to be
  the same class and composition forces the UI to know both names.
  Fusio's per-property mapper renames at compile time, with a FIR
  checker flagging field-shape mismatches.
- **The effect (up) direction has the same options with the same
  costs.** Both tricks apply symmetrically: with inheritance you can
  declare `TaskListEffect : MyScreenEffect` and have each child
  return its own `Flow<ChildEffect>` for the parent to `merge`; with
  composition you wrap each child's effects in a parent subtype
  (`MyScreenEffect.TaskList(val effect: TaskListEffect)`) and `map`
  before merging. Same UI-leak problem, same rename problem, same
  exhaustiveness gap. Fusio's `@MapFrom` mirrors `@MapTo` and dodges
  all three.
- **No exhaustiveness check that every parent subtype actually
  reaches a child.** Sealed inheritance / sealed wrapping gives you
  "this event class belongs to this hierarchy" but doesn't enforce
  that `MyScreenEvent.AddTask` is actually handled by *some* child.
  Fusio's `MISSING_EVENT_HANDLER` checker reports uncovered subtypes
  at compile time.

## The fusion point

```kotlin
val tasks  = fuse { taskList() }   // ← child presenter 1
val filter = fuse { filter() }     // ← child presenter 2
```

Each `fuse { subPresenter() }` is where a sub-presenter's scope fuses
into the parent's. The Fusio compiler plugin rewrites each call site at
IR time into:

1. a `mapEvents { when(parentEvent) { … } }` pipeline driven by the
   parent's `@MapTo` annotations — only the events this child should
   see flow in,
2. a fresh child `PresenterScope<ChildEvent, ChildEffect>`,
3. a `forwardEffects { when(childEffect) { … } }` pipeline driven by
   the parent's `@MapFrom` annotations — child effects bubble up as
   parent effects,
4. an invocation of the sub-presenter lambda, whose return value is the
   child's `State`.

Sibling `fuse` calls don't see each other's events or effects: each
gets a narrow, typed slice of the parent's flows.

## What you write

- `buildPresenter { … }` — screen-level entry, returns
  `Presentation<State, Event, Effect>` (UI calls
  `presentation.send(event)` to drive input)
- `on<Event> { … }` — typed handler reading from the current scope's
  event flow
- `fuse { subPresenter() }` — the fusion point above
- `@MapTo(ChildEvent::class)` on a *parent-event* sealed subtype —
  "route me into this child"
- `@MapFrom(ChildEffect::class)` on a *parent-effect* sealed subtype —
  "lift this child effect up as me"

FIR checkers enforce the interesting invariants:

- Properties on the mapped subtypes must line up by name and type.
- Every child sealed subtype must be covered by a parent `@MapFrom`
  (effect exhaustiveness).
- Every parent-Event sealed subtype must be reached by some `on<E>`
  handler in the presenter body, or routed via `@MapTo` to a fused
  child. Configurable severity via the
  `fusio { eventHandlerExhaustiveSeverity = … }` DSL or the
  `-Pfusio.event-handler-exhaustive-severity=error|warning|none`
  Gradle property (default `WARNING`). See
  [docs/12-event-handler-exhaustiveness.md](12-event-handler-exhaustiveness.md).

## Example: a Todo screen with two sibling sub-presenters

Sub-presenter types (each child is its own self-contained module — no
parent knowledge):

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

sealed interface FilterEvent {
    data class Select(val filter: TaskFilter) : FilterEvent
}
sealed interface FilterEffect {
    data class Changed(val newFilter: TaskFilter) : FilterEffect
}
```

Parent (screen-level) types — annotations declare the fusion:

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
`PresenterScope<Event, Effect>` returning the child `State`:

```kotlin
@Composable
fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState { /* … */ }

@Composable
fun PresenterScope<FilterEvent, FilterEffect>.filter(): FilterState { /* … */ }
```

The receiver carries the typed event flow (read via `on<E> { … }`) and
the effect channel (written via `emitEffect`). The function body
returns the sub-presenter's state, which the parent reads through
`fuse { … }`.

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

The UI side keeps a single `presentation` reference and calls
`presentation.send(MyScreenEvent.AddTask("Buy milk"))` from `onClick`
or similar handlers — `buildPresenter` owns the underlying event
channel internally, so there's no `MutableSharedFlow` to remember at
the call site.

At runtime, a `MyScreenEvent.AddTask("Buy milk")` sent through
`presentation.send` is:

1. routed by the `@MapTo` into `TaskListEvent.Add("Buy milk")` on the
   child scope,
2. handled by `taskList()`'s `on<TaskListEvent.Add>`,
3. answered with a `TaskListEffect.Added("Buy milk")`,
4. lifted back up as `MyScreenEffect.ShowTaskAdded("Buy milk")` via the
   `@MapFrom`.

All of that plumbing is generated by the compiler plugin. No reflection.

See `demo/` in the repository for the runnable version — launch with
`cd demo && ../gradlew runJvm`.

## Project layout

```
fusio-annotations/      @MapTo, @MapFrom                    (Kotlin Multiplatform)
fusio-runtime/          Presentation, PresenterScope, buildPresenter, on, fuse stub
                                                             (Kotlin Multiplatform + Compose Multiplatform)
fusio-test/             testPresenter / testSubPresenter harness + PresenterScenario
                                                             (Kotlin Multiplatform, same targets as runtime)
fusio-compiler-plugin/  FIR checkers + IR transformer        (JVM, single shaded jar)
fusio-gradle-plugin/    KotlinCompilerPluginSupportPlugin integration (included build)
demo/                   Compose Desktop Todo app using Fusio end-to-end
```

## Build commands

```
./gradlew build                       # compile + test every target, every module
./gradlew :fusio-runtime:allTests     # runtime tests on every platform
./gradlew :fusio-runtime:jvmTest      # JVM only (fastest feedback)

# Launch the Todo demo (Compose Desktop window)
cd demo
../gradlew runJvm
```

Builds run with Gradle 9 configuration cache enabled; incremental
rebuilds complete in under a second after the first run.

### Plugin ordering

Fusio's IR transformer must run **before** the Compose compiler plugin,
because Compose injects `$composer` / `$changed` parameters into
`@Composable` lambdas that Fusio needs to rewrite first. The Fusio
Gradle plugin sets this automatically by injecting
`-Xcompiler-plugin-order=com.kitakkun.fusio>androidx.compose.compiler.plugins.kotlin`
into every Kotlin compilation, so applying the plugin is enough — no
extra configuration required.
