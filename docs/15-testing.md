# Testing Fusio presenters

Decomposition pays off twice — at the maintenance level
([docs/14](14-getting-started.md)), and in tests.

Sub-presenters return plain `State` (not `Presentation<…>`), so
they're callable as ordinary `@Composable` functions — no wrapper, no
synthetic scope, no mocks. Events flow in via `@MapTo`, effects out
via `@MapFrom`, so only event input and effect output are side
channels — state is a single readable snapshot. And because every
mapping is exhaustiveness-checked, adding a new sealed subtype breaks
compilation until both production code and tests acknowledge it.

## Quick start

```kotlin
dependencies {
    testImplementation("com.kitakkun.fusio:fusio-test:0.1.0")
}
```

```kotlin
@Test
fun adds_a_task_and_shows_toast() = testPresenter(
    presenter = {
        // Bind the real presenter's dependencies — fakes, ids, dispatchers, etc.
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

## `PresenterScenario` API

The scenario lambda's receiver exposes:

- **Reads.** `state` (latest snapshot), `stateHistory`,
  `pendingEffects`, `pendingEventErrors`.
- **Drivers.** `send(event)` pushes an event and advances one frame;
  `advance()` ticks a frame without sending.
- **Awaits.** `awaitState { predicate }`, `awaitEffect()` /
  `awaitEffect<T>()`, `awaitEventError()` / `awaitEventError<T>()`.
  `expectNoEffects()` / `expectNoEventErrors()` fail if anything
  arrives in the window.
- **Fail-fast.** `assertState(message?) { predicate }` checks current
  state without waiting.

The harness runs in `kotlinx-coroutines-test`'s virtual time, so
`delay` / `withTimeout` resolve instantly. The composition,
recomposer, and driver coroutine are torn down before the test
returns.

## Event errors stay observable

Exceptions thrown by `on<Event>` handlers are caught and routed to
`Presentation.eventErrorFlow`. The presenter stays alive (the
offending event is dropped) and the test can assert on the crash:

```kotlin
@Test
fun add_rejects_duplicate_title() = testPresenter(presenter = { todoPresenter() }) {
    send(TodoEvent.Add("milk"))
    send(TodoEvent.Add("milk")) // handler throws DuplicateTitleException

    val err = awaitEventError<DuplicateTitleException>()
    assertEquals("milk", err.title)
    assertState { it.items.size == 1 }   // first Add still landed
}
```

Children fused into a parent forward their event errors up
automatically — one root-level observer sees every crash in the tree.

## Testing a sub-presenter in isolation

`testSubPresenter` wraps a `@Composable PresenterScope<…>.() -> State`
lambda in `buildPresenter` for you:

```kotlin
@Test
fun filter_updates_on_select() = testSubPresenter<FilterEvent, FilterState, FilterEffect>(
    subPresenter = { filter() },
) {
    send(FilterEvent.Select(TaskFilter.Active))
    awaitState { it.current == TaskFilter.Active }
}
```

Each child lives with its own tests; the screen-level test then only
asserts the *fusion* — routing and effect lifting — because the
children are already covered.

## Failure messages

Timeouts include the observed-state trace and queued effects:

```
awaitState timed out after 1s.
  Latest state: TaskListState(tasks=[], loading=true)
  Observed states (oldest first):
    [0] TaskListState(tasks=[], loading=false)
    [1] TaskListState(tasks=[], loading=true)
  Pending effects: [Toast(message=loading)]
```

So "the test hung" is usually "here's exactly the state the presenter
landed on, and here's what it never produced".

## Using fusio-test alongside other tools

- **[Kotest](https://kotest.io) matchers** work on `PresenterScenario`
  fields with no adapter (`state.visibleTasks.shouldHaveSize(1)`).
  Kotest's `FunSpec` / `BehaviorSpec` etc. wrap `testPresenter`
  transparently — the returned `TestResult` is what they expect.
- **[Turbine](https://github.com/cashapp/turbine)** isn't needed for
  effects (`awaitEffect` covers it), but coexists fine — both run on
  `kotlinx-coroutines-test`'s virtual time.
- **Real fakes.** The `presenter` lambda binds whatever your
  presenter needs (`FakeRepository()`, a `TestDispatcher`-backed
  Clock, etc.); `presentation.send` drives input.

See `demo/src/jvmTest/` for a runnable showcase that proves
sub-presenter unit tests plus one screen-level integration test
suffice to cover a non-trivial decomposed presenter tree.
