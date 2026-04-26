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

The library's core data type, `Presentation<State, Effect, Event>`, *is* that fusion: a state value, the effect stream the presenter produced alongside it, and a `send: (Event) -> Unit` entry point the UI uses to push input back in.

## The problem

Without decomposition, a screen-level presenter grows like this:

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

- `buildPresenter { … }` — screen-level entry, returns `Presentation<State, Effect, Event>` (UI calls `presentation.send(event)` to drive input)
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

Each sub-presenter is a `@Composable` bound to its own `PresenterScope`. Two
equivalent declaration styles are supported — pick whichever reads best:

```kotlin
// (a) As a free @Composable extension on PresenterScope<Event, Effect>:
@Composable
fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState { /* … */ }

// (b) As a `presenter<Event, Effect, State> { … }` factory value:
val filter = presenter<FilterEvent, FilterEffect, FilterState> {
    /* body; `on<>` and `emitEffect` resolve on the implicit PresenterScope receiver */
}
```

Both compile to the same shape — `fuse { taskList() }` / `fuse { filter() }` at
the parent works identically for either form. The factory style moves the three
type parameters out of the extension-receiver position onto a single call, which
some codebases find easier to scan; the free-extension style is the plainer
Kotlin idiom. See `docs/10-presenter-signature-ergonomics.md` for the design
trade-offs.

The screen-level presenter fuses them:

```kotlin
@Composable
fun myScreenPresenter(): Presentation<MyScreenUiState, MyScreenEffect, MyScreenEvent> =
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
`presentation.send(MyScreenEvent.AddTask("Buy milk"))` from `onClick` / similar
handlers — `buildPresenter` owns the underlying event channel internally, so
there's no `MutableSharedFlow` to remember at the call site.

At runtime, a `MyScreenEvent.AddTask("Buy milk")` sent through `presentation.send` is:

1. routed by the `@MapTo` into `TaskListEvent.Add("Buy milk")` on the child scope,
2. handled by `taskList()`'s `on<TaskListEvent.Add>`,
3. answered with a `TaskListEffect.Added("Buy milk")`,
4. lifted back up as `MyScreenEffect.ShowTaskAdded("Buy milk")` via the `@MapFrom`.

All of that plumbing is generated by the compiler plugin. No reflection.

See `demo/` for the runnable version — launch with `cd demo && ../gradlew runJvm`.

## Testing

Decomposition pays off twice. The first time is at the maintenance level
covered above. The second is in tests.

Because sub-presenters return plain `State` (not `Presentation<State, Effect, Event>`),
they're callable as ordinary `@Composable` functions — no wrapper, no synthetic
scope, no mocks. Because parent events are typed into each child via `@MapTo`
and effects lift out via `@MapFrom`, *only* the event input and effect output
are side channels — state is a single readable snapshot. And because every
mapping is exhaustiveness-checked at compile time, the test cases you need to
write track the sealed hierarchy automatically: adding a new subtype breaks
compilation until every `@MapTo`/`@MapFrom` relationship is declared, which
tells both you *and* your test file what's missing.

`fusio-test` ships a headless Compose harness so none of this requires
boilerplate. Add the dependency:

```kotlin
dependencies {
    testImplementation("com.kitakkun.fusio:fusio-test:0.1.0")
}
```

and the entire scenario fits in one call:

```kotlin
@Test
fun adds_a_task_and_shows_toast() = testPresenter(
    presenter = {
        // Bind every real-presenter parameter here — fakes, ids,
        // dispatchers, feature flags, etc. The harness drives input
        // through the returned presentation's `send`.
        myScreenPresenter()
    },
) {
    send(MyScreenEvent.AddTask("Buy milk"))
    awaitState { it.visibleTasks.any { task -> task.title == "Buy milk" } }

    val added = awaitEffect<MyScreenEffect.ShowTaskAdded>()
    assertEquals("Buy milk", added.title)
    expectNoEffects()
}
```

`PresenterScenario` (the receiver on the lambda) exposes:

- **Reads.** `state` (latest snapshot), `stateHistory` (every distinct value
  observed, in order), `pendingEffects` and `pendingHandlerErrors`
  (queued but not yet awaited).
- **Drivers.** `send(event)` pushes an event and advances one frame;
  `advance()` ticks a frame without sending.
- **Awaits.** `awaitState { predicate }` suspends until the predicate matches;
  `awaitEffect()` / `awaitEffect<T>()` pops the next effect (strict on type
  mismatch); `awaitHandlerError()` / `awaitHandlerError<T>()` pops the next
  `on<>`-handler exception; `expectNoEffects()` / `expectNoHandlerErrors()`
  fail if anything arrives in the window.
- **Fail-fast.** `assertState(message?) { predicate }` checks the current
  state without waiting — useful for layering extra checks on a state that
  already landed.

### Handler errors stay observable

Exceptions thrown inside `on<Event>` handlers are caught by the runtime
and routed into `PresenterScope.handlerErrors`, surfaced at the root
`Presentation.handlerErrors` flow. The presenter stays alive — the
offending event is dropped, subsequent events continue flowing — and a
test can assert on the crash directly:

```kotlin
@Test
fun add_rejects_duplicate_title() = testPresenter(presenter = { todoPresenter() }) {
    send(TodoEvent.Add("milk"))
    send(TodoEvent.Add("milk")) // handler throws DuplicateTitleException

    val err = awaitHandlerError<DuplicateTitleException>()
    assertEquals("milk", err.title)
    // presenter's state still updated cleanly from the first Add
    assertState { it.items.size == 1 }
}
```

Children `fuse`d into a parent forward their handler errors up
automatically — one root-level observer sees every handler crash in the
tree.

Under the hood it runs in `kotlinx-coroutines-test`'s virtual time, so
`delay` / `withTimeout` inside a presenter resolve instantly. The entire
scenario is single-threaded, lifecycle-managed, and cleaned up before the
`@Test` function returns.

### Testing a sub-presenter in isolation

The same harness tests a child without constructing its parent. Use
`testSubPresenter` — it wraps your `@Composable PresenterScope<E, Eff>.() -> S`
lambda in `buildPresenter` so the rest of the API is identical:

```kotlin
@Test
fun filter_updates_on_select() = testSubPresenter<FilterEvent, FilterState, FilterEffect>(
    subPresenter = { filter() },   // receiver: PresenterScope<FilterEvent, FilterEffect>
) {
    send(FilterEvent.Select(TaskFilter.Active))
    awaitState { it.current == TaskFilter.Active }
}
```

This is where the decomposition pays dividends: the child lives in its own
file with its own sealed event/effect types and its own tests. When you
later integration-test the parent through `testPresenter`, you're only
asserting on the *fusion* — the event routing, effect lifting, and
cross-child state composition — because the individual children are already
covered.

### Failure messages

When an await times out or a predicate never matches, the assertion text
renders the full observed-state trace and any queued effects alongside the
header. A typical failure reads:

```
awaitState timed out after 1s.
  Latest state: TaskListState(tasks=[], loading=true)
  Observed states (oldest first):
    [0] TaskListState(tasks=[], loading=false)
    [1] TaskListState(tasks=[], loading=true)
  Pending effects: [Toast(message=loading)]
