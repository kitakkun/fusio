# Step 2: fusio-runtime

## Module: `fusio-runtime`

Runtime API that users interact with directly. Depends on Compose Runtime and Kotlinx Coroutines.

## Core Types

### Fusio<State, Effect>

The core return type of both screen-level and sub-level Presenters. Library name = core type.

```kotlin
package com.kitakkun.fusio

import kotlinx.coroutines.flow.Flow

data class Fusio<State, Effect>(
    val state: State,
    val effectFlow: Flow<Effect>,
)
```

### PresenterScope<Event, Effect>

Scope that manages Event/Effect plumbing internally. `eventFlow` is never exposed to users.

```kotlin
package com.kitakkun.fusio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class PresenterScope<Event, Effect>(
    internal val eventFlow: Flow<Event>,
) {
    private val _effectChannel = Channel<Effect>(Channel.UNLIMITED)
    internal val internalEffectFlow: Flow<Effect> = _effectChannel.receiveAsFlow()

    suspend fun emitEffect(effect: Effect) {
        _effectChannel.send(effect)
    }

    internal fun close() {
        _effectChannel.close()
    }
}
```

**Design decisions:**
- `eventFlow` is `internal` - users access events via `on<Event>` only
- `Channel.UNLIMITED` prevents loss of Effects if collector isn't ready
- `close()` is called via `DisposableEffect` when Composition is destroyed

### on\<Event\>

Type-safe event handler. Filters `eventFlow` by type and collects.

```kotlin
@Composable
inline fun <reified E> PresenterScope<*, *>.on(
    noinline handler: suspend (E) -> Unit,
) {
    val flow = eventFlow
    LaunchedEffect(Unit) {
        flow.filterIsInstance<E>().collect { handler(it) }
    }
}
```

### buildPresenter

DSL entry point for screen-level Presenters.

```kotlin
@Composable
fun <Event, Effect, UiState> buildPresenter(
    eventFlow: Flow<Event>,
    block: @Composable PresenterScope<Event, Effect>.() -> UiState,
): Fusio<UiState, Effect> {
    val scope = remember { PresenterScope<Event, Effect>(eventFlow) }

    DisposableEffect(Unit) {
        onDispose { scope.close() }
    }

    val uiState = scope.block()

    return Fusio(uiState, scope.internalEffectFlow)
}
```

### mappedScope (Stub)

Stub that will be replaced by the IR Transformer. Throws at runtime if compiler plugin is not applied.

```kotlin
@Composable
fun <ChildEvent, ChildEffect, ChildState> PresenterScope<*, *>.mappedScope(
    block: @Composable PresenterScope<ChildEvent, ChildEffect>.() -> Fusio<ChildState, ChildEffect>,
): ChildState {
    error(
        "mappedScope requires the Fusio Compiler Plugin. " +
        "Make sure 'com.kitakkun.fusio' Gradle plugin is applied."
    )
}
```

## Build Configuration

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":fusio-annotations"))
                implementation(compose.runtime)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}
```

## Open Questions

1. **eventFlow update on recomposition**: When `buildPresenter` recomposes with a new `eventFlow` instance, the `PresenterScope` holds the old reference via `remember`. The `on<Event>` handler collects with `LaunchedEffect(Unit)`, which captures the initial `eventFlow` and never re-collects.
   
   **Proposed fix**: Use `rememberUpdatedState` for eventFlow reference, or re-key `LaunchedEffect` on `eventFlow`:
   ```kotlin
   @Composable
   inline fun <reified E> PresenterScope<*, *>.on(noinline handler: suspend (E) -> Unit) {
       val currentFlow = rememberUpdatedState(eventFlow)
       LaunchedEffect(Unit) {
           snapshotFlow { currentFlow.value }
               .flatMapLatest { it.filterIsInstance<E>() }
               .collect { handler(it) }
       }
   }
   ```
   **Alternative**: In practice, `eventFlow` may be a stable `SharedFlow` from the UI layer that doesn't change reference. If so, this is a non-issue. Decide based on the expected caller pattern (DroidKaigi architecture uses stable flows).

2. **PresenterScope lifecycle**: Should PresenterScope survive configuration changes? If used with `rememberRetained` (Soil/Circuit pattern), the Channel must also be retained.

3. **Fusio return type**: Should `Fusio<State, Nothing>` have a convenience constructor that doesn't require effectFlow? e.g., `fun <State> Fusio(state: State) = Fusio(state, emptyFlow())`
