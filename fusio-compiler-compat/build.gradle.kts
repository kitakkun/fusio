/**
 * Host module for Fusio's Kotlin-compiler-version compatibility layer.
 *
 * This module exposes only the [CompatContext] interface + [CompatContextResolver].
 * Per-Kotlin-version implementations live in sibling subprojects — `:k230`
 * (2.3.0 – 2.3.19, legacy finder API), `:k2320` (2.3.20 – 2.3.x,
 * DeclarationFinder API), `:k240_beta2` (2.4.x) — and are ServiceLoader-
 * registered via META-INF/services entries.
 *
 * At release time the `fusio-compiler-plugin` build shades this module and every
 * k** implementation into its jar, so end users install a single artifact. The
 * resolver picks the right impl for whatever Kotlin compiler is actually
 * running, and classloading keeps the non-matching impls inert.
 *
 * NOT published as a standalone artifact — see fusio-compiler-plugin's shadowJar
 * configuration.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
}

dependencies {
    // Stable compiler APIs only — anything version-sensitive belongs in a k**
    // subproject, not here. Compiled against the catalog's default Kotlin jar
    // purely to see the `org.jetbrains.kotlin.*` type names.
    compileOnly(libs.kotlin.compiler.embeddable)
}