```

so "the test hung" is almost always actually "here's the exact state the
presenter landed on, and here's what it never produced".

### Using fusio-test alongside other tools

`fusio-test` doesn't replace your existing testing stack — it composes with it.

**With [Kotest](https://kotest.io) matchers.** `PresenterScenario` is a
plain interface; Kotest matchers work on its fields without any adapter:

```kotlin
testPresenter(presenter = { myScreenPresenter() }) {
    send(MyScreenEvent.AddTask("milk"))
    awaitState { it.totalCount == 1 }

    state.visibleTasks.shouldHaveSize(1)
    state.visibleTasks.first().title shouldBe "milk"
}
```

Kotest's `FunSpec` / `BehaviorSpec` / etc. wrap `testPresenter` transparently
— the returned `TestResult` works from a `test(…) { … }` block the same way
it does from a `@Test` method.

**With [Turbine](https://github.com/cashapp/turbine).** `awaitEffect` /
`awaitEffect<T>()` / `expectNoEffects` cover the presenter effect stream
without needing Turbine at all. If you already run Turbine elsewhere in the
same suite, there's no conflict — fusio-test runs on
`kotlinx-coroutines-test`'s virtual time just like Turbine's own
`TestScope` does.

**With real fakes.** The `presenter` lambda binds whatever parameters your
presenter needs — `FakeRepository()`, a hand-rolled
`TestDispatcher`-backed Clock, etc. fusio-test drives input via
`presentation.send`; everything else is ordinary dependency passing.

See `demo/src/jvmTest/` for a runnable showcase — `TaskListPresenterTest`,
`FilterPresenterTest`, and `MyScreenPresenterTest` together prove that
sub-presenter unit tests + one screen-level integration test are enough
to cover a non-trivial decomposed presenter tree.

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

Two compat impls cover the 2.3 line — `:k230` compiled against 2.3.0 for the
2.3.0–2.3.19 range (uses the legacy `referenceClass` / `referenceFunctions`
APIs), and `:k2320` compiled against 2.3.20 for the 2.3.20+ range (uses the
`finderForBuiltins()` / `DeclarationFinder` API added in 2.3.20). A third
impl, `:k240_beta2`, covers 2.4.0-Beta2 by delegating to `:k2320` for every
method except `kclassArg`, whose signature lost its `session` parameter in
2.4. Picking up a new 2.3 or 2.4 patch requires no Fusio release.

## License

Fusio is licensed under the [Apache License, Version 2.0](LICENSE).

The design of the compiler-compat layer is inspired by
[ZacSweers/Metro](https://github.com/ZacSweers/metro) (also Apache 2.0).
See [NOTICE](NOTICE) for details.
