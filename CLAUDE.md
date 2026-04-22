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
| `aria-compiler-plugin` | FIR checkers + IR `MappedScopeTransformer` |
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

## Current focus

Multi-compiler-version support scaffolding — pending; this project currently pins Kotlin 2.3.20. Design to be drafted.