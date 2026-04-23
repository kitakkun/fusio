/**
 * Kotlin 2.3.0 – 2.3.19 implementation of
 * [com.kitakkun.fusio.compiler.compat.CompatContext].
 *
 * Compiled against `kotlin-compiler-embeddable:2.3.0` — the oldest version
 * this impl targets. The single reason this module exists separately from
 * `:k2320` is that `IrPluginContext.finderForBuiltins()` + `DeclarationFinder`
 * were added in 2.3.20; on 2.3.0 / 2.3.10 the shaded plugin jar link-fails
 * with `NoClassDefFoundError: …/DeclarationFinder` if it references them.
 *
 * Every other method here is identical to `:k2320`'s Kotlin source — the
 * APIs used (`getKClassArgument(name, session)`, `arguments[i]`,
 * `typeArguments[i]`, `FirExtensionRegistrarAdapter`, `IrGenerationExtension.
 * registerExtension`) are stable across the whole 2.3.x range. What differs
 * is the bytecode linked against this module's 2.3.0 compiler-embeddable jar
 * and the fallback to legacy `pluginContext.referenceClass` /
 * `referenceConstructors` / `referenceFunctions` for the finder trio.
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
