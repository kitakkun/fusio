package com.kitakkun.fusio.test

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs [presenter] inside a headless Compose runtime and exposes a
 * [PresenterScenario] for driving events and asserting on the resulting
 * state and effects.
 *
 * Use it from a `@Test` function exactly the way you'd use
 * `runTest { … }` — the returned [TestResult] is whatever
 * `kotlinx-coroutines-test` returns on the current platform (`Unit` on
 * JVM / Android, `Promise` on JS / Wasm).
 *
 * ```kotlin
 * @Test
 * fun adds_a_task() = testPresenter(
 *     presenter = {
 *         // Bind every real-presenter argument here — fakes, ids,
 *         // dispatchers, feature flags, etc. Same shape as Compose's
 *         // setContent { MyScreen(dep1, dep2) } pattern.
 *         todoPresenter(repo = FakeTodoRepo(), userId = "u1")
 *     },
 * ) {
 *     send(TodoEvent.Add("milk"))
 *     awaitState { it.items.size == 1 }
 *     val toast = awaitEffect<TodoEffect.Toast>()
 *     assertEquals("added", toast.message)
 *     expectNoEffects()
 * }
 * ```
 *
 * Events are pushed via the scenario's [PresenterScenario.send], which
 * forwards into the presenter's internal event channel — no event-flow
 * argument to plumb in from the test side.
 *
 * ## Timing
 *
 * Runs under `kotlinx-coroutines-test`'s virtual time:
 * `delay` / `withTimeout` inside the presenter resolve instantly,
 * `awaitState` / `awaitEffect` timeouts count virtual milliseconds, and
 * the whole test completes in microseconds regardless of the declared
 * timeouts. Pass a `TestDispatcher` through [context] when a fake
 * collaborator needs to share the test scheduler.
 *
 * ## Recording state history
 *
 * By default every distinct state value the presenter produces is
 * captured into [PresenterScenario.stateHistory]. Pass
 * `recordStateHistory = false` for long-running tests where the history
 * list would grow large; `state` still reflects the latest value either
 * way.
 *
 * ## Cleanup
 *
 * The composition, recomposer, and driver coroutine are torn down before
 * this function returns — presenters that register long-lived
 * `LaunchedEffect`s don't leak across tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun <Event, State, Effect> testPresenter(
    context: CoroutineContext = EmptyCoroutineContext,
    recordStateHistory: Boolean = true,
    presenter: @Composable () -> Presentation<State, Event, Effect>,
    scenario: suspend PresenterScenario<Event, State, Effect>.() -> Unit,
): TestResult = runTest(context = UnconfinedTestDispatcher() + context) {
    val clock = BroadcastFrameClock()
    val effectChannel = Channel<Effect>(Channel.UNLIMITED)
    val eventErrorChannel = Channel<Throwable>(Channel.UNLIMITED)
    val stateHolder = StateHolder<State>()
    val history: MutableList<State> = mutableListOf()
    val errorRef = ErrorRef()

    // The presenter creates its own internal event flow and exposes a
    // `send: (Event) -> Unit` lambda on the returned Presentation. We
    // capture the latest send into a Compose state so the scenario's
    // `PresenterScenarioImpl.send` can call it directly. Compose's
    // re-composition keeps this fresh as the presenter re-runs.
    val sendRef = mutableStateOf<((Event) -> Unit)?>(null)

    // Recomposer runs in its own coroutine so the scenario body can
    // interleave with it. The exception handler catches crashes *inside
    // the recomposer loop itself* — things that throw during Compose's
    // change-apply phase. `on<>` handler crashes are now caught by the
    // runtime and surface through `presentation.eventErrorFlow`, drained
    // into [eventErrorChannel] below for the scenario to assert on.
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
        val presentation = presenter()
        sendRef.value = presentation.send
        val nextState = presentation.state
        if (stateHolder.current != nextState) {
            stateHolder.current = nextState
            // Opt-out short-circuits the append so long-running tests
            // don't accumulate a huge list. Failure messages adapt by
            // just rendering the empty history.
            if (recordStateHistory) history += nextState
        }
        LaunchedEffect(presentation.effectFlow) {
            presentation.effectFlow.collect { effectChannel.send(it) }
        }
        LaunchedEffect(presentation.eventErrorFlow) {
            presentation.eventErrorFlow.collect { eventErrorChannel.send(it) }
        }
    }

    val impl = PresenterScenarioImpl<Event, State, Effect>(
        sendDelegate = { event ->
            val current = sendRef.value
                ?: error("Scenario tried to send before the presenter's first composition. Call advance() first.")
            current(event)
        },
        stateHolder = stateHolder,
        stateHistory = history,
        effectChannel = effectChannel,
        eventErrorChannel = eventErrorChannel,
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
 * Sibling of [testPresenter] for sub-presenters declared as
 * `@Composable PresenterScope<Event, Effect>.foo(): State`. Lets you
 * unit-test the sub-presenter in isolation without constructing its
 * parent or any `@MapTo` / `@MapFrom` plumbing.
 *
 * The scenario API is identical to [testPresenter]:
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
    recordStateHistory: Boolean = true,
    subPresenter: @Composable PresenterScope<Event, Effect>.() -> State,
    scenario: suspend PresenterScenario<Event, State, Effect>.() -> Unit,
): TestResult = testPresenter(
    context = context,
    recordStateHistory = recordStateHistory,
    presenter = { buildPresenter(subPresenter) },
    scenario = scenario,
)
