# Step 9: `fusio-test` — Presenter testing framework (Design)

Status: **Phase 1 + 2 landed.** `fusio-test` module ships with `testPresenter` / `testSubPresenter` on every KMP target `fusio-runtime` covers. Phase 2 added `assertState { }` fail-fast, a `recordStateHistory = false` opt-out, and richer failure messages (observed-state trace + pending-effect list on every timeout). The sections below kept the original design intent; per-phase as-built notes sit at the bottom under "Implementation log".

## Problem

Fusio's architecture is inherently test-friendly:

- Sub-presenters return plain `State`, not `Presentation<S, E>` — they can be called like ordinary functions.
- `sealed` event/effect hierarchies + `@MapTo` / `@MapFrom` exhaustiveness give "all cases must be mapped" as a compile-time guarantee, which carries into tests (there are no silent drops).
- Event input and effect output are the only side-channels — the state surface is a single readable snapshot.

But in practice, testing a real Fusio presenter still requires:

- A headless Compose runtime (`Recomposer` + `BroadcastFrameClock` + `AbstractApplier`)
- A frame-driving loop (`Snapshot.sendApplyNotifications()` + `clock.sendFrame()` with appropriate yields)
- A state observer (`mutableStateOf<S?>` + `setContent { stateHolder.value = presenter(events).state }`)
- An effect collector (`LaunchedEffect { fusio.effectFlow.collect { … } }`)
- Clean teardown (dispose composition → close recomposer → cancel the runner job)

That's ~50 lines of boilerplate per test module before a single `fun assertCounterIncrements()` exists. A private version — `runHeadless { }` in `FusioHeadlessRunnerSourceProvider` — already ships the pattern in our box-test path, but it's embedded as a string literal inside a testData source provider and not exposed to users.

Making "testable by default" a marketing position for Fusio means lifting that helper into a first-class, KMP-compatible module so users get a Turbine-style DSL on day one.

## Goals

1. **One-liner runner.** Calling `testPresenter(::myPresenter) { … }` sets up and tears down the entire Compose pipeline.
2. **Ergonomic state + effect assertions.** `awaitState { it.count == 3 }` and `awaitEffect<Toast>()` instead of manual polling.
3. **KMP-wide.** Match `fusio-runtime`'s target list (jvm, iosArm64/SimArm64, macosArm64, js(IR), wasmJs, androidLibrary) so mobile, web, and native test targets all work.
4. **Virtual time by default.** Built on `kotlinx.coroutines.test.runTest`, so presenters that use `delay`/`withTimeout` don't wait in wall-clock.
5. **Sub-presenter isolation.** A dedicated helper for testing a `@Composable PresenterScope<E, Eff>.() -> S` function without building an outer `Presentation` by hand.

## Non-goals (v1)

- No property-based test generator for `@MapTo`/`@MapFrom` mappings. Integration tests of the parent presenter exercise those transitively; synthetic mapping tests would duplicate the IR transformer's own test coverage.
- No snapshot/golden-file testing for state history. Delegating that to standard snapshot libs is cleaner than inventing our own.
- No mocking facilities for `PresenterScope`. The real scope is cheap to construct and there's no interesting behaviour to fake.
- No non-Compose presenter testing. Fusio is explicitly Compose-based; tests that don't run through the composer would drift from reality.

## API proposal

### Entry points

Real presenters take more than just `Flow<E>` — a `TodoPresenter` will pull in a repository, a user id, a coroutine dispatcher, etc. Binding a function reference like `::todoPresenter` would lock the signature to `(Flow<E>) -> Presentation<S, Eff>` and force users to write synthetic shim wrappers. Instead, the framework supplies the `Flow<E>` (for top-level presenters) or the `PresenterScope<E, Eff>` receiver (for sub-presenters), and the caller provides a `@Composable` lambda that calls the real presenter with all of its own arguments.

