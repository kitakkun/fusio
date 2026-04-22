# Aria

> ⚠️ Experimental — a research project exploring Composable presenter decomposition, building on DroidKaigi 2024 / 2025-style Compose architectures.

Aria is a Kotlin Compiler Plugin library for **decomposing fat Composable Presenters into reusable, self-contained sub-presenters**, with automatic event/effect bridging between them.

## Problem

Composable-based Presenters grow unwieldy as screens accumulate features:

```kotlin
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): MyScreenUiState {
    var state1 by remember { mutableStateOf(...) }
    var state2 by remember { mutableStateOf(...) }
    var state3 by remember { mutableStateOf(...) }

    LaunchedEffect(...) { /* wire state1 */ }
    LaunchedEffect(...) { /* wire state2 */ }
    // ...and so on
}
```

Splitting them into sub-presenters normally forces you to plumb events and effects by hand. Aria makes that plumbing a compile-time concern.

## What Aria gives you

- `buildPresenter(eventFlow) { ... }` — screen-level presenter entry
- `on<Event> { ... }` — typed handler wired to the presenter's event flow
- `mappedScope { subPresenter() }` — delegate to a sub-presenter; the compiler plugin rewrites the call site to wire events and effects
- `@MapTo(ChildEvent::class)` / `@MapFrom(ChildEffect::class)` — declare event/effect mappings between parent and child sealed hierarchies
- FIR checkers that validate property compatibility and require exhaustive mappings at compile time

## Current status (April 2026)

End-to-end working:

```
buildPresenter ─┬─> on<Event>                   ✔
                └─> mappedScope { subPresenter() } ✔  (nested ✔, data object subtypes ✔)
                     │
                     ├─ @MapTo event mapping       ✔
                     └─ @MapFrom effect forwarding ✔

FIR checkers:
  @MapTo/@MapFrom property compatibility          ✔
  Exhaustive mappings over child sealed subtypes  ✔
```

See `sample/src/jvmMain` for a runnable example (`../gradlew runJvm` from within `sample/`).

## Example

```kotlin
// Child (sub-presenter) types
sealed interface FavoriteEvent {
    data class Toggle(val id: String) : FavoriteEvent
}
sealed interface FavoriteEffect {
    data class ShowMessage(val message: String) : FavoriteEffect
}

// Parent (screen-level) types
sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent
}
sealed interface MyScreenEffect {
    @MapFrom(FavoriteEffect.ShowMessage::class)
    data class ShowSnackbar(val message: String) : MyScreenEffect
}

// Sub-presenter (reusable, has no parent knowledge)
@Composable
fun PresenterScope<FavoriteEvent, FavoriteEffect>.favorite(): Aria<FavoriteState, FavoriteEffect> {
    var favorited by remember { mutableStateOf(false) }
    on<FavoriteEvent.Toggle> { event ->
        favorited = !favorited
        emitEffect(FavoriteEffect.ShowMessage("Toggled ${event.id}"))
    }
    return Aria(FavoriteState(favorited), emptyFlow())
}

// Screen presenter — the compiler plugin wires mappedScope
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): Aria<MyScreenUiState, MyScreenEffect> =
    buildPresenter(eventFlow) {
        val favoriteState = mappedScope { favorite() }
        MyScreenUiState(favoriteState)
    }
```

When a `MyScreenEvent.ToggleFavorite` is emitted on the parent event flow, Aria:
1. Maps it to `FavoriteEvent.Toggle` via the `@MapTo` annotation
2. Delivers it to the child's `on<FavoriteEvent.Toggle>` handler
3. Forwards the child's `FavoriteEffect.ShowMessage` back to the parent as `MyScreenEffect.ShowSnackbar` via `@MapFrom`

All of this is generated at compile time with no reflection.

## Project layout

```
aria-annotations/      @MapTo, @MapFrom
aria-runtime/          Aria, PresenterScope, buildPresenter, on, mappedScope stub
aria-compiler-plugin/  FIR checkers + IR transformer
aria-gradle-plugin/    KotlinCompilerPluginSupportPlugin integration
sample/                Headless Compose runner exercising the full pipeline (composite build)
```

## Build

```
./gradlew build
./gradlew :aria-runtime:jvmTest

# Run the sample
cd sample
../gradlew runJvm
```

### Plugin ordering

Aria's IR transformer must run **before** the Compose compiler plugin, because Compose injects `$composer`/`$changed` parameters into `@Composable` lambdas that Aria needs to rewrite first. The Aria Gradle plugin sets this automatically by injecting `-Xcompiler-plugin-order=com.kitakkun.aria>androidx.compose.compiler.plugins.kotlin` into every Kotlin compilation, so applying the plugin is enough — no extra configuration required.

### Kotlin version compatibility

| Your Kotlin | Compiler-plugin artifact |
|-------------|--------------------------|
| 2.3.x       | `aria-compiler-plugin` (default) |
| 2.4.0-Beta+ | `aria-compiler-plugin-k24` |

The Gradle plugin detects your Kotlin version at apply time and resolves the matching artifact for you — no manual dependency declaration needed. `aria-runtime` and `aria-annotations` are version-agnostic and shared across all variants.

## License

Aria is licensed under the [Apache License, Version 2.0](LICENSE).

The design of the compiler-compat layer is inspired by [ZacSweers/Metro](https://github.com/ZacSweers/metro) (also Apache 2.0). See [NOTICE](NOTICE) for details.
