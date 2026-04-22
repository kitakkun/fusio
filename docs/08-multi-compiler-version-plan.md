# Step 8: Multi-Kotlin-compiler-version support

Status: **Phase 1 landed** (Kotlin 2.3.20 + 2.4.0-Beta2 both build); CI matrix and per-variant box tests pending.

## What landed

- `aria-compiler-plugin-k24/` sibling module sharing production source via `srcDir("../aria-compiler-plugin/src/main/kotlin")`, compiled against kotlin-compiler-embeddable 2.4.0-Beta2
- `aria-compiler-plugin/src/main-k23/kotlin/compat/` and `aria-compiler-plugin-k24/src/main/kotlin/compat/` — per-version implementations of `FirAnnotation.kclassArg(name, session)`. Only one API break surfaced: `getKClassArgument` dropped its session parameter in 2.4
- `AriaGradlePlugin.getPluginArtifact()` now selects `aria-compiler-plugin` for Kotlin 2.3.x and `aria-compiler-plugin-k24` for Kotlin 2.4.x, detected via the Kotlin Gradle plugin jar's Implementation-Version

Published artifacts (via `publishToMavenLocal`):
- `com.kitakkun.aria:aria-compiler-plugin:<aria-version>` — k23 default
- `com.kitakkun.aria:aria-compiler-plugin-k24:<aria-version>`

## Still to land (Phase 1 tail)

- **CI matrix**: run `:aria-compiler-plugin-k24:compileKotlin` alongside k23 build so drift surfaces on PR
- **Box tests for k24**: `kotlin-compiler-internal-test-framework` pins a single Kotlin version, so each variant needs its own test module; the current box-test lane exercises only k23
- **Version-detection fallback**: reflection reads `package.implementationVersion`; brittle against re-jarring. Consider `KotlinPluginWrapperKt.getKotlinPluginVersion(project)` once we can take that API dep

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
| `KtDiagnosticsContainer` | `AriaErrors` extends it | 2.0's diagnostic API was entirely different (pre-`KtDiagnosticFactoryToRendererMap` factory DSL). The whole `AriaErrors` file is unworkable as-is. |
| `DirectDeclarationsAccess` annotation | `@OptIn(DirectDeclarationsAccess::class)` in `AriaFirCheckerUtils` | Pre-existing opt-in wasn't there; remove annotation for 2.0 |
| Factory function `by KtDiagnosticFactoryToRendererMap("name") { ... }` | `AriaErrors` / `AriaErrorMessages` | Different builder shape in 2.0 |

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

Of our ~500 LoC in `aria-compiler-plugin/src/main/kotlin/`:

- `AriaErrors.kt` (31 lines) — full rewrite for 2.0 (Level 2)
- `AriaMapToChecker.kt` / `AriaMapFromChecker.kt` / `AriaExhaustivenessChecker.kt` (~200 lines combined) — fork for check() signature (Level 4)
- `AriaCompilerPluginRegistrar.kt` / `AriaFirExtensionRegistrar.kt` / `AriaFirCheckersExtension.kt` (~50 lines) — adjust for Level 3 changes
- `AriaFirCheckerUtils.kt` (30 lines) — compat-fixable if we strip `DirectDeclarationsAccess`
- `MappedScopeTransformer.kt` (375 lines) — many Level 1 breaks but fixable with wider compat layer

So **~300 of 500 lines need per-version content** for a 2.0 variant. The current layout with a single `compat/` file per variant can't carry this — we'd need file-level forks and a wider compat surface.

### What the probe tells us about the design

1. **The current compat-facade pattern covers Level 1 cleanly, nothing above.** That's why 2.3 ↔ 2.4 was a 20-line fix and 2.3 ↔ 2.0 is effectively a rewrite.

2. **Override-signature changes (Level 4) are non-bridgeable** by any facade. They require whole-class forks.

3. **There is a soft boundary around context-parameter checker adoption (Kotlin 2.2)** below which every FIR checker must be duplicated.

4. **Realistic support window** is probably "last 1-2 minors that all sit above the Level 4 boundary." For the current codebase: 2.3 and 2.4 (both use context-parameter checkers). 2.2 might be reachable with a wider compat layer; 2.1 and below are out without a full fork.

5. **Dedicated compat module needed if we want more.** A separate `aria-compiler-compat/` sub-layer that each variant module depends on — housing `IrCall.setArgument(i)`, `IrPluginContext.findClassSymbol(classId)`, and similar — would at least halve the per-variant surface area. Plan that in Phase 2.

### Action for now

k20 probe is not merged. Neither the `aria-compiler-plugin-k20/` module nor the `kotlin-k20` catalog entry remained — this document is the paper trail. When someone re-attempts older-version support, start here.

## Problem

Aria currently pins Kotlin 2.3.20 end-to-end:

- `kotlin` version in `gradle/libs.versions.toml` drives every catalog alias
- `aria-compiler-plugin` imports internal IR / FIR types that move between Kotlin minor releases
- `kotlin-compose-compiler-plugin` is one jar per Kotlin version
- `kotlin-compiler-internal-test-framework` is one jar per Kotlin version

