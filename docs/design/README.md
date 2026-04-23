# Historical design docs

These are the design notes written before Fusio was implemented. They captured the target architecture, API shape, and Kotlin compiler API research that guided the first cut. They are **not** current documentation of the as-built system — the repo README and the code itself are.

Keep them for:

- Context on **why** a given design decision was made (especially the Compose-plugin-ordering, sealed-class exhaustiveness, and IR-transformer approach discussions)
- A paper trail of the API iterations (`fusio { }` block → `buildPresenter` DSL → `mappedScope`)
- Kotlin compiler API notes (FIR extension wiring, IR transformer entry points, `KtElement` vs `PsiElement` gotchas)

Where any of these docs describe things that later changed in implementation, assume the code is authoritative. Known divergences:

- Sub-presenters were originally going to return `Fusio<State, Effect>`; they now return just `State` (effects flow through `PresenterScope.emitEffect`)
- The screen-level type was renamed: `Fusio<State, Effect>` → `Presentation<State, Effect>`. Design docs still use the old name inside code samples.
- The fusion call was renamed: `mappedScope { ... }` → `fuse { ... }`. Design doc `05-ir-transformer.md` describes it under the old name and the transformer was called `MappedScopeTransformer`; it's now `FuseTransformer`.
- Plugin ordering was documented as a user configuration step; the Fusio Gradle plugin now injects `-Xcompiler-plugin-order=...` automatically
- Diagnostics `PsiElement` parameter type was switched to `KtElement` so the non-shaded kotlin-compiler classpath works in the test framework
- Multi-Kotlin-version support: the design docs predate the compat-module pattern entirely. See `../08-multi-compiler-version-plan.md` (in its "Phase 2 as-built" header) for how `:fusio-compiler-compat:{k230,k2320,k240_beta2}` dispatch actually works.

For current implementation status, see `../07-test-infrastructure-plan.md` (has landed-status markers per testData file) and the project README.
