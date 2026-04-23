# Fusio

> ⚠️ Experimental — a research project exploring Composable presenter decomposition, building on DroidKaigi 2024 / 2025-style Compose architectures.

**Fusio** — *Latin, "to pour together, to fuse"* — is a Kotlin compiler plugin for Compose presenters that stay small as a screen grows.

A single `@Composable` presenter for a whole screen accumulates state,
`LaunchedEffect`s, and event branches until nobody wants to touch it. Splitting
it into sub-presenters normally means hand-plumbing every parent event down to
the right child and every child effect back up. Fusio does that plumbing for
you, at compile time, from a pair of annotations.

The name isn't decorative. Every screen Fusio produces is literally a **fusion** of:

- each sub-presenter's private state trees into one screen-level `UiState`
- the children's effect flows back into the parent's single effect channel
- the parent's event flow routed into each child's scope via declared mappings

The library's core data type, `Presentation<State, Effect>`, *is* that fusion expressed as a pair — a state value and the effect stream the presenter produced alongside it.

## The problem

Without decomposition, a screen-level presenter grows like this:

```kotlin
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): MyScreenUiState {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    var filter by remember { mutableStateOf(TaskFilter.All) }
    var nextId by remember { mutableStateOf(1L) }
    // …more state every time the screen learns a new feature…

    LaunchedEffect(Unit) { /* AddTask handler */ }
    LaunchedEffect(Unit) { /* ToggleTask handler */ }
    LaunchedEffect(Unit) { /* RemoveTask handler */ }
    LaunchedEffect(Unit) { /* SelectFilter handler */ }
    // …and so on; each new feature adds another branch…

    val visible = when (filter) { … }
    return MyScreenUiState(visible, filter, …)
}
```

Split the feature-rules into sub-presenters and the parent becomes a composition
step instead of a junk drawer — *if* you're willing to write the event /
effect plumbing between them by hand. Fusio turns that plumbing into annotations the compiler reads.

## The fusion point

```kotlin
val tasks  = fuse { taskList() }   // <── child presenter 1
val filter = fuse { filter() }     // <── child presenter 2
```

Each `fuse { subPresenter() }` is where a sub-presenter's scope fuses
into the parent's. The Fusio compiler plugin rewrites each call site at IR
time into:

1. a `mapEvents { when(parentEvent) { … } }` pipeline driven by the parent's
   `@MapTo` annotations — only the events this child should see flow in,
2. a fresh child `PresenterScope<ChildEvent, ChildEffect>`,
3. a `forwardEffects { when(childEffect) { … } }` pipeline driven by the parent's
   `@MapFrom` annotations — child effects bubble up as parent effects,
4. an invocation of the sub-presenter lambda, whose return value is the
   child's `State`.

Sibling `fuse` calls don't see each other's events or effects: each gets
a narrow, typed slice of the parent's flows.

## What you write

- `buildPresenter(eventFlow) { … }` — screen-level entry, returns `Presentation<State, Effect>`
- `on<Event> { … }` — typed handler reading from the current scope's event flow
- `fuse { subPresenter() }` — the fusion point above
- `@MapTo(ChildEvent::class)` on a *parent-event* sealed subtype — "route me into this child"
- `@MapFrom(ChildEffect::class)` on a *parent-effect* sealed subtype — "lift this child effect up as me"

FIR checkers enforce the interesting invariants:

- properties on the mapped subtypes must line up by name and type
- every child sealed subtype must be covered by a parent `@MapFrom` (exhaustiveness)

## Example: a Todo screen with two sibling sub-presenters

Sub-presenter types (each child is its own self-contained module — no parent knowledge):

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

Each sub-presenter is a plain Composable function on its own `PresenterScope`:

```kotlin
@Composable
fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState { /* … */ }

@Composable
fun PresenterScope<FilterEvent, FilterEffect>.filter(): FilterState { /* … */ }
```

The screen-level presenter fuses them:

