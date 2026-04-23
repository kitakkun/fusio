# Step 8: Multi-Kotlin-compiler-version support

Status: **Phase 2 landed, since widened** (single shaded jar, Metro-style compiler-compat layer, Kotlin 2.3.0 through 2.4.0-Beta2 all work end-to-end, `smokeK24` task proves k240_beta2 at runtime). Phase 1's sibling-variant-module scheme is retired. The as-built state below supersedes the Phase 1 and Phase 2-plan sections further down — those are kept for historical context and future-version bring-up.

## Phase 2 as-built (current state)

### Module layout

```
fusio-compiler-plugin/             FIR checkers + IR FuseTransformer
  build.gradle.kts                shadow plugin shades the compat modules in
fusio-compiler-compat/             host module
  src/.../CompatContext.kt        interface + Version/VersionRange + Factory
  src/.../CompatContextResolver.kt ServiceLoader-based runtime selection
  k230/
    CompatContextImpl.kt          Kotlin 2.3.0–2.3.19 impl (legacy
                                  `referenceClass` / `referenceFunctions`
                                  for the finder trio, same session-carrying
                                  kclassArg as k2320 otherwise)
    META-INF/services/…Factory    registers Factory for ServiceLoader
  k2320/
    CompatContextImpl.kt          Kotlin 2.3.20+ impl (uses
                                  `finderForBuiltins()` / `DeclarationFinder`,
                                  kclassArg with session, list-access IrCall
                                  writes)
    META-INF/services/…Factory    registers Factory for ServiceLoader
  k240_beta2/
    CompatContextImpl.kt          Kotlin 2.4.x impl (delegates to k2320,
                                  overrides kclassArg to call the session-
                                  less 2.4 signature)
    META-INF/services/…Factory    registers Factory for ServiceLoader
```

### How it works

- `CompatContext` declares every API the plugin needs whose signature shifts between Kotlin versions — currently `kclassArg`, `setArg`, `setTypeArg`, `registerFirExtension`, `registerIrGenerationExtension`, the finder trio (`findClass` / `findConstructors` / `findFunctions`), and `localFunctionForLambdaOrigin`.
- Each k** subproject compiles against its pinned `kotlin-compiler-embeddable` version and registers a `CompatContext.Factory` with a `supportedRange: VersionRange`.
- `fusio-compiler-plugin/build.gradle.kts` uses the `com.gradleup.shadow` plugin to bundle the compat jars into a single artifact. A dedicated non-transitive `shaded` configuration keeps kotlin-stdlib and other transitive deps out of the final jar. `mergeServiceFiles()` stitches each subproject's `META-INF/services/.../CompatContext$Factory` into one.
- At compiler runtime, `CompatContextResolver.resolve()` reads `KotlinCompilerVersion.VERSION`, loads every `Factory` via `ServiceLoader`, and instantiates the first whose `supportedRange` contains the running version.
- `FusioIrGenerationExtension` and `FusioFirCheckersExtension` call `CompatContextResolver.resolve()` eagerly at construction, so a classpath missing a matching impl fails with a clear error before any analysis runs.
- Checkers and `FuseTransformer` delegate to the resolved context via Kotlin interface delegation (`: CompatContext by compat`), so call sites inside the plugin read like `annotation.kclassArg(...)` / `call.setArg(...)` with no version-sensitive API touched directly.

### Published artifact

- `com.kitakkun.fusio:fusio-compiler-plugin:<fusio-version>` — single shaded jar
- `fusio-compiler-compat` and its k** subprojects are NOT published separately; they're shaded in.
- `FusioGradlePlugin.getPluginArtifact()` returns a single coordinate; the Kotlin version dispatch happens at compiler runtime inside the jar, not at Gradle-apply time.

### Composite-build compatibility

The `demo/` subproject consumes `:fusio-compiler-plugin` through Gradle's intra-build mechanism, which resolves via the project's `runtimeElements` / `apiElements` configurations rather than the published Maven artifact. `fusio-compiler-plugin/build.gradle.kts` explicitly swaps those configurations' outgoing artifact from the plain `jar` task output to `shadowJar`, so in-repo consumers see the same shaded jar that mavenLocal does.

### Validation

