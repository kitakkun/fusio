# Fusio — Claude's project map

Kotlin compiler plugin + runtime that decomposes fat Composable Presenters. Users write `fuse { subPresenter() }` and `@MapTo` / `@MapFrom` annotations; the plugin rewrites the call site into event/effect plumbing at IR time.

## Start here (in order)

1. `README.md` — the user-facing overview and example
2. `docs/07-test-infrastructure-plan.md` — has landed-status rows for every testData case
3. `docs/design/` — **archived** pre-impl design docs (may diverge from code)

Persistent notes that survive across sessions live in `~/.claude/projects/-Users-kitakkun-Documents-GitHub-fusio/memory/` — see `MEMORY.md` there for the index. Prefer those for API-shape and gotcha lookups.

## Modules

| Module | Role |
|---|---|
| `fusio-annotations` | `@MapTo`, `@MapFrom` (KMP: jvm, iosArm64/SimArm64, macosArm64, js(IR), wasmJs) |
| `fusio-runtime` | `Presentation`, `PresenterScope`, `buildPresenter`, `on<>`, `fuse` (inline stub), `mapEvents`, `forwardEffects` — same KMP targets |
| `fusio-test` | `testPresenter` / `testSubPresenter` headless Compose runner + `PresenterScenario` DSL (send / awaitState / awaitEffect / expectNoEffects). Same KMP targets as `fusio-runtime` |
| `fusio-compiler-plugin` | FIR checkers + IR `FuseTransformer`; shades `fusio-compiler-compat` and every `kXXX` impl into a single jar (JVM only) |
| `fusio-compiler-plugin-tests/` | Per-Kotlin-patch test lanes — one subproject per supported compiler (`k230`, `k2310`, `tests-k2320`, `k2321`, `tests-k240_beta2`). Shared box + diagnostics testData lives at `fusio-compiler-plugin-tests/testData/`; IR goldens are per-lane |
| `fusio-compiler-compat/` | `CompatContext` interface + `CompatContextResolver` (ServiceLoader); `kXXX/` subprojects hold per-Kotlin-version impls |
| `fusio-gradle-plugin` | Auto-injects `-Xcompiler-plugin-order` so Fusio runs before Compose |
| `demo/` | Composite-build Compose Desktop app demonstrating the full Fusio pipeline end-to-end (parent presenter, mapped sub-presenter, nested fuse, @MapTo/@MapFrom round-trip) |
| `build-logic/` | `fusio.publish` convention plugin |

Build: Gradle 9.3.0, shadow 9.4.1, Kotlin 2.3.21 (+ 2.4.0-Beta2 via smokeK24). Configuration cache is on by default in both root and sample — cold incremental runs are ~800 ms.

## Common commands

```
./gradlew build                                            # compiles + runs all tests (7 platforms × commonTest)
./gradlew :fusio-compiler-plugin-tests:k2321:test           # primary Kotlin 2.3.21 lane — diagnostics + box + IR
./gradlew :fusio-compiler-plugin-tests:tests-k240_beta2:test # Kotlin 2.4.0-Beta2 lane (own IR goldens)
./gradlew :fusio-compiler-plugin-tests:k230:test            # Kotlin 2.3.0 — box + diagnostics via :compat:k230
./gradlew :fusio-runtime:allTests                           # runtime tests on every KMP target
./gradlew :fusio-runtime:jvmTest                            # JVM only — fastest feedback
./gradlew :fusio-test:allTests                              # self-tests for the presenter testing harness
./gradlew :demo:jvmTest                                     # demo's own tests (TaskList / Filter / MyScreen) — doubles as fusio-test showcase
cd demo && ../gradlew runJvm                               # launch Compose Desktop demo
./gradlew :fusio-compiler-plugin-tests:k2321:test -Pkotlin.test.update.test.data=true  # auto-update diagnostic markers
./gradlew publishToMavenLocal                              # seed ~/.m2 (umbrella + included fusio-gradle-plugin in one shot)
```

mavenLocal is content-filtered to `com.kitakkun.fusio` in settings so external KMP deps always resolve from mavenCentral with proper `.module` metadata. Don't remove that filter — doing so will silently pin kotlinx-coroutines-core to its JVM variant in commonMain compile.

## Rules of thumb

- Sub-presenters return `State`, **not** `Presentation<State, Effect, Event>`. Effects flow through `emitEffect`.
- `Presentation<State, Effect, Event>` carries `send: (Event) -> Unit` (Step 11). `buildPresenter { … }` owns the internal event flow; UI calls `presentation.send(...)`. No external `MutableSharedFlow` to plumb.
- `fuse` is `inline` (not `@Composable`); inline carries caller's Composable context.
- Diagnostic types use `org.jetbrains.kotlin.psi.KtElement`, not IntelliJ `PsiElement` (shading mismatch between embeddable / non-embeddable kotlin-compiler).
- IR transformer registers BEFORE Compose (insertion order in `ExtensionStorage`).
- When touching compiler-plugin internals, check `~/.claude/projects/-Users-kitakkun-Documents-GitHub-fusio/memory/MEMORY.md` for "tried, didn't work" entries (currently: the 2.3.x testFixtures consolidation footgun).

## Multi-Kotlin-version support

Single shaded `fusio-compiler-plugin` jar covers every supported Kotlin version. Per-Kotlin-version logic lives in `fusio-compiler-compat/`:

- `fusio-compiler-compat/` — `CompatContext` interface + `CompatContextResolver` (ServiceLoader)
- `fusio-compiler-compat/k230/` — Kotlin 2.3.0 – 2.3.19 impl (legacy `referenceClass` / `referenceFunctions` for the finder trio; same bytecode as k2320 for everything else)
- `fusio-compiler-compat/k2320/` — Kotlin 2.3.20 – 2.3.x impl (uses `finderForBuiltins()` / `DeclarationFinder`)
- `fusio-compiler-compat/k240_beta2/` — Kotlin 2.4.0-Beta2 impl (delegates to k2320 except `kclassArg`, which lost its `session` parameter in 2.4)

Shadow plugin in `fusio-compiler-plugin/build.gradle.kts` bundles all four jars and merges their `META-INF/services/…/CompatContext$Factory` files. `CompatContextResolver.resolve()` picks the matching factory via `KotlinCompilerVersion.VERSION` at plugin init.

Adding Kotlin 2.5 support: create `fusio-compiler-compat/k250/` mirroring `k240_beta2/`, declare its `supportedRange`, add it to the `shaded` configuration in `fusio-compiler-plugin/build.gradle.kts`, and add the new patch versions to `fusio-compiler-compat/supported-kotlin-versions.txt` (single source of truth for the gradle plugin's apply-time version warning). See `docs/08-multi-compiler-version-plan.md` for the full outline.