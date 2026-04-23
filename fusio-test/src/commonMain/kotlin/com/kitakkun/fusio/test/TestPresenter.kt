package com.kitakkun.fusio.test

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.test.internal.ErrorRef
import com.kitakkun.fusio.test.internal.HeadlessApplier
import com.kitakkun.fusio.test.internal.PresenterScenarioImpl
import com.kitakkun.fusio.test.internal.StateHolder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs [presenter] inside a headless Compose runtime, then invokes [scenario]
 * against a [PresenterScenario] that can drive events and assert on state /
 * effects.
 *
 * ## What the framework supplies, what you supply
 *
 * The framework passes a synthetic `Flow<Event>` into [presenter]. Every
 * other parameter of your real presenter (repositories, user ids,
 * dispatchers, feature flags, …) is yours to bind inside the lambda — the
 * same pattern Compose UI tests use with `setContent { MyScreen(dep1, dep2) }`.
 *
 * ```kotlin
 * @Test
 * fun adds_a_task() = testPresenter(
 *     presenter = { events ->
 *         todoPresenter(events = events, repo = FakeTodoRepo(), userId = "u1")
 *     },
 * ) {
 *     send(TodoEvent.Add("milk"))
 *     awaitState { it.items.size == 1 }
 *     awaitEffect<TodoEffect.Toast> { assertEquals("added", it.message) }
 *     expectNoEffects()
 * }
 * ```
 *
 * ## Timing model
 *
 * Driven by [kotlinx.coroutines.test.runTest] + [UnconfinedTestDispatcher],
 * so virtual time replaces wall-clock everywhere. `delay` / `withTimeout`
 * inside the presenter resolve instantly when the scenario advances its
 * frame clock, and `awaitState` / `awaitEffect` timeouts count virtual
 * milliseconds — tests run in microseconds regardless of how big the
 * declared timeouts are.
 *
 * Pass a [TestDispatcher][kotlinx.coroutines.test.TestDispatcher] via
 * [context] if a fake collaborator needs to share the scheduler — e.g. to
 * coordinate a fake `CoroutineDispatcher` injected into a repository.
 *
 * ## Lifecycle
 *
 * The returned [TestResult] represents the test run; JVM / Android return
 * `Unit`, JS / Wasm return a `Promise`. Assign it from your `@Test` function
 * exactly the way you would assign `runTest { … }`.
 *
 * ```
 * @Test fun myTest() = testPresenter(presenter = { … }) { … }
 * ```
 *
 * The composition, recomposer, and driver coroutine are disposed in that
 * order before this function returns — presenters that register long-lived
 * `LaunchedEffect`s don't leak across tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun <Event, State, Effect> testPresenter(
    context: CoroutineContext = EmptyCoroutineContext,
    presenter: @Composable (Flow<Event>) -> Presentation<State, Effect>,
    scenario: suspend PresenterScenario<Event, State, Effect>.() -> Unit,
): TestResult = runTest(context = UnconfinedTestDispatcher() + context) {
    val events = MutableSharedFlow<Event>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    val clock = BroadcastFrameClock()
    val effectChannel = Channel<Effect>(Channel.UNLIMITED)
    val stateHolder = StateHolder<State>()
    val history = mutableListOf<State>()
    val errorRef = ErrorRef()

    // Recomposer runs in its own coroutine so the scenario body can
    // interleave with it. The exception handler routes presenter crashes
    // into [errorRef] instead of letting them tear down the TestScope.
    val recomposer = Recomposer(coroutineContext + clock)
    val recomposerJob = launch(
        clock + CoroutineExceptionHandler { _, t -> errorRef.value = t },
    ) {
        try {
            recomposer.runRecomposeAndApplyChanges()
        } catch (t: Throwable) {
            errorRef.value = t
        }
    }

    val composition = Composition(HeadlessApplier(), recomposer)
    composition.setContent {
        val presentation = presenter(events)
        val nextState = presentation.state
        if (stateHolder.current != nextState) {
            stateHolder.current = nextState
            history += nextState
        }
        LaunchedEffect(presentation.effectFlow) {
            presentation.effectFlow.collect { effectChannel.send(it) }
        }
    }

    val impl = PresenterScenarioImpl(
        events = events,
        stateHolder = stateHolder,
        stateHistory = history,
        effectChannel = effectChannel,
        clock = clock,
        scheduler = testScheduler,
        recompositionError = errorRef,
    )

    try {
        // Kick the first frame so `setContent { … }`'s composition actually
        // runs and stateHolder has a value by the time the scenario body
        // reads it.
        impl.advance()
        impl.scenario()
    } finally {
        // Ordered teardown: let the composition's `onDispose` hooks fire
        // first, then close the recomposer (which ends its loop and
        // completes [recomposerJob] naturally), and finally cancel the job
        // as a belt-and-braces guard in case `runRecomposeAndApplyChanges`
        // didn't actually exit.
        composition.dispose()
        recomposer.close()
        recomposerJob.cancel()
    }
}

/**
 * Sibling of [testPresenter] for sub-presenters — functions shaped like
 * `@Composable PresenterScope<Event, Effect>.() -> State` that return bare
 * [State] instead of a [Presentation] (the form sub-presenters take before
 * `fuse { }` wraps them).
 *
 * Internally delegates to [testPresenter] by wrapping [subPresenter] in
 * [buildPresenter], so the scenario API is identical. Useful when you want
 * to unit-test a sub-presenter in isolation without constructing the parent
 * and its `@MapTo` / `@MapFrom` plumbing.
 *
 * ```kotlin
 * @Test
 * fun counts_ticks() = testSubPresenter(
 *     subPresenter = { counterSub(startAt = 0) },  // receiver: PresenterScope<E, Eff>
 * ) {
 *     send(CounterEvent.Tick)
 *     awaitState { it == 1 }
 * }
 * ```
 */
public fun <Event, State, Effect> testSubPresenter(
    context: CoroutineContext = EmptyCoroutineContext,
    subPresenter: @Composable PresenterScope<Event, Effect>.() -> State,
    scenario: suspend PresenterScenario<Event, State, Effect>.() -> Unit,
): TestResult = testPresenter(
    context = context,
    presenter = { events -> buildPresenter(events, subPresenter) },
    scenario = scenario,
)

private const val EVENT_BUFFER_CAPACITY: Int = 64