```kotlin
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): Presentation<MyScreenUiState, MyScreenEffect> =
    buildPresenter(eventFlow) {
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

At runtime, a `MyScreenEvent.AddTask("Buy milk")` emitted on the parent flow is:

1. routed by the `@MapTo` into `TaskListEvent.Add("Buy milk")` on the child scope,
2. handled by `taskList()`'s `on<TaskListEvent.Add>`,
3. answered with a `TaskListEffect.Added("Buy milk")`,
4. lifted back up as `MyScreenEffect.ShowTaskAdded("Buy milk")` via the `@MapFrom`.

All of that plumbing is generated by the compiler plugin. No reflection.

See `demo/` for the runnable version — launch with `cd demo && ../gradlew runJvm`.

## Project layout

```
fusio-annotations/      @MapTo, @MapFrom                    (Kotlin Multiplatform)
fusio-runtime/          Presentation, PresenterScope, buildPresenter, on, fuse stub
                                                             (Kotlin Multiplatform + Compose Multiplatform)
fusio-compiler-plugin/  FIR checkers + IR transformer        (JVM, single shaded jar)
fusio-gradle-plugin/    KotlinCompilerPluginSupportPlugin integration (included build)
demo/                   Compose Desktop Todo app using Fusio end-to-end
```

### Platform targets

`fusio-annotations` and `fusio-runtime` publish to:

| Target | Status |
|---|---|
| JVM | ✅ |
| Android (`com.android.kotlin.multiplatform.library`) | ✅ |
| iOS (`iosArm64`, `iosSimulatorArm64`) | ✅ |
| macOS (`macosArm64`) | ✅ |
| JS (`js(IR)` — browser & node) | ✅ |
| Wasm (`wasmJs` — browser & node) | ✅ |

`commonTest` runs on all of the above. watchOS, tvOS, Linux, and Windows
aren't configured yet but pose no fundamental obstacle — see
`fusio-runtime/build.gradle.kts` to add more.

## Build

```
./gradlew build                       # compile + test every target, every module
./gradlew :fusio-runtime:allTests     # runtime tests on every platform
./gradlew :fusio-runtime:jvmTest      # JVM only (fastest feedback)

# Launch the Todo demo (Compose Desktop window)
cd demo
../gradlew runJvm
```

Builds run with Gradle 9 configuration cache enabled; incremental rebuilds
complete in under a second after the first run.

### Plugin ordering

Fusio's IR transformer must run **before** the Compose compiler plugin,
because Compose injects `$composer` / `$changed` parameters into `@Composable`
lambdas that Fusio needs to rewrite first. The Fusio Gradle plugin sets this
automatically by injecting
`-Xcompiler-plugin-order=com.kitakkun.fusio>androidx.compose.compiler.plugins.kotlin`
into every Kotlin compilation, so applying the plugin is enough — no extra
configuration required.

### Kotlin version compatibility

| Your Kotlin | Supported |
|-------------|-----------|
| 2.3.0 – 2.3.x | ✅ (compat impl compiled against 2.3.0, forward-compatible to every patch) |
| 2.4.0-Beta2+ | ✅ |

A single `fusio-compiler-plugin` jar ships with a per-Kotlin-version
compatibility layer inside it (via a shaded `fusio-compiler-compat` + `kXXX`
submodule pattern inspired by [ZacSweers/Metro](https://github.com/ZacSweers/metro)).
At compile time the plugin inspects the running Kotlin compiler and
`ServiceLoader`-resolves the matching impl, so there's nothing to configure
per Kotlin version.

The 2.3.x compat impl is deliberately compiled against
`kotlin-compiler-embeddable:2.3.0` — the oldest version it declares support
for — so the shaded bytecode only references API members present in every
2.3.x release. If you upgrade to a newer 2.3 patch the same jar keeps
working; no Fusio release is required.

## License

Fusio is licensed under the [Apache License, Version 2.0](LICENSE).

The design of the compiler-compat layer is inspired by
[ZacSweers/Metro](https://github.com/ZacSweers/metro) (also Apache 2.0).
See [NOTICE](NOTICE) for details.
