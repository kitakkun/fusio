# Step 8: Multi-Kotlin-compiler-version support (Design)

Status: **design doc, not yet implemented**

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
