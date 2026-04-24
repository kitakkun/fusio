# Step 10: Sub-presenter signature ergonomics (Design)

Status: **design + Phase 1 (factory) landing.** Annotation-driven rewrite (Phase 2) is explicitly deferred.

## Problem

The sub-presenter declaration shape is:

```kotlin
@Composable
fun PresenterScope<TaskListEvent, TaskListEffect>.taskList(): TaskListState {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    on<TaskListEvent.Add> { … }
    on<TaskListEvent.Toggle> { … }
    on<TaskListEvent.Remove> { … }
    return TaskListState(tasks)
}
```

It works, but the signature is heavy:

- `PresenterScope<TaskListEvent, TaskListEffect>` occupies the extension-receiver position, dominating the first line visually even though it's mostly metadata — what really matters about `taskList` is that it handles `TaskListEvent`s and emits `TaskListEffect`s and returns `TaskListState`.
- `@Composable` sits on the line above, which means the function header is spread across three visual layers (annotation, extension receiver with generics, name + return).
- IDE hover / auto-complete anchors on the extension receiver, producing long "what am I calling?" popups.

The mental model the reader actually wants is *"this is a presenter with event type X, effect type Y, state type Z; here's its body."* The current syntax reorders those three facts in a way that hides the three-parameter identity behind Kotlin's extension-function grammar.

## Iteration log

Ten rounds of sketch → critique before landing on a direction.

### Round 1–3 — framing

The question is *where do `<Event, Effect, State>` go* and *how do `on<>` / `emitEffect` resolve*. Any approach that hides the receiver type needs a story for both questions — `on<>` is an extension on `PresenterScope`, so the scope must be available to resolve `on<>` from a non-receiver position.

### Round 4 — typealiases (pure user-side)

```kotlin
typealias TaskListScope = PresenterScope<TaskListEvent, TaskListEffect>

@Composable
fun TaskListScope.taskList(): TaskListState { … }
```

Zero library change, ~80% of the visual win. Downside: every user re-invents the naming convention; drift between repos; no enforcement the alias actually matches its presenter's inferred types. **Recipe to document, not the library's primary answer.**

### Round 5 — `@Presenter(events, effects)` annotation

User's original intuition. The annotation ideally replaces the extension receiver:

```kotlin
@Presenter(events = TaskListEvent::class, effects = TaskListEffect::class)
@Composable
fun taskList(): TaskListState { … }
```

To make `on<>` / `emitEffect` resolve inside the body without an explicit receiver, the compiler plugin would have to do FIR-level rewriting — synthesising a `PresenterScope<E, F>` extension receiver from the annotation before IR. Non-trivial; biggest rewrite of the plugin surface to date. Tooling risk with IDE's FIR caches is also real.

Parked as **Phase 2 (future)** — viable, but Phase 1's cost/benefit doesn't justify the scope yet.

### Round 6 — context parameters

Kotlin 2.3+'s `context(PresenterScope<E, F>)` form:

```kotlin
context(PresenterScope<TaskListEvent, TaskListEffect>)
@Composable
fun taskList(): TaskListState { … }
```

Keeps the same visual weight (or more) as the extension-receiver form. Doesn't improve readability; introduces a newer-syntax requirement. **No clear win — rejected.**

### Round 7 — auto-infer events/effects from body

Let the plugin scan the body, collect `on<Foo>` / `on<Bar>` declarations, unify them into the inferred event type. Same for `emitEffect`. Elegant in theory but compile-time sealed-class union inference is complicated, error messages become magical, and the parent `@MapTo` / `@MapFrom` exhaustiveness checker now has to look at a function's BODY rather than its signature. **Too speculative — rejected.**

### Round 8 — interface + impl split

```kotlin
interface TaskListPresenter : Presenter<TaskListEvent, TaskListEffect, TaskListState>

@Composable
fun TaskListPresenter.present(): TaskListState { … }
```

Splits what was one declaration into two with no net signature saving. **Rejected.**

### Round 9 — `presenter<E, F, S> { body }` factory

Identity function that types its argument lambda:

```kotlin
val taskList = presenter<TaskListEvent, TaskListEffect, TaskListState> {
    var tasks by remember { mutableStateOf(listOf<Task>()) }
    on<TaskListEvent.Add> { event ->
        tasks = tasks + Task(event.title)
        emitEffect(TaskListEffect.Added(event.title))
    }
    TaskListState(tasks)
}
```

- `<Event, Effect, State>` collected into a single three-arg type list on the factory call; body becomes a plain lambda.
- `on<>` / `emitEffect` resolve on the implicit `this` inside the lambda (the lambda's type is `@Composable PresenterScope<E, F>.() -> S`).
- `fuse { taskList() }` works unchanged — the lambda invokes as an extension-lambda against whatever scope `fuse`'s block receiver is.
- Zero compiler-plugin change. One public function + typealias added to `fusio-runtime`.

### Round 10 — evaluation

Factory form doesn't *eliminate* the `PresenterScope<E, F>` implication (it's still the implicit receiver on the lambda), it just **moves the three type parameters to a position where they read as "this presenter has these types" instead of "this function is an extension on that receiver."**

Functionally identical to the extension-function form — both compile to the same shape and both work with `fuse { }`. That makes the change **additive**: existing presenters keep working, new presenters can choose whichever style their author prefers.

## Decision

**Phase 1 (this doc):** Ship the `presenter<E, F, S> { body }` factory in `fusio-runtime`. Rewrite the demo's sub-presenters to use it as the primary example style. README gains both forms side-by-side so readers can choose.

**Phase 2 (deferred):** Annotation-driven `@Presenter(events, effects)` that synthesises the extension receiver via FIR. Revisit once:
- The factory has real-world usage data showing it still feels heavy.
- FIR plugin API on the Kotlin side is stable enough that the churn cost across Kotlin versions is bounded.

## Implementation plan (Phase 1)

1. `fusio-runtime/src/commonMain/kotlin/com/kitakkun/fusio/Presenter.kt`
   - `typealias PresenterBody<Event, Effect, State> = @Composable PresenterScope<Event, Effect>.() -> State`
   - `fun <Event, Effect, State> presenter(body: PresenterBody<…>): PresenterBody<…>` (identity wrapper)
2. Update `demo/src/commonMain/.../{TaskListPresenter,FilterPresenter}.kt` to factory form.
3. Verify `demo/src/jvmTest/*PresenterTest.kt` still passes — `fuse { taskList() }` call site is unchanged.
4. Update README's "Example" section to show both the extension-function form and the factory form, note they're equivalent.
5. Regenerate BCV dumps for `fusio-runtime` (one new public function + one typealias).
6. Confirm `./gradlew check` green across 212 tasks.

## Non-goals

- `presenter` is not a DSL entry point; it doesn't take named args (`events`, `effects`, `state`) or chain calls. It's a single-expression identity function.
- No runtime behaviour change — the factory returns its argument unchanged. `fuse`'s IR transformer doesn't need to know factory-declared presenters from extension-declared ones.
- No rename of the existing extension-function form. Both remain first-class.

## Open questions (tracked for Phase 2)

- Should `@Presenter(events, effects)` carry the state type too? Return-type inference suggests no — the function's return type already pins it.
- Should the annotation live in `fusio-annotations` alongside `@MapTo` / `@MapFrom`?
- How does the FIR rewrite interact with sub-presenters that `fuse` into other sub-presenters at multiple depths?

These stay open until Phase 2 is actually justified by observed pain.
