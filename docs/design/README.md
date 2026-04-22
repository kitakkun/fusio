# Historical design docs

These are the design notes written before Aria was implemented. They captured the target architecture, API shape, and Kotlin compiler API research that guided the first cut. They are **not** current documentation of the as-built system — the repo README and the code itself are.

Keep them for:

- Context on **why** a given design decision was made (especially the Compose-plugin-ordering, sealed-class exhaustiveness, and IR-transformer approach discussions)
- A paper trail of the API iterations (`aria { }` block → `buildPresenter` DSL → `mappedScope`)
- Kotlin compiler API notes (FIR extension wiring, IR transformer entry points, `KtElement` vs `PsiElement` gotchas)

Where any of these docs describe things that later changed in implementation, assume the code is authoritative. Known divergences:

- Sub-presenters were originally going to return `Aria<State, Effect>`; they now return just `State` (effects flow through `PresenterScope.emitEffect`)
- Plugin ordering was documented as a user configuration step; the Aria Gradle plugin now injects `-Xcompiler-plugin-order=...` automatically
- Diagnostics `PsiElement` parameter type was switched to `KtElement` so the non-shaded kotlin-compiler classpath works in the test framework

For current implementation status, see `../07-test-infrastructure-plan.md` (has landed-status markers per testData file) and the project README.