Users that can't bump their project to 2.3.20 (downstream dependency pinning, Android Studio compat, etc.) can't adopt Aria at all. Once Kotlin 2.4.x stabilises we'll face the same cliff in reverse.

## Why the current code is version-sensitive

Audit of what in Aria actually touches Kotlin-minor-version-shifting surface:

### IR transformer (MappedScopeTransformer)

| API used | First available | Older alternative |
|----------|-----------------|-------------------|
| `IrCall.arguments[i]` | Kotlin 2.2 (stabilised 2.3) | `call.putValueArgument(i, ...)`, `call.extensionReceiver = ...` (deprecated 2.3) |
| `IrCall.typeArguments[i]` | Kotlin 2.2 (stabilised 2.3) | `call.putTypeArgument(i, type)` |
| `@UnsafeDuringIrConstructionAPI` | Kotlin 2.2 | wasn't an opt-in before |
| `IrPluginContext.finderForBuiltins()` / `finderForSource()` | Kotlin 2.1 | `pluginContext.referenceClass(...)`, `referenceFunctions(...)` (deprecated) |
| `IrCall.symbol.owner.parent.kotlinFqName` | stable | stable |
| `irGetObject`, `irCall`, `irBlock`, `irTemporary` etc. | stable with minor deprecations | stable |

### FIR checkers (AriaMapToChecker, AriaMapFromChecker, AriaExhaustivenessChecker)

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

**Applicable to Aria?** Not directly — we're not shipping with Kotlin. But the "per-Kotlin-version artifact" output model is the same goal.

### Arrow Meta / older third-party plugins

Historically built compatibility facades over multiple K1 versions. The facades were painful to maintain; most abandoned the approach when K2 landed.

### Compose compiler (pre-2.0, as third-party)

Published one jar per Kotlin version: `kotlin-compose-compiler-plugin:<kotlin-version>`. Users pinned `:1.9.10`, `:1.9.20`, etc. The Gradle plugin detected the project's Kotlin version and resolved the matching artifact.

### kotlinx-atomicfu

Ships a single plugin jar against the latest Kotlin. Doesn't attempt multi-version support. Breaks when Kotlin changes internal APIs. Accepted trade-off.

### Anvil (Square)

Multi-module split: core logic in a common module, per-Kotlin-version thin shim modules. The common module exposes only stable APIs.

## Options for Aria

### A. One plugin jar per Kotlin minor — "Compose-style fan-out"

Publish:

```
com.kitakkun.aria:aria-compiler-plugin-kotlin-2.3:0.1.0
com.kitakkun.aria:aria-compiler-plugin-kotlin-2.4:0.1.0
com.kitakkun.aria:aria-compiler-plugin-kotlin-2.5:0.1.0
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
aria-compiler-plugin-core/        // shared FIR checker logic, compiler-version-agnostic
aria-compiler-plugin-adapters/
  k23/                            // Kotlin 2.3 adapter, implements facade interfaces
  k24/                            // Kotlin 2.4 adapter
```

Core module declares a small facade (say `IrBuilderAdapter`) and per-version modules provide the real implementation.

**Pros**
- Less source duplication than A
- Core IR-shape / @MapTo walking logic lives once

**Cons**
- Facade design is the whole engineering cost — it has to capture every shifting API
- Aria's compiler plugin surface is **too small** to justify a facade layer (~500 LoC total). The facade would be half the codebase.
- Adapter modules still need to be tested per Kotlin version, so the matrix doesn't shrink much

### D. "Latest only" — formalise the current policy

Explicitly document: Aria tracks the latest stable Kotlin. Each Aria version targets exactly one Kotlin version. Users on older Kotlin use older Aria.

**Pros**
- Zero extra engineering
- Matches what JetBrains-shipped plugins do

**Cons**
- Adopters stuck on older Kotlin cannot use Aria at all
- No story for "my project is 2.3, the new Aria is 2.5-only"

## Recommended approach: **A, staged**

Adopt Strategy A, but phased — don't build the fan-out until we actually need to support a second Kotlin version. Concretely:

**Phase 0 (now):** formalise Strategy D in docs. One Aria version = one Kotlin version. Publish compatibility table in README. Lowers user confusion while we stay single-version.

**Phase 1 (when Kotlin 2.4.0 stabilises and we want to keep 2.3.20 support):** introduce Strategy A. Adds a second compiler-plugin artifact. The Gradle plugin auto-picks.

Deferring the build matrix until we have >1 active version keeps YAGNI-compliant. But structure Phase 0 so Phase 1 is straightforward to turn on.

## Phase 0 implementation plan (land now)

### 1. Isolate version-sensitive code behind a clear boundary

Pull Kotlin-compiler-API touchpoints into a narrow set of functions named so the Phase 1 split is obvious:

