# Step 9: `fusio-test` — Presenter testing framework (Design)

Status: **design doc, not yet implemented**

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

```kotlin
// fusio-test / commonMain

/**
 * Runs [presenter] inside a headless Compose runtime driven by
 * [kotlinx.coroutines.test.runTest] (virtual time). [block] receives a
 * [PresenterScenario] to drive events, observe state, and await effects.
 * Composition, recomposer, and coroutine job are disposed before returning.
 */
public fun <E, S, Eff> testPresenter(
    presenter: @Composable (Flow<E>) -> Presentation<S, Eff>,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend PresenterScenario<E, S, Eff>.() -> Unit,
)

/**
 * Variant for sub-presenters that return [S] directly (the shape sub-
 * presenters carry before `fuse { }` wraps them). Internally wraps the
 * sub-presenter in [buildPresenter] so the rest of the scenario API is
 * identical to [testPresenter].
 */
public fun <E, S, Eff> testSubPresenter(
    subPresenter: @Composable PresenterScope<E, Eff>.() -> S,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend PresenterScenario<E, S, Eff>.() -> Unit,
)
```

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
class CounterPresenterTest {
    @Test
    fun increment_emits_toast_on_reset() = testPresenter(::counterPresenter) {
        // Initial state lands via the first recomposition.
        awaitState { it.count == 0 }

        send(CounterEvent.Increment)
        assertEquals(1, state.count)

        send(CounterEvent.Reset)
        awaitEffect<CounterEffect.Toast> { assertEquals("reset", it.message) }
        assertEquals(0, state.count)
        expectNoEffects()
    }
}

class CounterSubPresenterTest {
    @Test
    fun sub_presenter_state_survives_recompose() = testSubPresenter(::counterSub) {
        assertEquals(0, state)         // S = Int directly, no Presentation wrapper
        send(Tick)
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

## Phasing

| Phase | Scope | Ship |
|---|---|---|
| **1 — minimal runner** | `testPresenter`, `testSubPresenter`, `PresenterScenario` with the seven methods above. JVM + Android targets first, then iOS/macOS, then JS/Wasm. BCV dump, Dokka docs, README sections on "How to test your presenter". | `fusio-test:0.2.0` |
| **2 — ergonomic polish** | Scenario DSL helpers (`assertState { … }` expectation builder, `recordStateHistory = false` opt-out), explicit `TestDispatcher` injection, failure-message improvements (diff rendering for state snapshots). | `fusio-test:0.3.0` |
| **3 — integrations** | Optional artifact `fusio-test-turbine` that bridges the effect stream to Turbine's `ReceiveTurbine<Eff>` for shops already using Turbine. Optional `fusio-test-kotest` for `withData` + scenario-per-row ergonomics. | `fusio-test-*:0.4.0` |

## Open questions

1. **Module name.** `fusio-test` (symmetry with `fusio-runtime`, `fusio-annotations`) vs `fusio-testing` (convention in some Android libs). Leaning `fusio-test`.
2. **Default timeout.** `1.seconds` for `awaitState` / `awaitEffect` matches Turbine's `3.seconds` pragma but shaves it — our virtual time means 1s is generous. Up for debate.
3. **Should `testPresenter` return `TestResult` or `Unit`?** `runTest` returns `TestResult` (opaque `suspend () -> Unit` wrapper on Wasm/JS); if we return it, the caller has to `return testPresenter { … }` in the `@Test` function. Matching `runTest`'s shape is the most honest option even if it reads oddly on JVM.
4. **Should effect assertions fail-fast on type mismatch?** `awaitEffect<Toast>()` when the next effect is `Navigate` — fail immediately, or skip and look for the next `Toast`? Current proposal: fail (strict). Turbine's precedent is also strict.
5. **Public or `@InternalFusioApi` on `HeadlessApplier`, `FrameDriver`?** They're useful for power users building custom scenarios, but exposing them commits us to their shape. Proposal: ship `@InternalFusioApi` in v1, promote to public in v2 once we see actual usage patterns.
