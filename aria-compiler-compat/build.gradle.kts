/**
 * Host module for Aria's Kotlin-compiler-version compatibility layer.
 *
 * This module exposes only the [CompatContext] interface + [CompatContextResolver].
 * Per-Kotlin-version implementations live in sibling subprojects (e.g. `:k2320`,
 * `:k240_beta2`) and are ServiceLoader-registered via META-INF/services entries.
 *
 * At release time the `aria-compiler-plugin` build shades this module and every
 * k** implementation into its jar, so end users install a single artifact. The
 * resolver picks the right impl for whatever Kotlin compiler is actually
 * running, and classloading keeps the non-matching impls inert.
 *
 * NOT published as a standalone artifact — see aria-compiler-plugin's shadowJar
 * configuration.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

dependencies {
    // Stable compiler APIs only — anything version-sensitive belongs in a k**
    // subproject, not here. Compiled against the catalog's default Kotlin jar
    // purely to see the `org.jetbrains.kotlin.*` type names.
    compileOnly(libs.kotlin.compiler.embeddable)
}