- **`:fusio-compiler-plugin-tests:k2321:test`** (Kotlin 2.3.21, primary): full box + diagnostics + IR text suite via `kotlin-compiler-internal-test-framework`. Covers the same pipeline end-to-end a user project would hit.
- **`:fusio-compiler-plugin-tests:k230:test` / `:k2310:test`** (Kotlin 2.3.0 / 2.3.10): box + diagnostics against the older patches. IR lane is omitted — `SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK` wasn't available until 2.3.20. Exercises the `:fusio-compiler-compat:k230` legacy-finder impl.
- **`:fusio-compiler-plugin-tests:tests-k2320:test`** (Kotlin 2.3.20): box + diagnostics, same shape as the k230 lanes.
- **`:fusio-compiler-plugin-tests:tests-k240_beta2:test`** (Kotlin 2.4.0-Beta2): parallel lane running box + diagnostics + IR text against a 2.4-shaped `configure(NonGroupingPhaseTestConfigurationBuilder)` override. Shares the plugin classpath with the primary module; the `k240_beta2` compat impl kicks in at runtime via ServiceLoader. box / diagnostics share the shared testData at `fusio-compiler-plugin-tests/testData/` (behavioural contracts are version-independent). IR text tests live under `fusio-compiler-plugin-tests/k240_beta2/testData/ir/` with their own 2.4-pinned `.fir.ir.txt` / `.fir.kt.txt` goldens — the Kotlin IR dump format shifts across patches (2.4's Compose compiler expands `$stable` into a PROPERTY + getter where 2.3 emits a bare FIELD, for example), so each version gets its own snapshot.
- **`:fusio-compiler-plugin:smokeK24`** (Kotlin 2.4.0-Beta2): still present. Forks a JVM, invokes `K2JVMCompiler` with the shaded plugin jar on `-Xplugin`, and compiles `src/smokeK24/kotlin/Sample.kt`. Different guarantee from the k240_beta2 test lane: this one validates the shaded-jar-load-in-an-isolated-process path; the test lane runs the plugin classes directly on the test JVM's classpath. Both are wired into `check`.
- **demo**: composite-build runtime smoke — `cd demo && ../gradlew runJvm` under the primary Kotlin version exercises the whole state/effect plumbing and renders a Compose Desktop window.

All of the above are wired into the root `check`, so `./gradlew build` exercises every Kotlin lane without extra invocations.

### Known gaps

(none outstanding for the 2.3 / 2.4 pair; add entries here as new Kotlin lines get added.)

## Stress-test probe: Kotlin 2.0.21

Before Phase 2 planning, we probed backwards to Kotlin 2.0.21 (the last 2.0.x) to see how far the shared-source + compat-facade pattern stretches. **Conclusion: it breaks.** The findings below inform why a dedicated compiler-compat layer (separate from the current per-variant `compat/` folders) is worth building next.

### What broke when compiling the current shared source against 2.0.21

Categorised by how hard each break is to work around.

#### Level 1 — function-signature drift (compat function works)

| Break | 2.3.20 shape | 2.0.21 shape |
|-------|--------------|--------------|
| `IrCall.arguments[i]` | list access (since 2.2) | `call.putValueArgument(i, expr)`, `call.extensionReceiver = expr`, `call.dispatchReceiver = expr` |
| `IrCall.typeArguments[i]` | list access | `call.putTypeArgument(i, type)` |
| `IrPluginContext.finderForBuiltins()` | returns DeclarationFinder | `pluginContext.referenceClass(classId)`, `referenceFunctions(callableId)`, `referenceConstructors(classId)` |
| `getKClassArgument(name, session)` | name+session required | session-less overload |

All of these could hide behind compat extension functions of the form `fun IrCall.setArgument(i, expr)`.

#### Level 2 — types that don't exist yet

| Missing class / annotation | Where used | Implication |
|---------------------------|------------|-------------|
| `KtDiagnosticsContainer` | `FusioErrors` extends it | 2.0's diagnostic API was entirely different (pre-`KtDiagnosticFactoryToRendererMap` factory DSL). The whole `FusioErrors` file is unworkable as-is. |
| `DirectDeclarationsAccess` annotation | `@OptIn(DirectDeclarationsAccess::class)` in `FusioFirCheckerUtils` | Pre-existing opt-in wasn't there; remove annotation for 2.0 |
| Factory function `by KtDiagnosticFactoryToRendererMap("name") { ... }` | `FusioErrors` / `FusioErrorMessages` | Different builder shape in 2.0 |

Compat functions can't save this — you can't compat-away a missing receiver type.

#### Level 3 — base-class shape changed

| Break | Why |
|-------|-----|
| `abstract val pluginId` on `CompilerPluginRegistrar` | Not declared in 2.0's `CompilerPluginRegistrar` |
| `registerDiagnosticContainers(...)` on `ExtensionRegistrarContext` | Not available in 2.0's `FirExtensionRegistrar` |

The `override` keyword binds to something that doesn't exist. Compile fails at the class level.

#### Level 4 — override signature change (the real killer)

In 2.2+ checker `check()` uses context parameters:

```kotlin
context(context: CheckerContext, reporter: DiagnosticReporter)
override fun check(declaration: FirClass) { ... }
```

In 2.0 it's a plain method:

```kotlin
override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) { ... }
```

**The shape of the override is different.** No compat function can bridge this — the method signature itself must differ between versions. Every checker class has to be forked per variant.

### Breakage scope estimate

Of our ~500 LoC in `fusio-compiler-plugin/src/main/kotlin/`:

- `FusioErrors.kt` (31 lines) — full rewrite for 2.0 (Level 2)
- `FusioMapToChecker.kt` / `FusioMapFromChecker.kt` / `FusioExhaustivenessChecker.kt` (~200 lines combined) — fork for check() signature (Level 4)
- `FusioCompilerPluginRegistrar.kt` / `FusioFirExtensionRegistrar.kt` / `FusioFirCheckersExtension.kt` (~50 lines) — adjust for Level 3 changes
- `FusioFirCheckerUtils.kt` (30 lines) — compat-fixable if we strip `DirectDeclarationsAccess`
- `MappedScopeTransformer.kt` (375 lines) — many Level 1 breaks but fixable with wider compat layer

So **~300 of 500 lines need per-version content** for a 2.0 variant. The current layout with a single `compat/` file per variant can't carry this — we'd need file-level forks and a wider compat surface.

### What the probe tells us about the design

1. **The current compat-facade pattern covers Level 1 cleanly, nothing above.** That's why 2.3 ↔ 2.4 was a 20-line fix and 2.3 ↔ 2.0 is effectively a rewrite.

2. **Override-signature changes (Level 4) are non-bridgeable** by any facade. They require whole-class forks.

3. **There is a soft boundary around context-parameter checker adoption (Kotlin 2.2)** below which every FIR checker must be duplicated.

4. **Realistic support window** is probably "last 1-2 minors that all sit above the Level 4 boundary." For the current codebase: 2.3 and 2.4 (both use context-parameter checkers). 2.2 might be reachable with a wider compat layer; 2.1 and below are out without a full fork.

5. **Dedicated compat module needed if we want more.** A separate `fusio-compiler-compat/` sub-layer that each variant module depends on — housing `IrCall.setArgument(i)`, `IrPluginContext.findClassSymbol(classId)`, and similar — would at least halve the per-variant surface area. Plan that in Phase 2.

### Action for now

k20 probe is not merged. Neither the `fusio-compiler-plugin-k20/` module nor the `kotlin-k20` catalog entry remained — this document is the paper trail. When someone re-attempts older-version support, start here.

## Phase 2 plan: Metro-style `compiler-compat` layer

**Status: designed, not implemented.** Phase 1 (sibling variant modules, Gradle-side artifact selection) is landed and working. Phase 2 swaps that for a single-artifact design inspired by ZacSweers/Metro's compiler-compat module.

### What Metro does (the pattern we're borrowing)

- **Single `CompatContext` interface** owns every version-sensitive API call. The main compiler plugin code only calls methods on `CompatContext`; it never touches a version-shifting Kotlin compiler API directly.
- **One `CompatContextImpl` per supported Kotlin version**, each sitting in its own Gradle subproject built against its own `kotlin-compiler-embeddable` pin:
  - `compiler-compat/k2220/` (Kotlin 2.2.20)
  - `compiler-compat/k230/` (Kotlin 2.3.0)
  - `compiler-compat/k2320/` (Kotlin 2.3.20)
  - `compiler-compat/k240_beta2/` (Kotlin 2.4.0-Beta2)
  - etc.
- **Delegation chain** reduces duplication: `k2320`'s impl declares `class CompatContextImpl : CompatContext by k230.CompatContextImpl()` and only overrides the methods whose behaviour changed between 2.3.0 and 2.3.20. Minor-to-minor diffs are often two or three methods.
- **ServiceLoader + `CompatContext.Factory`**: each version module registers a `META-INF/services/.../CompatContext$Factory` pointing at its own Factory implementation. At compiler runtime the plugin uses `ServiceLoader` to find the factory whose declared Kotlin-version range matches the running compiler and instantiates the matching `CompatContextImpl`.
- **All compat subprojects are shaded into the main plugin jar**, so end users install a single artifact. The ServiceLoader picks the right impl at runtime; impls whose Kotlin-compiler classes aren't on the classpath simply aren't instantiated (lazy class resolution keeps the dead impls silent).
- **Template script** (`generate-compat-module.sh`) scaffolds a new version's module skeleton, and `fetch-all-ide-kotlin-versions.py` enumerates IDE-bundled Kotlin builds so IntelliJ/Android-Studio-canary users are covered.

### How this maps onto Fusio

Fusio's version-sensitive touchpoints (from the audit earlier in this doc) are:

- `IrCall.arguments[i]` / `typeArguments[i]` writes (Level 1)
- `IrPluginContext.finderForBuiltins()` vs `referenceClass(classId)` (Level 1)
- `FirAnnotation.getKClassArgument(name[, session])` (Level 1 — already covered by current compat)
- `KtDiagnosticsContainer` / `KtDiagnosticFactoryToRendererMap` factory shape (Level 2/3)
- `CompilerPluginRegistrar.pluginId`, `registerDiagnosticContainers` (Level 3)
- `context(CheckerContext, DiagnosticReporter)` checker signature (Level 4 — not bridgeable)

Level 1-3 go behind `CompatContext`. Level 4 (checker class shape) can't — **pre-2.2 support still requires full class forks even under Metro's pattern**, so our realistic support window stays "2.2+ with context-parameter checkers." That matches Metro's own lowest pin (2.2.20).

### Target module layout

```
fusio-compiler-compat/                               top-level host (shaded into main)
├── build.gradle.kts                                compileOnly(kotlin-compiler), publishes CompatContext interface
├── README.md                                       the pattern; mirror of Metro's docs
├── src/main/kotlin/com/kitakkun/fusio/compiler/compat/
│   ├── CompatContext.kt                            interface CompatContext + Factory
│   └── CompatContextResolver.kt                    ServiceLoader-based lookup
├── k2320/
│   ├── build.gradle.kts                            compileOnly(kotlin-compiler-embeddable:2.3.20)
│   ├── src/main/kotlin/.../compat/k2320/CompatContextImpl.kt
│   └── src/main/resources/META-INF/services/.../CompatContext$Factory
├── k240_beta2/
│   ├── build.gradle.kts                            compileOnly(kotlin-compiler-embeddable:2.4.0-Beta2)
│   ├── src/main/kotlin/.../compat/k240_beta2/CompatContextImpl.kt
│   └── src/main/resources/META-INF/services/.../CompatContext$Factory
└── scripts/
    └── generate-compat-module.sh                   (future, when ≥3 variants)

fusio-compiler-plugin/                               primary plugin (single artifact now)
├── build.gradle.kts                                uses shadow plugin; shades fusio-compiler-compat + its k** subprojects
├── src/main/kotlin/com/kitakkun/fusio/compiler/
│   ├── FusioClassIds.kt                             unchanged
│   ├── FusioErrors.kt                               unchanged (2.2+ only)
│   ├── FusioFirCheckerUtils.kt                      changes `symbol.fir` path to go through CompatContext
│   ├── FusioMapToChecker.kt                         calls CompatContext.kclassArg(...)
│   ├── FusioMapFromChecker.kt                       same
│   ├── FusioExhaustivenessChecker.kt                same
│   ├── FusioIrGenerationExtension.kt                resolves CompatContext from ServiceLoader at .generate()
│   └── MappedScopeTransformer.kt                   uses CompatContext for every IrCall.arguments / typeArguments / finderForBuiltins touchpoint
└── ...existing test-fixtures, test-gen, testData intact
```

Meanwhile:
- `fusio-compiler-plugin-k24/` module is **removed** — its purpose (building against 2.4) moves to `fusio-compiler-compat/k240_beta2/`.
- `fusio-compiler-plugin/src/main-k23/` is **removed** — its purpose moves to `fusio-compiler-compat/k2320/`.
- `FusioGradlePlugin.getPluginArtifact()` is simplified to return a single `fusio-compiler-plugin` coordinate. The per-Kotlin-version detection at Gradle time is replaced by runtime ServiceLoader resolution.

### `CompatContext` surface (first pass)

Based on what the current shared source actually needs:

```kotlin
package com.kitakkun.fusio.compiler.compat

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.name.Name

interface CompatContext {
    // FIR side
    fun FirAnnotation.kclassArg(name: Name, session: FirSession): ConeKotlinType?

    // IR side — writes (we don't need reads beyond arguments[i] in current code)
    fun IrCall.setArg(index: Int, expr: IrExpression)
    fun IrCall.setTypeArg(index: Int, type: org.jetbrains.kotlin.ir.types.IrType)

    interface Factory {
        /** Semantic version range this impl covers, e.g. "2.3.0 <= v < 2.4.0". */
        val supportedRange: VersionRange
        fun create(): CompatContext
    }
}
```

The IR-builder helpers in `MappedScopeTransformer` (`irBlock`, `irCall`, `irTemporary`, etc.) are stable across 2.2+; they stay as direct calls.

### Version resolution

`CompatContextResolver` loads all `CompatContext.Factory` instances via `ServiceLoader`, reads the running Kotlin compiler's version (via `KotlinCompilerVersion.VERSION` or similar stable constant), and picks the first factory whose `supportedRange` contains it. If none match, throw a clear error message at `FusioIrGenerationExtension.generate()` time pointing the user at the supported-Kotlin list in the README.

### Implementation order (suggested)

1. **Create the new `fusio-compiler-compat/` module** with `CompatContext` interface, `CompatContextResolver`, and a k2320 impl that mirrors the current Kotlin 2.3.20 behaviour.
2. **Port `MappedScopeTransformer` and the checkers** to call `CompatContext` for each Level-1/2 touchpoint. Keep `compat/FirAnnotationCompat.kt` working during the transition by delegating to `CompatContext`.
3. **Add the k240_beta2 impl**, likely `by k2320.CompatContextImpl()` with the single `kclassArg` override.
4. **Introduce the shadow plugin** on `fusio-compiler-plugin`'s build, relocate `fusio-compiler-compat:*` jars inside the main jar. Confirm `jar tf` on the result shows both `k2320/CompatContextImpl.class` and `k240_beta2/CompatContextImpl.class` without classpath conflicts.
5. **Remove `fusio-compiler-plugin-k24/` and `src/main-k23/`**. Delete `kotlin-k24` / `kotlin-compiler-embeddable-k24` catalog entries at the same time.
6. **Simplify `FusioGradlePlugin.getPluginArtifact()`** back to a single artifact; drop the reflection-based version detection.
7. **Smoke-test the sample** (still pinned to Kotlin 2.3.20 via its own build) — verifies ServiceLoader picks k2320.
8. **Extend `:fusio-compiler-plugin:test`** to run against both 2.3.20 and 2.4.0-Beta2 — ideally by varying the test framework's Kotlin version, but the Kotlin internal test framework pins one version per test JVM, so this likely becomes two test configurations or a matrix CI job.

### Out of scope for Phase 2 (defer to Phase 3 if needed)

- Android Studio / IntelliJ IDE-bundled Kotlin version aliases (Metro's `ide-mappings.txt` approach)
- Dev-track version resolution (`2.3.20-dev-NNNN` branches)
- Supporting Kotlin ≤ 2.1 (Level 4 context-parameter boundary)
- `generate-compat-module.sh` automation — worth it at ≥3 variants, premature at 2

### Risks and open questions

1. **Shading strategy**: Gradle shadow plugin works, but the shaded jar needs to preserve the `META-INF/services/CompatContext$Factory` files from every sub-module. Standard behaviour is "last wins" — we need the shadow plugin's service-file merger explicitly enabled.
2. **Kotlin-compiler-embeddable class loading**: all the shaded `CompatContextImpl` classes have references to Kotlin compiler classes at different versions. Only the running compiler's classes will actually be loadable; the other impls must NOT be triggered. This is ServiceLoader-safe as long as the Factory's `create()` is the ONLY thing that loads the impl class and all impl classes are independent (no cross-version refs). Needs testing.
3. **Shaded jar publishing**: currently `fusio-compiler-plugin` is published via `fusio.publish` convention. After shading, its published artifact should still be a valid Gradle-consumable jar; the convention plugin may need a tweak.
4. **Compose plugin version alignment is still external**: `fusio-compiler-plugin` being version-agnostic doesn't help with Compose, which is still one jar per Kotlin version. The Gradle plugin should keep injecting `-Xcompiler-plugin-order=com.kitakkun.fusio>androidx.compose.compiler.plugins.kotlin` regardless.
5. **Breakages we haven't seen yet**: the audit was based on 2.3→2.4 + 2.3→2.0 probes. A Kotlin 2.5 or 2.6 might introduce a Level 2/3 break that the current `CompatContext` surface doesn't cover — planned growth of the interface over time, mirroring Metro's trajectory.

### Starting point when resuming

1. Re-read this section plus the audit (higher up in this doc).
2. Clone Metro's `compiler-compat/` as reference: `github.com/ZacSweers/metro/tree/main/compiler-compat` — especially `CompatContext.kt`, any `k2320/CompatContextImpl.kt`, and the root `build.gradle.kts` showing the shading setup.
3. Implementation order above — step 1 (new module + k2320 impl) is the smallest landable unit that validates the pattern before migrating the rest.

## Problem

Fusio currently pins Kotlin 2.3.20 end-to-end:

- `kotlin` version in `gradle/libs.versions.toml` drives every catalog alias
- `fusio-compiler-plugin` imports internal IR / FIR types that move between Kotlin minor releases
- `kotlin-compose-compiler-plugin` is one jar per Kotlin version
- `kotlin-compiler-internal-test-framework` is one jar per Kotlin version

Users that can't bump their project to 2.3.20 (downstream dependency pinning, Android Studio compat, etc.) can't adopt Fusio at all. Once Kotlin 2.4.x stabilises we'll face the same cliff in reverse.

## Why the current code is version-sensitive

Audit of what in Fusio actually touches Kotlin-minor-version-shifting surface:

### IR transformer (MappedScopeTransformer)

| API used | First available | Older alternative |
|----------|-----------------|-------------------|
| `IrCall.arguments[i]` | Kotlin 2.2 (stabilised 2.3) | `call.putValueArgument(i, ...)`, `call.extensionReceiver = ...` (deprecated 2.3) |
| `IrCall.typeArguments[i]` | Kotlin 2.2 (stabilised 2.3) | `call.putTypeArgument(i, type)` |
| `@UnsafeDuringIrConstructionAPI` | Kotlin 2.2 | wasn't an opt-in before |
| `IrPluginContext.finderForBuiltins()` / `finderForSource()` | Kotlin 2.1 | `pluginContext.referenceClass(...)`, `referenceFunctions(...)` (deprecated) |
| `IrCall.symbol.owner.parent.kotlinFqName` | stable | stable |
| `irGetObject`, `irCall`, `irBlock`, `irTemporary` etc. | stable with minor deprecations | stable |

### FIR checkers (FusioMapToChecker, FusioMapFromChecker, FusioExhaustivenessChecker)

| API used | Notes |
|----------|-------|
| `context(context: CheckerContext, reporter: DiagnosticReporter) fun check(...)` | Context parameters for checkers stabilised in 2.2. Requires `-Xcontext-parameters`. Kotlin 2.0 used explicit params. |
| `getAnnotationByClassId(classId, session)` | Stable since 2.0 |
| `getKClassArgument(name, session)` | Overload with explicit session; some versions have an implicit-session overload |
| `getSealedClassInheritors(session)` | Stable |
| `FirClassChecker(MppCheckerKind.Common)` | `MppCheckerKind` added 2.0 |
| `KtDiagnosticsContainer` / `BaseDiagnosticRendererFactory` / `by KtDiagnosticFactoryToRendererMap("name") { ... }` | API shifted between 2.1 and 2.2 |
| `KtElement` vs `PsiElement` for diagnostic factory type param | Stable FQN across versions, already using it |

### Gradle side

| Piece | Version sensitivity |
|-------|---------------------|
| `KotlinCompilerPluginSupportPlugin` | Part of `kotlin-gradle-plugin-api`, relatively stable |
| `-Xcompiler-plugin-order=...` | Added 2.1; auto-injected by our Gradle plugin |
| `compilerOptions.freeCompilerArgs.add(...)` | Stable |

### Test infrastructure

| Piece | Version sensitivity |
|-------|---------------------|
| `AbstractFirLightTreeBlackBoxCodegenTest` | Parent `AbstractJvmBlackBoxCodegenTestBase` became generic between 2.2 and 2.3 |
| `AdditionalSourceProvider`, `EnvironmentConfigurator` | Stable |
| `androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar` | Per-Kotlin-version jar |
| `// RUN_PIPELINE_TILL: FRONTEND` directive | New-ish directive, verified working on 2.3 |

**Bottom line:** the biggest rifts are the **IR call-argument API** (arguments[] vs putValueArgument) and the **test framework class hierarchy**. Everything else either stays stable or has a thin compatibility-shim shape.

## How other plugins handle this

Brief notes from observation / prior art:

### JetBrains-shipped plugins (kotlinx-serialization, compose)

Single implementation; always ships with the current Kotlin release. Users upgrading Kotlin upgrade the plugin atomically. Zero multi-version logic in the plugin itself.

**Applicable to Fusio?** Not directly — we're not shipping with Kotlin. But the "per-Kotlin-version artifact" output model is the same goal.

### Arrow Meta / older third-party plugins

Historically built compatibility facades over multiple K1 versions. The facades were painful to maintain; most abandoned the approach when K2 landed.

### Compose compiler (pre-2.0, as third-party)

Published one jar per Kotlin version: `kotlin-compose-compiler-plugin:<kotlin-version>`. Users pinned `:1.9.10`, `:1.9.20`, etc. The Gradle plugin detected the project's Kotlin version and resolved the matching artifact.

### kotlinx-atomicfu

Ships a single plugin jar against the latest Kotlin. Doesn't attempt multi-version support. Breaks when Kotlin changes internal APIs. Accepted trade-off.

### Anvil (Square)

Multi-module split: core logic in a common module, per-Kotlin-version thin shim modules. The common module exposes only stable APIs.

## Options for Fusio

### A. One plugin jar per Kotlin minor — "Compose-style fan-out"

Publish:

```
com.kitakkun.fusio:fusio-compiler-plugin-kotlin-2.3:0.1.0
com.kitakkun.fusio:fusio-compiler-plugin-kotlin-2.4:0.1.0
com.kitakkun.fusio:fusio-compiler-plugin-kotlin-2.5:0.1.0
```

Each targets a specific Kotlin minor. Runtime / annotations modules remain single-artifact (no compiler API used there). Gradle plugin detects the user's Kotlin version and resolves the matching compiler-plugin artifact as `SubpluginArtifact`.

**Pros**
- Each jar is simple — uses the natural APIs of its target Kotlin version
- No runtime branching, no reflection
- Standard pattern (Compose pre-2.0 worked exactly this way)

**Cons**
- Must publish N jars per release
- CI matrix multiplies
- Source duplication risk unless kept disciplined

### B. Single plugin jar with runtime dispatch via reflection

One jar; at plugin init, inspects available Kotlin compiler classes and picks an implementation.

**Pros**
- One artifact
- Users never think about version pinning

**Cons**
- Reflection against internal Kotlin APIs is brittle — class/method signatures change between minors, so even reflection breaks
- Hard to test (test framework pins one Kotlin version at a time anyway)
- Performance non-issue but code clarity suffers

**Verdict**: not worth it. Kotlin's compiler API isn't stable enough to paper over reliably.

### C. Shared-core + per-version-adapter modules

```
fusio-compiler-plugin-core/        // shared FIR checker logic, compiler-version-agnostic
fusio-compiler-plugin-adapters/
  k23/                            // Kotlin 2.3 adapter, implements facade interfaces
  k24/                            // Kotlin 2.4 adapter
```

Core module declares a small facade (say `IrBuilderAdapter`) and per-version modules provide the real implementation.

**Pros**
- Less source duplication than A
- Core IR-shape / @MapTo walking logic lives once

**Cons**
- Facade design is the whole engineering cost — it has to capture every shifting API
- Fusio's compiler plugin surface is **too small** to justify a facade layer (~500 LoC total). The facade would be half the codebase.
- Adapter modules still need to be tested per Kotlin version, so the matrix doesn't shrink much

### D. "Latest only" — formalise the current policy

Explicitly document: Fusio tracks the latest stable Kotlin. Each Fusio version targets exactly one Kotlin version. Users on older Kotlin use older Fusio.

**Pros**
- Zero extra engineering
- Matches what JetBrains-shipped plugins do

**Cons**
- Adopters stuck on older Kotlin cannot use Fusio at all
- No story for "my project is 2.3, the new Fusio is 2.5-only"

## Recommended approach: **A, staged**

Adopt Strategy A, but phased — don't build the fan-out until we actually need to support a second Kotlin version. Concretely:

**Phase 0 (now):** formalise Strategy D in docs. One Fusio version = one Kotlin version. Publish compatibility table in README. Lowers user confusion while we stay single-version.

**Phase 1 (when Kotlin 2.4.0 stabilises and we want to keep 2.3.20 support):** introduce Strategy A. Adds a second compiler-plugin artifact. The Gradle plugin auto-picks.

Deferring the build matrix until we have >1 active version keeps YAGNI-compliant. But structure Phase 0 so Phase 1 is straightforward to turn on.

## Phase 0 implementation plan (land now)

### 1. Isolate version-sensitive code behind a clear boundary

Pull Kotlin-compiler-API touchpoints into a narrow set of functions named so the Phase 1 split is obvious:

```
fusio-compiler-plugin/
  src/main/kotlin/com/kitakkun/fusio/compiler/
    compat/                         NEW — future split point
      IrCallCompat.kt               val IrCall.extReceiver; fun IrCall.setArg(i, e); ...
      FirAnnotationCompat.kt        fun FirAnnotation.kclassArgOf(name: String, session): ConeKotlinType?
    FusioMapToChecker.kt             (uses compat helpers)
    MappedScopeTransformer.kt       (uses compat helpers)
```

Today those compat helpers are trivial 1-liners delegating to the Kotlin 2.3 APIs. When we add Kotlin 2.4 or 2.2 targets, only the `compat/` directory gets the `src-k24/` / `src-k22/` override.

### 2. Document the compatibility policy

Add a section to README:

```
### Kotlin version compatibility

| Fusio version | Kotlin version |
|--------------|----------------|
| 0.1.x        | 2.3.20 only    |
| 0.2.x        | TBD            |
```

And a line in CLAUDE.md.

### 3. CI compat probe

Add an optional CI job that tries to build against Kotlin 2.4.0-Beta (when it's out). Doesn't gate merges; surfaces expected breakage early.

### 4. Target-version source-set scaffolding (no-op today)

Set up `fusio-compiler-plugin`'s build such that the switch to per-version source-sets is one Gradle block away:

```kotlin
sourceSets {
    main {
        java.setSrcDirs(listOf(
            "src/main/kotlin",
            "src-${kotlinVersion}/kotlin",  // empty today
        ))
    }
}
```

With an empty `src-2.3.20/` directory today. Phase 1 populates it with version-specific overrides.

## Phase 1 implementation plan (when needed)

### 1. Per-Kotlin-version subprojects

```
fusio-compiler-plugin/                 shared build-logic, no source
fusio-compiler-plugin-k23/             targets Kotlin 2.3.x
  src/main/kotlin/                    (symlinked / copied common sources)
  src/main/kotlin/compat/k23/         version-specific overrides
fusio-compiler-plugin-k24/             targets Kotlin 2.4.x
  src/main/kotlin/
  src/main/kotlin/compat/k24/
```

Common sources sit in `fusio-compiler-plugin/common/` and are pulled in via `kotlin.sourceSets.main.srcDir("../common/kotlin")` from each subproject. The `compat/` directory differs per subproject.

### 2. Gradle plugin picks the right artifact

`FusioGradlePlugin.getPluginArtifact()` becomes dynamic:

```kotlin
override fun getPluginArtifact(): SubpluginArtifact {
    val kotlinVersion = project.getKotlinPluginVersion()
    val major = kotlinVersion.split(".").take(2).joinToString("")  // "2.3" -> "23"
    return SubpluginArtifact(
        groupId = "com.kitakkun.fusio",
        artifactId = "fusio-compiler-plugin-k$major",
        version = fusioVersion,
    )
}
```

If no matching artifact is published for the user's Kotlin version, fail fast with an actionable error.

### 3. Test matrix in CI

```yaml
strategy:
  matrix:
    kotlin: ['2.3.20', '2.4.0']
```

Each matrix cell runs `./gradlew :fusio-compiler-plugin-k${version}:test`. Box tests catch runtime regressions per version.

### 4. Convention-plugin-level version config

Add `fusio.kotlin-version` convention plugin that takes the target Kotlin version as a parameter:

```kotlin
plugins { id("fusio.kotlin-version") }
fusioKotlin { version.set("2.3.20") }
```

This centralises dep resolution across subprojects.

## Migration scenarios

### Scenario: Kotlin 2.4.0 stable lands, we keep 2.3 support

1. Create `fusio-compiler-plugin-k24` subproject targeting 2.4.0
2. Copy compat code from k23 as baseline; update for API drift
3. Run matrix CI; fix breaks
4. Release Fusio 0.2.0 with both `-k23` and `-k24` artifacts
5. Gradle plugin now picks either

### Scenario: Kotlin 2.3 EOL'd, drop support

1. Delete `fusio-compiler-plugin-k23` subproject
2. Remove k23 from matrix
3. Update compat table in README
4. Release Fusio 0.3.0

### Scenario: Kotlin 2.5 introduces a big IR API break

1. Create `fusio-compiler-plugin-k25`
2. May need to extract more functions into `compat/` if the shared core breaks
3. Common layer may shrink — that's fine

## Open questions

1. **How many versions to support?** Current stable + current-1 seems reasonable. JetBrains-shipped plugins support 1; Anvil historically did 3-4. Decide on release cadence when Phase 1 is triggered.

2. **Sample and tests — per-version or shared?** Probably one sample pinned to latest; box tests run matrix-style against each supported compiler-plugin variant.

3. **Compose plugin version alignment** — Compose's per-Kotlin-version jar means we can't mix "Fusio for Kotlin 2.3 + Compose for Kotlin 2.4". Each Fusio-Kotlin-pair implies a Compose-Kotlin-pair. Must document.

4. **Gradle plugin backwards-compat** — if a user has `id("com.kitakkun.fusio")` with Fusio 0.2 installed but the old 0.1 `SubpluginArtifact` resolution logic, do things work? Need to decide: ship a single Gradle plugin that always detects, or version the Gradle plugin alongside the compiler plugins?

5. **Artifact naming scheme** — `fusio-compiler-plugin-k23` vs `fusio-compiler-plugin-kotlin-2.3` vs putting the Kotlin version in the coordinate as a classifier. Classifier is cleanest but Gradle's `SubpluginArtifact` API doesn't easily express classifiers.

## References

- `reference_fusio_gotchas.md` (memory) — itemised list of which APIs we touch and why they're sensitive
- `project_fusio_current_state.md` (memory) — as-built shape; Phase 0 preserves it
- `docs/07-test-infrastructure-plan.md` — adjacent pattern of landed-status-per-item for test cases; this doc follows the same format
