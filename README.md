# Fusio

> ⚠️ Experimental — a research project exploring Composable presenter decomposition, building on DroidKaigi 2024 / 2025-style Compose architectures.

Fusio is a Kotlin Compiler Plugin library for **decomposing fat Composable Presenters into reusable, self-contained sub-presenters**, with automatic event/effect bridging between them.

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

Splitting them into sub-presenters normally forces you to plumb events and effects by hand. Fusio makes that plumbing a compile-time concern.

## What Fusio gives you

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

See `demo/` for a runnable Compose Desktop example (`../gradlew runJvm` from within `demo/`).

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
fun PresenterScope<FavoriteEvent, FavoriteEffect>.favorite(): Fusio<FavoriteState, FavoriteEffect> {
    var favorited by remember { mutableStateOf(false) }
    on<FavoriteEvent.Toggle> { event ->
        favorited = !favorited
        emitEffect(FavoriteEffect.ShowMessage("Toggled ${event.id}"))
    }
    return Fusio(FavoriteState(favorited), emptyFlow())
}

// Screen presenter — the compiler plugin wires mappedScope
@Composable
fun myScreenPresenter(eventFlow: Flow<MyScreenEvent>): Fusio<MyScreenUiState, MyScreenEffect> =
    buildPresenter(eventFlow) {
        val favoriteState = mappedScope { favorite() }
        MyScreenUiState(favoriteState)
    }
```

When a `MyScreenEvent.ToggleFavorite` is emitted on the parent event flow, Fusio:
1. Maps it to `FavoriteEvent.Toggle` via the `@MapTo` annotation
2. Delivers it to the child's `on<FavoriteEvent.Toggle>` handler
3. Forwards the child's `FavoriteEffect.ShowMessage` back to the parent as `MyScreenEffect.ShowSnackbar` via `@MapFrom`

All of this is generated at compile time with no reflection.

## Project layout

```
fusio-annotations/      @MapTo, @MapFrom                    (Kotlin Multiplatform)
fusio-runtime/          Fusio, PresenterScope, buildPresenter, on, mappedScope stub
                                                             (Kotlin Multiplatform + Compose Multiplatform)
fusio-compiler-plugin/  FIR checkers + IR transformer        (JVM, single shaded jar)
fusio-gradle-plugin/    KotlinCompilerPluginSupportPlugin integration
demo/                  Compose Desktop app using Fusio end-to-end (composite build)
```

### Platform targets

`fusio-annotations` and `fusio-runtime` publish to:

| Target | Status |
|---|---|
| JVM | ✅ |
| iOS (`iosArm64`, `iosSimulatorArm64`) | ✅ |
| macOS (`macosArm64`) | ✅ |
| JS (`js(IR)` — browser & node) | ✅ |
| Wasm (`wasmJs` — browser & node) | ✅ |

`commonTest` runs on all of the above. Android, watchOS, tvOS, Linux, and Windows aren't configured yet but pose no fundamental obstacle — see `fusio-runtime/build.gradle.kts` to add more.

## Build

```
./gradlew build                       # compile + test every target
./gradlew :fusio-runtime:allTests     # runtime tests on every platform
./gradlew :fusio-runtime:jvmTest      # JVM only (fastest feedback)

# Run the demo (Compose Desktop window)
cd demo
../gradlew runJvm
```

Builds run with Gradle 9 configuration cache enabled; incremental rebuilds complete in under a second after the first run.

### Plugin ordering

Fusio's IR transformer must run **before** the Compose compiler plugin, because Compose injects `$composer`/`$changed` parameters into `@Composable` lambdas that Fusio needs to rewrite first. The Fusio Gradle plugin sets this automatically by injecting `-Xcompiler-plugin-order=com.kitakkun.fusio>androidx.compose.compiler.plugins.kotlin` into every Kotlin compilation, so applying the plugin is enough — no extra configuration required.

### Kotlin version compatibility

| Your Kotlin | Supported |
|-------------|-----------|
| 2.3.x       | ✅ |
| 2.4.0-Beta2+ | ✅ |

A single `fusio-compiler-plugin` jar ships with a per-Kotlin-version compatibility layer inside it (via a shaded `fusio-compiler-compat` + `kXXX` submodule pattern inspired by [ZacSweers/Metro](https://github.com/ZacSweers/metro)). At compile time the plugin inspects the running Kotlin compiler and `ServiceLoader`-resolves the matching impl, so there's nothing to configure per Kotlin version.

## License

Fusio is licensed under the [Apache License, Version 2.0](LICENSE).

The design of the compiler-compat layer is inspired by [ZacSweers/Metro](https://github.com/ZacSweers/metro) (also Apache 2.0). See [NOTICE](NOTICE) for details.
