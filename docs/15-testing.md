# Testing Fusio presenters

Decomposition pays off twice. The first time is at the maintenance
level (`docs/14-getting-started.md`). The second is in tests.

Because sub-presenters return plain `State` (not
`Presentation<State, Event, Effect>`), they're callable as ordinary
`@Composable` functions — no wrapper, no synthetic scope, no mocks.
Because parent events are typed into each child via `@MapTo` and
effects lift out via `@MapFrom`, *only* the event input and effect
output are side channels — state is a single readable snapshot. And
because every mapping is exhaustiveness-checked at compile time, the
test cases you need to write track the sealed hierarchy automatically:
adding a new subtype breaks compilation until every `@MapTo` /
`@MapFrom` relationship is declared, which tells both you *and* your
test file what's missing.

## Quick start

`fusio-test` ships a headless Compose harness so none of this requires
boilerplate. Add the dependency:

```kotlin
dependencies {
    testImplementation("com.kitakkun.fusio:fusio-test:0.1.0")
}
```

The entire scenario fits in one call:

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

## `PresenterScenario` API

The scenario lambda's receiver exposes:

- **Reads.** `state` (latest snapshot), `stateHistory` (every distinct
  value observed, in order), `pendingEffects` and `pendingEventErrors`
  (queued but not yet awaited).
- **Drivers.** `send(event)` pushes an event and advances one frame;
  `advance()` ticks a frame without sending.
- **Awaits.** `awaitState { predicate }` suspends until the predicate
  matches; `awaitEffect()` / `awaitEffect<T>()` pops the next effect
  (strict on type mismatch); `awaitEventError()` /
  `awaitEventError<T>()` pops the next event-processing exception;
  `expectNoEffects()` / `expectNoEventErrors()` fail if anything
  arrives in the window.
- **Fail-fast.** `assertState(message?) { predicate }` checks the
  current state without waiting — useful for layering extra checks on
  a state that already landed.

Under the hood the harness runs in `kotlinx-coroutines-test`'s virtual
time, so `delay` / `withTimeout` inside a presenter resolve instantly.
The entire scenario is single-threaded, lifecycle-managed, and cleaned
up before the `@Test` function returns.

## Event errors stay observable

Exceptions thrown inside `on<Event>` handlers are caught by the
runtime and routed into `PresenterScope.eventErrorFlow`, surfaced at
the root `Presentation.eventErrorFlow`. The presenter stays alive —
the offending event is dropped, subsequent events continue flowing —
and a test can assert on the crash directly:

```kotlin
@Test
fun add_rejects_duplicate_title() = testPresenter(presenter = { todoPresenter() }) {
    send(TodoEvent.Add("milk"))
    send(TodoEvent.Add("milk")) // handler throws DuplicateTitleException

    val err = awaitEventError<DuplicateTitleException>()
    assertEquals("milk", err.title)
    // presenter's state still updated cleanly from the first Add
    assertState { it.items.size == 1 }
}
```

Children `fuse`d into a parent forward their event errors up
automatically — one root-level observer sees every event-processing
crash in the tree.

## Testing a sub-presenter in isolation

The same harness tests a child without constructing its parent. Use
`testSubPresenter` — it wraps your
`@Composable PresenterScope<Event, Effect>.() -> State` lambda in
`buildPresenter` so the rest of the API is identical:

```kotlin
@Test
fun filter_updates_on_select() = testSubPresenter<FilterEvent, FilterState, FilterEffect>(
    subPresenter = { filter() },   // receiver: PresenterScope<FilterEvent, FilterEffect>
) {
    send(FilterEvent.Select(TaskFilter.Active))
    awaitState { it.current == TaskFilter.Active }
}
```

This is where the decomposition pays dividends: the child lives in its
own file with its own sealed event/effect types and its own tests.
When you later integration-test the parent through `testPresenter`,
you're only asserting on the *fusion* — the event routing, effect
lifting, and cross-child state composition — because the individual
children are already covered.

## Failure messages

When an await times out or a predicate never matches, the assertion
text renders the full observed-state trace and any queued effects
alongside the header. A typical failure reads:

```
awaitState timed out after 1s.
  Latest state: TaskListState(tasks=[], loading=true)
  Observed states (oldest first):
    [0] TaskListState(tasks=[], loading=false)
    [1] TaskListState(tasks=[], loading=true)
  Pending effects: [Toast(message=loading)]
```

So "the test hung" is almost always actually "here's the exact state
the presenter landed on, and here's what it never produced".

## Using fusio-test alongside other tools

`fusio-test` doesn't replace your existing testing stack — it composes
with it.

**With [Kotest](https://kotest.io) matchers.** `PresenterScenario` is
a plain interface; Kotest matchers work on its fields without any
adapter:

```kotlin
testPresenter(presenter = { myScreenPresenter() }) {
    send(MyScreenEvent.AddTask("milk"))
    awaitState { it.totalCount == 1 }

    state.visibleTasks.shouldHaveSize(1)
    state.visibleTasks.first().title shouldBe "milk"
}
```

Kotest's `FunSpec` / `BehaviorSpec` / etc. wrap `testPresenter`
transparently — the returned `TestResult` works from a `test(…) { … }`
block the same way it does from a `@Test` method.

**With [Turbine](https://github.com/cashapp/turbine).** `awaitEffect` /
`awaitEffect<T>()` / `expectNoEffects` cover the presenter effect
stream without needing Turbine at all. If you already run Turbine
elsewhere in the same suite, there's no conflict — fusio-test runs on
`kotlinx-coroutines-test`'s virtual time just like Turbine's own
`TestScope` does.

**With real fakes.** The `presenter` lambda binds whatever parameters
your presenter needs — `FakeRepository()`, a hand-rolled
`TestDispatcher`-backed Clock, etc. fusio-test drives input via
`presentation.send`; everything else is ordinary dependency passing.

See `demo/src/jvmTest/` in the repository for a runnable showcase —
`TaskListPresenterTest`, `FilterPresenterTest`, and
`MyScreenPresenterTest` together prove that sub-presenter unit tests
plus one screen-level integration test are enough to cover a
non-trivial decomposed presenter tree.
