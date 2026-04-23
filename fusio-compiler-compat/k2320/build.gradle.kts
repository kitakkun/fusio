/**
 * Kotlin 2.3.x implementation of [com.kitakkun.fusio.compiler.compat.CompatContext].
 *
 * Compiled against `kotlin-compiler-embeddable:2.3.0` — the oldest version
 * the [CompatContext.Factory.supportedRange] declares it handles. Using the
 * oldest surface guarantees the emitted bytecode only references members
 * that exist in every 2.3.x release (2.3.0 / 2.3.10 / 2.3.20 / …), so the
 * shaded jar runs against any of them. Building against 2.3.20 would let
 * the compiler silently pick up members added in later patches and break
 * downstream consumers still on 2.3.0.
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
    compileOnly(libs.kotlin.compiler.embeddable.k23)
}