```
aria-compiler-plugin/
  src/main/kotlin/com/kitakkun/aria/compiler/
    compat/                         NEW — future split point
      IrCallCompat.kt               val IrCall.extReceiver; fun IrCall.setArg(i, e); ...
      FirAnnotationCompat.kt        fun FirAnnotation.kclassArgOf(name: String, session): ConeKotlinType?
    AriaMapToChecker.kt             (uses compat helpers)
    MappedScopeTransformer.kt       (uses compat helpers)
```

Today those compat helpers are trivial 1-liners delegating to the Kotlin 2.3 APIs. When we add Kotlin 2.4 or 2.2 targets, only the `compat/` directory gets the `src-k24/` / `src-k22/` override.

### 2. Document the compatibility policy

Add a section to README:

```
### Kotlin version compatibility

| Aria version | Kotlin version |
|--------------|----------------|
| 0.1.x        | 2.3.20 only    |
| 0.2.x        | TBD            |
```

And a line in CLAUDE.md.

### 3. CI compat probe

Add an optional CI job that tries to build against Kotlin 2.4.0-Beta (when it's out). Doesn't gate merges; surfaces expected breakage early.

### 4. Target-version source-set scaffolding (no-op today)

Set up `aria-compiler-plugin`'s build such that the switch to per-version source-sets is one Gradle block away:

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
aria-compiler-plugin/                 shared build-logic, no source
aria-compiler-plugin-k23/             targets Kotlin 2.3.x
  src/main/kotlin/                    (symlinked / copied common sources)
  src/main/kotlin/compat/k23/         version-specific overrides
aria-compiler-plugin-k24/             targets Kotlin 2.4.x
  src/main/kotlin/
  src/main/kotlin/compat/k24/
```

Common sources sit in `aria-compiler-plugin/common/` and are pulled in via `kotlin.sourceSets.main.srcDir("../common/kotlin")` from each subproject. The `compat/` directory differs per subproject.

### 2. Gradle plugin picks the right artifact

`AriaGradlePlugin.getPluginArtifact()` becomes dynamic:

```kotlin
override fun getPluginArtifact(): SubpluginArtifact {
    val kotlinVersion = project.getKotlinPluginVersion()
    val major = kotlinVersion.split(".").take(2).joinToString("")  // "2.3" -> "23"
    return SubpluginArtifact(
        groupId = "com.kitakkun.aria",
        artifactId = "aria-compiler-plugin-k$major",
        version = ariaVersion,
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

Each matrix cell runs `./gradlew :aria-compiler-plugin-k${version}:test`. Box tests catch runtime regressions per version.

### 4. Convention-plugin-level version config

Add `aria.kotlin-version` convention plugin that takes the target Kotlin version as a parameter:

```kotlin
plugins { id("aria.kotlin-version") }
ariaKotlin { version.set("2.3.20") }
```

This centralises dep resolution across subprojects.

## Migration scenarios

### Scenario: Kotlin 2.4.0 stable lands, we keep 2.3 support

1. Create `aria-compiler-plugin-k24` subproject targeting 2.4.0
2. Copy compat code from k23 as baseline; update for API drift
3. Run matrix CI; fix breaks
4. Release Aria 0.2.0 with both `-k23` and `-k24` artifacts
5. Gradle plugin now picks either

### Scenario: Kotlin 2.3 EOL'd, drop support

1. Delete `aria-compiler-plugin-k23` subproject
2. Remove k23 from matrix
3. Update compat table in README
4. Release Aria 0.3.0

### Scenario: Kotlin 2.5 introduces a big IR API break

1. Create `aria-compiler-plugin-k25`
2. May need to extract more functions into `compat/` if the shared core breaks
3. Common layer may shrink — that's fine

## Open questions

1. **How many versions to support?** Current stable + current-1 seems reasonable. JetBrains-shipped plugins support 1; Anvil historically did 3-4. Decide on release cadence when Phase 1 is triggered.

2. **Sample and tests — per-version or shared?** Probably one sample pinned to latest; box tests run matrix-style against each supported compiler-plugin variant.

3. **Compose plugin version alignment** — Compose's per-Kotlin-version jar means we can't mix "Aria for Kotlin 2.3 + Compose for Kotlin 2.4". Each Aria-Kotlin-pair implies a Compose-Kotlin-pair. Must document.

4. **Gradle plugin backwards-compat** — if a user has `id("com.kitakkun.aria")` with Aria 0.2 installed but the old 0.1 `SubpluginArtifact` resolution logic, do things work? Need to decide: ship a single Gradle plugin that always detects, or version the Gradle plugin alongside the compiler plugins?

5. **Artifact naming scheme** — `aria-compiler-plugin-k23` vs `aria-compiler-plugin-kotlin-2.3` vs putting the Kotlin version in the coordinate as a classifier. Classifier is cleanest but Gradle's `SubpluginArtifact` API doesn't easily express classifiers.

## References

- `reference_aria_gotchas.md` (memory) — itemised list of which APIs we touch and why they're sensitive
- `project_aria_current_state.md` (memory) — as-built shape; Phase 0 preserves it
- `docs/07-test-infrastructure-plan.md` — adjacent pattern of landed-status-per-item for test cases; this doc follows the same format
