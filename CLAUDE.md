# Fusio — Claude's project map

Kotlin compiler plugin + runtime that decomposes fat Composable Presenters. Users write `mappedScope { subPresenter() }` and `@MapTo` / `@MapFrom` annotations; the plugin rewrites the call site into event/effect plumbing at IR time.

## Start here (in order)

1. `README.md` — the user-facing overview and example
2. `docs/07-test-infrastructure-plan.md` — has landed-status rows for every testData case
3. `docs/design/` — **archived** pre-impl design docs (may diverge from code)

Persistent notes that survive across sessions live in `~/.claude/projects/-Users-kitakkun-Documents-GitHub-fusio/memory/` — see `MEMORY.md` there for the index. Prefer those for API-shape and gotcha lookups.

## Modules

| Module | Role |
|---|---|
| `fusio-annotations` | `@MapTo`, `@MapFrom` (KMP: jvm, iosArm64/SimArm64, macosArm64, js(IR), wasmJs) |
| `fusio-runtime` | `Fusio`, `PresenterScope`, `buildPresenter`, `on<>`, `mappedScope` (inline stub), `mapEvents`, `forwardEffects` — same KMP targets |
| `fusio-compiler-plugin` | FIR checkers + IR `MappedScopeTransformer`; shades `fusio-compiler-compat` and every `kXXX` impl into a single jar (JVM only) |
| `fusio-compiler-compat/` | `CompatContext` interface + `CompatContextResolver` (ServiceLoader); `kXXX/` subprojects hold per-Kotlin-version impls |
| `fusio-gradle-plugin` | Auto-injects `-Xcompiler-plugin-order` so Fusio runs before Compose |
| `demo/` | Composite-build Compose Desktop app demonstrating the full Fusio pipeline end-to-end (parent presenter, mapped sub-presenter, nested mappedScope, @MapTo/@MapFrom round-trip) |
| `build-logic/` | `fusio.publish` convention plugin |

Build: Gradle 9.3.0, shadow 9.4.1, Kotlin 2.3.20 (+ 2.4.0-Beta2 via smokeK24). Configuration cache is on by default in both root and sample — cold incremental runs are ~800 ms.

## Common commands

```
./gradlew build                                  # compiles + runs all tests (7 platforms × commonTest)
./gradlew :fusio-compiler-plugin:test             # diagnostics + box tests (13/13)
./gradlew :fusio-runtime:allTests                 # runtime tests on every KMP target
./gradlew :fusio-runtime:jvmTest                  # JVM only — fastest feedback
cd demo && ../gradlew runJvm                     # launch Compose Desktop demo
./gradlew :fusio-compiler-plugin:test -Pkotlin.test.update.test.data=true  # auto-update expected diagnostic markers
./gradlew publishToMavenLocal                    # seed ~/.m2
```

mavenLocal is content-filtered to `com.kitakkun.fusio` in settings so external KMP deps always resolve from mavenCentral with proper `.module` metadata. Don't remove that filter — doing so will silently pin kotlinx-coroutines-core to its JVM variant in commonMain compile.

## Rules of thumb

- Sub-presenters return `State`, **not** `Fusio<State, Effect>`. Effects flow through `emitEffect`.
- `mappedScope` is `inline` (not `@Composable`); inline carries caller's Composable context.
- Diagnostic types use `org.jetbrains.kotlin.psi.KtElement`, not IntelliJ `PsiElement` (shading mismatch between embeddable / non-embeddable kotlin-compiler).
- IR transformer registers BEFORE Compose (insertion order in `ExtensionStorage`).
- When touching compiler-plugin internals, read `reference_fusio_gotchas.md` in memory first.

## Multi-Kotlin-version support

Single shaded `fusio-compiler-plugin` jar covers every supported Kotlin version. Per-Kotlin-version logic lives in `fusio-compiler-compat/`:

- `fusio-compiler-compat/` — `CompatContext` interface + `CompatContextResolver` (ServiceLoader)
- `fusio-compiler-compat/k2320/` — Kotlin 2.3.x impl
- `fusio-compiler-compat/k240_beta2/` — Kotlin 2.4.0-Beta2 impl (delegates to k2320 except `kclassArg`, which lost its `session` parameter in 2.4)

Shadow plugin in `fusio-compiler-plugin/build.gradle.kts` bundles all three jars and merges their `META-INF/services/…/CompatContext$Factory` files. `CompatContextResolver.resolve()` picks the matching factory via `KotlinCompilerVersion.VERSION` at plugin init.

Adding Kotlin 2.5 support: create `fusio-compiler-compat/k250/` mirroring `k240_beta2/`, declare its `supportedRange`, add it to the `shaded` configuration in `fusio-compiler-plugin/build.gradle.kts`. See `docs/08-multi-compiler-version-plan.md` for the full outline.