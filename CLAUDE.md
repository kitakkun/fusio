# Aria — Claude's project map

Kotlin compiler plugin + runtime that decomposes fat Composable Presenters. Users write `mappedScope { subPresenter() }` and `@MapTo` / `@MapFrom` annotations; the plugin rewrites the call site into event/effect plumbing at IR time.

## Start here (in order)

1. `README.md` — the user-facing overview and example
2. `docs/07-test-infrastructure-plan.md` — has landed-status rows for every testData case
3. `docs/design/` — **archived** pre-impl design docs (may diverge from code)

Persistent notes that survive across sessions live in `~/.claude/projects/-Users-kitakkun-Documents-GitHub-aria/memory/` — see `MEMORY.md` there for the index. Prefer those for API-shape and gotcha lookups.

## Modules

| Module | Role |
|---|---|
| `aria-annotations` | `@MapTo`, `@MapFrom` (pure KMP) |
| `aria-runtime` | `Aria`, `PresenterScope`, `buildPresenter`, `on<>`, `mappedScope` (inline stub), `mapEvents`, `forwardEffects` |
| `aria-compiler-plugin` | FIR checkers + IR `MappedScopeTransformer`; shades `aria-compiler-compat` and every `kXXX` impl into a single jar |
| `aria-compiler-compat/` | `CompatContext` interface + `CompatContextResolver` (ServiceLoader); `kXXX/` subprojects hold per-Kotlin-version impls |
| `aria-gradle-plugin` | Auto-injects `-Xcompiler-plugin-order` so Aria runs before Compose |
| `sample/` | Composite-build, headless Compose runner smoke test |
| `build-logic/` | `aria.publish` convention plugin |

## Common commands

```
./gradlew build                                  # compiles + runs all tests
./gradlew :aria-compiler-plugin:test             # diagnostics + box tests (13/13)
./gradlew :aria-runtime:jvmTest                  # runtime unit tests (6/6)
cd sample && ../gradlew runJvm                   # end-to-end smoke
./gradlew :aria-compiler-plugin:test -Pkotlin.test.update.test.data=true  # auto-update expected diagnostic markers
./gradlew publishToMavenLocal                    # seed ~/.m2
```

## Rules of thumb

- Sub-presenters return `State`, **not** `Aria<State, Effect>`. Effects flow through `emitEffect`.
- `mappedScope` is `inline` (not `@Composable`); inline carries caller's Composable context.
- Diagnostic types use `org.jetbrains.kotlin.psi.KtElement`, not IntelliJ `PsiElement` (shading mismatch between embeddable / non-embeddable kotlin-compiler).
- IR transformer registers BEFORE Compose (insertion order in `ExtensionStorage`).
- When touching compiler-plugin internals, read `reference_aria_gotchas.md` in memory first.

## Multi-Kotlin-version support

Single shaded `aria-compiler-plugin` jar covers every supported Kotlin version. Per-Kotlin-version logic lives in `aria-compiler-compat/`:

- `aria-compiler-compat/` — `CompatContext` interface + `CompatContextResolver` (ServiceLoader)
- `aria-compiler-compat/k2320/` — Kotlin 2.3.x impl
- `aria-compiler-compat/k240_beta2/` — Kotlin 2.4.0-Beta2 impl (delegates to k2320 except `kclassArg`, which lost its `session` parameter in 2.4)

Shadow plugin in `aria-compiler-plugin/build.gradle.kts` bundles all three jars and merges their `META-INF/services/…/CompatContext$Factory` files. `CompatContextResolver.resolve()` picks the matching factory via `KotlinCompilerVersion.VERSION` at plugin init.

Adding Kotlin 2.5 support: create `aria-compiler-compat/k250/` mirroring `k240_beta2/`, declare its `supportedRange`, add it to the `shaded` configuration in `aria-compiler-plugin/build.gradle.kts`. See `docs/08-multi-compiler-version-plan.md` for the full outline.