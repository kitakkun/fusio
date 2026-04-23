/**
 * Kotlin 2.3.20 – 2.3.x implementation of
 * [com.kitakkun.fusio.compiler.compat.CompatContext].
 *
 * Compiled against `kotlin-compiler-embeddable:2.3.20` — the oldest version
 * this impl targets. 2.3.20 is the first release that shipped
 * `IrPluginContext.finderForBuiltins()` / `DeclarationFinder`, which
 * [CompatContext.findClass] etc. route through. Older 2.3 patches
 * (2.3.0 / 2.3.10 / 2.3.19) are handled by the sibling `:k230` impl using
 * the legacy `referenceClass` / `referenceFunctions` APIs.
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
    compileOnly(libs.kotlin.compiler.embeddable.k2320)
}
