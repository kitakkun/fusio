/**
 * Kotlin 2.3.x implementation of [com.kitakkun.aria.compiler.compat.CompatContext].
 *
 * Compiled against `kotlin-compiler-embeddable:2.3.20` so the types on the
 * call sites below resolve to that version's shapes (session-taking
 * `getKClassArgument`, list-access `IrCall.arguments`).
 *
 * Not published on its own — shaded into aria-compiler-plugin at release time.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
}

dependencies {
    api(project(":aria-compiler-compat"))
    compileOnly(libs.kotlin.compiler.embeddable) // pinned to 2.3.20 in libs.versions.toml
}
