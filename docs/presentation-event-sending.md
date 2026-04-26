# Step 11: Presentation owns event sending

Status: **landing.** `Presentation<State, Event, Effect>` now exposes `send: (Event) -> Unit`; `buildPresenter` no longer takes an external event flow.

## Problem

The pre-Step-11 shape forced every caller to manufacture and remember an event flow:

```kotlin
@Composable
fun MyScreen() {
    val events = remember {
        MutableSharedFlow<MyScreenEvent>(extraBufferCapacity = 64)
    }
    val presentation = buildPresenter(events) { /* body */ }

    Button(onClick = { events.tryEmit(MyScreenEvent.Click) }) { … }
}
```

Three things wrong with this:

1. **Boilerplate.** `events = remember { MutableSharedFlow(…) }` is identical at every Fusio call site. The `extraBufferCapacity = 64` value is arbitrary and copied around.
2. **Two reference points.** The caller must keep `events` alive for `tryEmit` calls *and* keep `presentation` alive for state/effect reads — they're conceptually one thing but live as two locals.
3. **Mismatched ownership.** Outputs (`state`, `effectFlow`, `eventErrorFlow`) flow from the presenter; the input flow flows *into* the presenter. The asymmetry leaks library plumbing into user code.

## Iteration log

### Round 1 — drop the external flow entirely

Idea: have `buildPresenter` create + remember its own `MutableSharedFlow` internally. But then how does the caller emit events? Need a handle on the input side.

### Round 2 — `Presentation.send`

Add `send: (Event) -> Unit` as a fourth field on `Presentation`. The buffer is owned and lifecycle-managed by `buildPresenter`.

```kotlin
val presentation = buildPresenter<MyScreenEvent, …, …> { /* body */ }
Button(onClick = { presentation.send(MyScreenEvent.Click) }) { … }
```

### Round 3 — type-parameter cost

`Presentation<State, Effect>` becomes `Presentation<State, Event, Effect>`. Type-parameter order is intentionally consumer-facing — `state` first because UI renders it every frame, `event` second because UI sends one on every interaction, `effect` last because UI collects it once via `LaunchedEffect`. The producer-side types (`buildPresenter<Event, Effect, UiState>`, `PresenterScope<Event, Effect>`) keep their event-first ordering for the same reason: from the producer's perspective, `Event` is the input axis. Two perspectives, two orderings — the inconsistency is real but each side reads naturally for its caller.

### Round 4 — bin compat impact

Pre-v0.1.0; BCV ABI dump regenerates cleanly. Acceptable cost.

### Round 5 — keep the external-flow path?

A two-overload `buildPresenter` (with/without external flow) was considered for users who want to bridge a URL deeplink or navigation event into the presenter's input. Trade-offs:

- **Pro:** Keeps the escape hatch.
- **Con:** Doubles the API surface, complicates `Presentation`'s `send` semantics (does external also flow through `send`? does `send` exist when the caller owns the flow?).

External-flow bridging can always be reconstructed in user space:

```kotlin
LaunchedEffect(externalFlow) {
    externalFlow.collect { presentation.send(it) }
}
```

So Round 5 lands on **single shape, no external-flow overload**. The bridge case is one `LaunchedEffect` away.

### Round 6 — fuse / sub-presenter implications

Sub-presenters return bare `State`, not `Presentation`. They participate in `fuse { … }`'s IR-rewritten pipeline where the parent's mapped event flow becomes the child's `eventFlow`. **`send` is a parent-presenter concern only**; sub-presenters get events from their parent's mapped flow. No IR transformer change needed.

### Round 7 — Compose stability

`send: (Event) -> Unit` lives on `Presentation`, which is `@Stable`. The lambda is constructed inside `buildPresenter` (`@Composable`); Compose's lambda memoization auto-`remember`s lambda literals when all captures are stable. The captured `MutableSharedFlow` reference is itself stable (returned from `remember`), so no manual wrapping is required.

### Round 8 — fusio-test

`testPresenter`'s pre-Step-11 signature was `presenter = { events -> buildPresenter(events) { … } }`. Step 11 simplifies to `presenter = { buildPresenter { … } }`; the harness calls `presentation.send` directly instead of plumbing its own `MutableSharedFlow`. The user-facing scenario API (`send(event)`) is unchanged — only internal wiring differs.

### Round 9 — buffer policy

Internal flow uses `extraBufferCapacity = 64` matching the previous testPresenter buffer. `send` uses `tryEmit` (non-suspending) so it can be called from `onClick` lambdas. Overflow returns `false` silently — handler-side back-pressure isn't a documented Fusio guarantee, and `on<E>` handlers should be quick enough that 64 outstanding events is unreachable in practice. If real workloads contradict this, expose buffer as a `buildPresenter` parameter later.

### Round 10 — single-overload final shape

```kotlin
@Composable
fun <Event, Effect, UiState> buildPresenter(
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Presentation<UiState, Event, Effect>
```

No external-flow overload. `Presentation` carries `send`. `PresenterScope` is unchanged (it still takes `Flow<Event>` from the constructor — supplied internally by `buildPresenter`).

## Decision

Ship the single-overload form. `Presentation<State, Event, Effect>`, `send: (Event) -> Unit`. External-flow bridging is a documented one-liner using `LaunchedEffect`.

## Implementation plan

1. `Presentation`: add `Event` type parameter and `send: (Event) -> Unit` field. Update `equals` / `hashCode` / `toString`.
2. `BuildPresenter`: remove `eventFlow` parameter; create + `remember` an internal `MutableSharedFlow(extraBufferCapacity = 64)`; pass it to `PresenterScope`; expose `tryEmit` as the `send` lambda.
3. `fusio-test/TestPresenter`: drop the `events` synthetic flow; the harness's `send` lambda calls `presentation.send` instead.
4. `fusio-test/PresenterScenarioImpl`: replace the `MutableSharedFlow` field with a `(Event) -> Unit` delegate.
5. `demo/MyScreenPresenter` + tests + README + design docs: drop `events =` boilerplate, switch to the new form.
6. Regenerate BCV dumps for `fusio-runtime` and `fusio-test`.
7. Verify IR golden tests don't shift (PresenterScope's constructor is unchanged; the IR shape `fuse` produces is unchanged).
8. `./gradlew check` green across all lanes.

## Non-goals

- No replacement for the external-flow injection path. Use `LaunchedEffect(external) { external.collect { presentation.send(it) } }` if a presenter needs to be driven from an external Flow.
- No `send` exposed on `PresenterScope` itself. Sub-presenter bodies don't need to "send to themselves" — they handle events via `on<E>` and emit effects via `emitEffect`.
- No suspending `send` overload. `Presentation.send` is fire-and-forget for `onClick`-shaped use; if a caller genuinely needs back-pressure they can capture the underlying scope and `emit` from a coroutine, but that's outside the documented surface.

## Open questions

- Should the buffer capacity be a `buildPresenter` parameter? Defer until a real workload demonstrates 64 isn't enough.
- Does Step 10's sub-presenter ergonomics work intersect with this change? No — sub-presenters return `State`, not `Presentation`, so they're untouched by Step 11. (Step 10's `presenter { }` factory was later withdrawn for unrelated reasons; see presenter-signature-ergonomics.md.)