```kotlin
// fusio-test / commonMain

/**
 * Runs [presenter] inside a headless Compose runtime driven by
 * [kotlinx.coroutines.test.runTest] (virtual time). [presenter] is a
 * `@Composable` lambda receiving the scenario's `Flow<E>` — bind the
 * rest of your real presenter's parameters inside it (fakes, ids, etc.).
 * [scenario] receives a [PresenterScenario] to drive events, observe
 * state, and await effects. Composition, recomposer, and coroutine job
 * are disposed before returning.
 */
public fun <E, S, Eff> testPresenter(
    context: CoroutineContext = EmptyCoroutineContext,
    presenter: @Composable () -> Presentation<S, Eff, E>,
    scenario: suspend PresenterScenario<E, S, Eff>.() -> Unit,
)

/**
 * Variant for sub-presenters that return [S] directly (the shape sub-
 * presenters carry before `fuse { }` wraps them). [subPresenter] is a
 * `@Composable` lambda whose receiver is a fresh `PresenterScope<E, Eff>`;
 * bind the rest of your real sub-presenter's parameters inside it.
 * Internally wraps the lambda in [buildPresenter] so the scenario API is
 * identical to [testPresenter].
 */
public fun <E, S, Eff> testSubPresenter(
    context: CoroutineContext = EmptyCoroutineContext,
    subPresenter: @Composable PresenterScope<E, Eff>.() -> S,
    scenario: suspend PresenterScenario<E, S, Eff>.() -> Unit,
)
```

Both entry points put `scenario` last so the scenario block is the trailing lambda at the call site. `presenter` / `subPresenter` must be passed by name because Kotlin only promotes the final lambda argument. `context` defaults to empty; pass a `TestDispatcher` if you need to share a scheduler with collaborating fakes.

### Scenario API

```kotlin
public interface PresenterScenario<E, S, Eff> {
    /** Most recently observed state. */
    public val state: S

    /** Every state observed since scenario start, oldest first. */
    public val stateHistory: List<S>

    /** Send an event and advance one frame. */
    public suspend fun send(event: E)

    /** Advance one frame without sending anything. */
    public suspend fun advance()

    /** Block until [predicate] matches, or fail at [timeout]. Returns the matching state. */
    public suspend fun awaitState(
        timeout: Duration = 1.seconds,
        predicate: (S) -> Boolean,
    ): S

    /** Pop the next effect from the queue, or fail at [timeout]. */
    public suspend fun awaitEffect(timeout: Duration = 1.seconds): Eff

    /** Pop the next effect narrowed to [T]; fails if the next effect isn't a [T] or none arrives. */
    public suspend fun <T : Eff> awaitEffect(
        type: KClass<T>,
        timeout: Duration = 1.seconds,
    ): T

    /** Fail if any effect arrives within [within]. */
    public suspend fun expectNoEffects(within: Duration = 50.milliseconds)

    /** Effects collected but not yet consumed by `awaitEffect`. */
    public val pendingEffects: List<Eff>
}

// `reified` helper on top of the KClass-taking overload
public suspend inline fun <E, S, Eff, reified T : Eff> PresenterScenario<E, S, Eff>.awaitEffect(
    timeout: Duration = 1.seconds,
): T = awaitEffect(T::class, timeout)
```

### Usage example

```kotlin
class TodoPresenterTest {
    @Test
    fun adds_a_task_and_emits_toast() = testPresenter(
        presenter = { events ->
            // Bind the real presenter's own dependencies here — the framework
            // only supplies `events`. Fakes, dispatchers, ids, flags all live
            // at the test author's fingertips.
            todoPresenter(
                events = events,
                repository = FakeTodoRepository(),
                userId = "u1",
            )
        },
    ) {
        awaitState { it.items.isEmpty() }

        send(TodoEvent.Add(text = "milk"))
        awaitState { it.items.size == 1 }

        val toast = awaitEffect<TodoEffect.Toast>()
        assertEquals("added", toast.message)
        expectNoEffects()
    }
}

class CounterSubPresenterTest {
    @Test
    fun counts_ticks() = testSubPresenter(
        // Receiver here is PresenterScope<CounterEvent, CounterEffect>.
        subPresenter = { counterSub(startAt = 0) },
    ) {
        assertEquals(0, state)         // S = Int directly, no Presentation wrapper
        send(CounterEvent.Tick)
        awaitState { it == 1 }
    }
}
```

