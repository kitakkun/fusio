/**
 * Kotlin 2.4.0-Beta2 implementation of [com.kitakkun.fusio.compiler.compat.CompatContext].
 *
 * Compiled against `kotlin-compiler-embeddable:2.4.0-Beta2`. Delegates every
 * method except the ones whose API shape actually shifted between 2.3.20 and
 * 2.4.0-Beta2 to the `:k2320` impl.
 *
 * Not published on its own — shaded into fusio-compiler-plugin at release time.
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
    api(project(":fusio-compiler-compat"))
    // Delegation target — we want class-level visibility into k2320.CompatContextImpl
    // so only the diverging method(s) need concrete overrides here.
    implementation(project(":fusio-compiler-compat:k2320"))
    compileOnly(libs.kotlin.compiler.embeddable.k24)
}
