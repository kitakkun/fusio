# Step 12: Event-handler exhaustiveness check

Status: **landing.** L3 coverage analysis (direct `on<T>` + fuse-routed via `@MapTo`) for `buildPresenter` call sites. Sub-presenter declaration coverage deferred for compat reasons.

## Problem

Pre-Step-12, the FIR checker surface enforced `@MapTo` / `@MapFrom`
exhaustiveness ("every child sealed subtype is referenced from some
parent annotation") but said nothing about `on<E>` handler coverage.
A presenter body could declare an Event sealed type with N subtypes
and silently ignore (N − k) of them — the only signal a maintainer
got was "events arrive, nothing happens". A real example from a
downstream consumer:

```kotlin
@Composable
fun timetableScreenPresenter(): Presentation<TimetableScreenAction, …, …> = buildPresenter {
    // on<…> calls all commented out; ToggleBookmark and friends
    // never reach a handler. Compiler is silent.
    TimetableScreenUiState()
}
```

## Coverage model

For each `buildPresenter<Event, Effect, State> { body }` call site, a
parent-Event sealed subtype `X` is **covered** iff one of:

1. **Direct**: some `on<T>` call inside `body` has `X <: T`.
   Walking the supertype chain by class id (not full
   `ConeKotlinType.isSubtypeOf`) keeps the check stable across Kotlin
   minor releases — sealed Event hierarchies don't use type parameters
   in practice, so the loss of generic-substitution rigor doesn't matter.
2. **Fuse-routed**: `X` carries `@MapTo(target = Y::class)` and `Y` is
   in the sealed hierarchy of any of the fused child Event types
   reached from `body` (`fuse<CE, …> { … }` calls).

Anything left after both passes is reported.

## Scope: top-level only

The implementation hooks `buildPresenter` call sites only. Standalone
sub-presenter declarations
(`@Composable fun PresenterScope<E, F>.foo(): S { body }`) **aren't
checked at the declaration site itself** — they're only reached
indirectly via the parent's `fuse` rewrite.

The sub-presenter checker would require a `FirSimpleFunctionChecker`
override, whose parameter type changed mid-Kotlin-2.3.x:

- 2.3.0 / 2.3.10: `override fun check(declaration: FirSimpleFunction)`
- 2.3.20+: `override fun check(declaration: FirNamedFunction)` (renamed)

Either binding loads cleanly only against half the supported range.
Adding the sub-presenter checker would mean splitting it into per-Kotlin
compat impls (a chunk of work that doesn't pay off until the missing
coverage actually bites). For now, the top-level check is the higher-
value half — the original missing-handler example was a `buildPresenter`
body anyway — and we revisit when observed pain justifies the compat work.

## Severity

The check is configurable via three modes:

- `ERROR` — fail compilation
- `WARNING` (default) — log warning, build succeeds
- `NONE` — skip the check entirely

### From a consumer build

DSL form, type-safe via the `FusioPluginExtension`:

```kotlin
fusio {
    eventHandlerExhaustiveSeverity = EventHandlerExhaustiveSeverity.ERROR
}
```

Gradle property fallback (CI overrides without editing the script):

```bash
./gradlew build -Pfusio.event-handler-exhaustive-severity=error
```

Resolution order at `applyToCompilation` time:

1. DSL value (if set)
2. Gradle property (`fusio.event-handler-exhaustive-severity`)
3. Default (`WARNING`)

### Wire format → compiler plugin

The Gradle plugin lowercases the enum name and passes it as a
`SubpluginOption("event-handler-exhaustive-severity", "error|warning|none")`.
The compiler-plugin side mirrors with its own internal enum
(`com.kitakkun.fusio.compiler.EventHandlerExhaustiveSeverity`) — they
don't share a class because they live on different classloaders. They
agree on the wire format names.

## Diagnostic factories

Two factories are pre-registered, one per severity:

- `MISSING_EVENT_HANDLER_ERROR` (severity ERROR)
- `MISSING_EVENT_HANDLER_WARNING` (severity WARNING)

Selected at fire time. `NONE` short-circuits before either fires.
Two factories instead of one with dynamic severity because Kotlin's
diagnostic plumbing pins severity at factory construction.

## Why not enforce ERROR by default

Existing presenters in the wild may legitimately ignore some events
(analytics-only listeners, partial-handler stubs during refactors).
WARNING is the safe-to-introduce default that surfaces issues without
breaking builds; teams that want strictness can opt in via the DSL.

## Implementation files

- `fusio-compiler-plugin/src/main/kotlin/com/kitakkun/fusio/compiler/`
  - `FusioConfigurationKeys.kt` — adds `EVENT_HANDLER_EXHAUSTIVE_SEVERITY` key + internal enum
  - `FusioCommandLineProcessor.kt` — `-P plugin:com.kitakkun.fusio:event-handler-exhaustive-severity=…`
  - `FusioErrors.kt` — `MISSING_EVENT_HANDLER_ERROR` / `_WARNING` factories
  - `FusioEventHandlerExhaustivenessChecker.kt` — the actual analysis
  - `FusioFirCheckersExtension.kt` — registers the call checker
  - `FusioFirExtensionRegistrar.kt` — passes severity through
  - `FusioCompilerPluginRegistrar.kt` — reads from CompilerConfiguration
  - `FusioClassIds.kt` — `ON` and `BUILD_PRESENTER` callable ids
- `fusio-gradle-plugin/src/main/kotlin/com/kitakkun/fusio/gradle/`
  - `EventHandlerExhaustiveSeverity.kt` — public enum
  - `FusioPluginExtension.kt` — `fusio { … }` DSL
  - `FusioGradlePlugin.kt` — extension registration + severity resolution + SubpluginOption
- `fusio-compiler-plugin-tests/testData/diagnostics/`
  - `missingEventHandler.kt` — uncovered subtype, expects `_WARNING`
  - `eventHandlerCovered.kt` — every subtype handled directly
  - `eventHandlerCoveredViaParent.kt` — `on<MyEvent>` covers all subtypes
  - `eventHandlerCoveredViaFuse.kt` — fuse-routed coverage via `@MapTo`

## Acknowledgement

The `version-aliases.txt`-style "single source-of-truth file → bake into
plugin → check at apply time" pattern, the option-naming convention, and
the "ship as enum DSL with Gradle-property fallback" shape are borrowed
from ZacSweers/Metro. NOTICE updated to extend the existing
acknowledgement to this work as well.