## Module structure

```
fusio-test/
  build.gradle.kts                  # KMP: jvm, androidLibrary, iosArm64/SimArm64, macosArm64, js(IR), wasmJs
  src/commonMain/kotlin/com/kitakkun/fusio/test/
    TestPresenter.kt                # testPresenter / testSubPresenter builders
    PresenterScenario.kt            # interface + Impl
    internal/
      HeadlessApplier.kt            # AbstractApplier<Unit> stub
      FrameDriver.kt                # BroadcastFrameClock + sendFrame helpers
  src/commonTest/                   # self-tests of the framework itself
  api/                              # BCV dump
```

Dependencies:

- `api(project(":fusio-runtime"))` — `Presentation`, `PresenterScope`, `buildPresenter`
- `api(libs.compose.runtime.multiplatform)` — `Composable`, `Recomposer`, `AbstractApplier`, `BroadcastFrameClock`, `mutableStateOf`, `LaunchedEffect`, `Snapshot`
- `api(libs.kotlinx.coroutines.test)` — `runTest`, `TestScope` for virtual time
- `implementation(libs.kotlinx.coroutines.core)` — `MutableSharedFlow`, `Channel`

BCV is enabled; `apiValidation.ignoredProjects` stays unchanged (this module publishes).

## Design iterations

This section records three cycles of self-review the design went through — keeping the "why this and not that" reasoning visible so a future bring-up doesn't re-litigate the same trade-offs.

### Round 1 — initial sketch

Proposed a JVM-only `runPresenter { }` lifted verbatim from `FusioHeadlessRunnerSourceProvider`'s string literal, using `runBlocking` for the outer coroutine scope. Turbine-style `awaitItem()` on the effect stream.

### Round 2 — critique

- **`runBlocking` is JVM-only.** KMP tests on iOS/Wasm/JS can't call it. Every Compose-for-Kotlin test helper (the Compose team's own `runComposeUiTest`, Molecule's `moleculeFlow + test`) uses `runTest` underneath. Switch to that.
- **Wall-clock `delay(5)` inside `pump()` is flaky.** When the coroutine dispatcher is a `TestDispatcher`, the delay advances virtual time without actually yielding to the recomposer. Need a `yield()` after `sendFrame()`, not a `delay()`.
- **Turbine-style effect consumption is one-shot.** If the user only asserts on one specific effect and ignores the rest, later `expectNoEffects` fires. Effects need a `pendingEffects` readable snapshot alongside the `awaitEffect` pop semantics.
- **Naming `runPresenter` collides with `runBlocking`/`runTest`.** Readers scan test code by the outermost call — `testPresenter` reads as "this is a presenter test"; `runPresenter` reads as "run the presenter in some mode". Pick the former.
- **Sub-presenter DX is rough.** Since `fuse` returns `State` (not `Presentation`), users testing a sub-presenter directly have to hand-write a `buildPresenter { subPresenter(…) }` wrapper. That's wasted ceremony — add `testSubPresenter` as a sibling entry point.

### Round 3 — critique of round 2

