/**
 * Kotlin 2.4.0-Beta2 variant of the Aria compiler plugin.
 *
 * Shares all production source with aria-compiler-plugin (the Kotlin 2.3.20
 * variant) via `srcDir("../aria-compiler-plugin/src/main/kotlin")`. Same
 * goes for META-INF service registrations. Only the `compileOnly` Kotlin
 * compiler dependency differs — it points at 2.4.0-Beta2.
 *
 * When a code path requires version-specific handling we put the k24-only
 * override under `src/main/kotlin/compat/`; k23 has a symmetric override
 * location (empty today). The compiler picks the local `compat/` over the
 * shared tree because the Kotlin resolver sees the sources together and
 * direct package duplication is a compile error — so any divergence is
 * intentional, not accidental.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("aria.publish")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
}

sourceSets {
    main {
        // Pull in production sources from the Kotlin 2.3 variant. Same bytecode
        // target, different compiler classpath — if the code doesn't rely on
        // any API that shifted between 2.3 and 2.4, it compiles against both.
        kotlin.srcDir("../aria-compiler-plugin/src/main/kotlin")
        resources.srcDir("../aria-compiler-plugin/src/main/resources")
    }
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable.k24)
}