- **Error surfacing.** If the presenter throws during composition, `Recomposer` logs the exception but doesn't re-throw from `runRecomposeAndApplyChanges`. The test would hang on the next `awaitState`. The scenario impl needs to install an exception handler on the `Recomposer` job and re-throw any captured crash at the next suspension point.
- **Close ordering matters.** Dispose composition → close recomposer → cancel the driver job. Closing in the wrong order (e.g. cancelling the job first) leaves effect collectors dangling and pending sends get dropped silently. Needs a `try/finally` with explicit order.
- **`awaitEffect<reified T>` is nice but reified types don't cross KMP boundary cleanly.** The interface method has to take `KClass<T>` to be usable from Java interop and to keep the `expect/actual` story simple; a top-level `inline reified` wrapper gives the ergonomic call site without constraining the interface.
- **Frame advance semantics.** `send(event)` should be "emit event → sendFrame → yield to recomposer → snapshot state". Each of those steps is one tick's worth of work — encode as a single helper method, don't make the user call three things.
- **Virtual time interacts awkwardly with `LaunchedEffect` containing `delay`.** If a user's presenter has `LaunchedEffect(key) { delay(100); emitEffect(X) }`, under `runTest` the delay resolves immediately via `advanceUntilIdle()` — but `advanceUntilIdle()` is something the scenario must call explicitly, not automatically on every `send`. Decision: `advance()` calls `runCurrent()` (process immediate work), `awaitEffect(timeout)` triggers `advanceTimeBy(timeout)` under the hood when the channel is empty. This keeps tests deterministic without making users reason about scheduler ticks.

Post-round-3 additions folded into the main proposal above:

1. `PresenterScenario.pendingEffects` as a readable snapshot (vs. the consumed `awaitEffect` stream).
2. Interface takes `KClass<T>`; `inline reified` lives as a top-level extension function.
3. `send` / `advance` encapsulate the send-frame-yield-snapshot cycle as one atomic operation.
4. `awaitEffect(timeout)` internally advances virtual time when the effect channel is empty, so `LaunchedEffect { delay(X); emit(Y) }` resolves within the scenario.
5. Explicit `try/finally` tear-down order documented in the impl.

### Round 4 — review-driven correction

- **Function references don't fit.** The original draft used `testPresenter(::myPresenter) { … }`. That assumes the presenter has exactly one parameter — `Flow<E>` — and returns `Presentation<S, Eff>`. Real presenters take a repository, a user id, a dispatcher, a feature flag, etc. — any of which would force a synthetic shim wrapper just to test. Switched both entry points to take a `@Composable` lambda that receives the scenario-supplied `Flow<E>` / `PresenterScope<E, Eff>` receiver, leaving every other argument to be bound by the caller inline. This is also how Compose's own UI testing binds the content under test (`setContent { MyComposable(param1, param2) }`), so it's the idiom users will already recognize.
- **Argument order.** With `presenter` and `scenario` both being lambdas, only one can be the trailing lambda; Kotlin picks the last-declared. Put `scenario` last so the scenario block stays visually dominant at the call site, and require `presenter` to be passed by name — this mirrors the `setContent { … }` feel while keeping the DSL-heavy `scenario { send; awaitState; … }` block last.

## Phasing

| Phase | Scope | Ship |
|---|---|---|
| **1 — minimal runner** | `testPresenter`, `testSubPresenter`, `PresenterScenario` with the seven methods above. JVM + Android targets first, then iOS/macOS, then JS/Wasm. BCV dump, Dokka docs, README sections on "How to test your presenter". | `fusio-test:0.2.0` |
| **2 — ergonomic polish** | Scenario DSL helpers (`assertState { … }` expectation builder, `recordStateHistory = false` opt-out), explicit `TestDispatcher` injection, failure-message improvements (diff rendering for state snapshots). | `fusio-test:0.3.0` |
| **3 — integrations** | Optional artifact `fusio-test-turbine` that bridges the effect stream to Turbine's `ReceiveTurbine<Eff>` for shops already using Turbine. Optional `fusio-test-kotest` for `withData` + scenario-per-row ergonomics. | `fusio-test-*:0.4.0` |

## Implementation log

### Phase 1 — landed

- Module `fusio-test` created on every fusio-runtime KMP target (jvm, androidLibrary, iosArm64/SimArm64, macosArm64, js(IR), wasmJs).
- `testPresenter` / `testSubPresenter` take a `@Composable` lambda (not a function reference) so real presenters with extra dependencies bind inline — see round 4 of the design iterations above.
- `runTest(UnconfinedTestDispatcher())` drives the scope; each `advance()` calls `delay(16ms)` on the test scheduler so virtual-time deadlines actually make progress (plain `yield()` spun the loop forever). That cycle was the "why is gradle stuck" finding during Phase 1 wiring — `awaitState` with a never-matching predicate wouldn't ever hit its timeout until `advance()` also moved the scheduler clock.
- `awaitEffect<T>()` uses `PresenterScenario<*, *, *>` star projections + `reified T : Any` so the one-arg narrowing shape works without colliding with the bare `awaitEffect(timeout)` interface method.

### Phase 2 — landed

- `PresenterScenario.assertState(message?, predicate)` — fail-fast variant of `awaitState`. Checks the current snapshot once, no suspension, and threads the caller-supplied `message` into the failure text for predicate-source-less builds.
- `testPresenter(recordStateHistory = true)` / `testSubPresenter(recordStateHistory = true)` — opt-out knob. Long-running scenarios (or memory-sensitive targets) can disable history accumulation; `state` is unaffected.
- Uniform failure-message builder: every `await*` / `assertState` / `expectNoEffects` failure now renders the observed-state trace (`[0] … [1] …`) plus any pending effects left in the queue. This traded a one-line "timeout" for a paragraph that usually pinpoints the presenter bug without re-running under a debugger.
- Dropped the originally-proposed `assertEffect<T>()` fail-fast variant — its non-pop semantics would have confused against `awaitEffect`'s pop semantics. Users who want fail-fast read `pendingEffects` directly.

### Phase 2.1 — event-error surfacing (landed 2026-04-24)

A follow-up that closed the "on<> handler crash kills the whole presenter" gap flagged during initial Phase 2 review. No design-doc round needed — the shape dropped out of the existing effect-stream pattern cleanly.

- Runtime: `PresenterScope.eventErrorFlow: Flow<Throwable>`, populated by a try/catch wrap inside `on<E>` (CancellationException rethrown, everything else funnelled). `Presentation` carries the same field. `forwardEventErrors(child, parent)` runtime helper bubbles child errors up; `FuseTransformer` emits a call next to every `fuse { }` unconditionally (no annotation gate — Throwable flows through unchanged).
- fusio-test: `awaitEventError()` / `awaitEventError<T>()` / `pendingEventErrors` / `expectNoEventErrors(within)` on `PresenterScenario`. `PresenterScenarioImpl` drains via a dedicated channel, which `testPresenter` populates with a `LaunchedEffect(presentation.eventErrorFlow) { collect { send } }` alongside the effect collector.
- Previously-queued memory entry `project_fusio_test_error_surfacing.md` flipped to RESOLVED with a breadcrumb for future symptom matching.

## Open questions (resolved)

All five were settled by the time Phase 2 landed. Notes kept so future revisits see the reasoning, not just the outcome.

1. **Module name** — settled as `fusio-test`. Symmetry with `fusio-runtime` / `fusio-annotations` won; `fusio-testing` suggested Android-specific intent that didn't match the KMP-wide scope.
2. **Default timeout** — settled as `1.seconds`. Under the virtual-time dispatcher the framework installs, timeouts cost microseconds of wall-clock regardless of the declared value, so picking a shorter default than Turbine's 3s didn't introduce flake in practice across Phase 1/2 self-tests.
3. **`TestResult` vs `Unit` return** — settled as `TestResult`, matching `runTest`'s shape. The `fun myTest() = testPresenter(...)` call-site ergonomics are the same as `fun myTest() = runTest { ... }`, and on JS/Wasm it's actually required for the `Promise`-backed run to complete.
4. **Effect assertions on type mismatch** — settled as strict fail-fast. `awaitEffect<Toast>()` throws immediately if the next effect is `Navigate` rather than skipping. Matches Turbine; the alternative (skip until match) would silently absorb "wrong effect emitted first" bugs.
5. **HeadlessApplier / frame-driver visibility** — settled by going with Kotlin `internal` rather than an `@InternalFusioApi` opt-in. No user has asked for custom-scenario extension points yet; promoting to public when one does is additive. The `FrameDriver` name wasn't needed in the final shape — frame-advance logic folded into `PresenterScenarioImpl.advance()`.
