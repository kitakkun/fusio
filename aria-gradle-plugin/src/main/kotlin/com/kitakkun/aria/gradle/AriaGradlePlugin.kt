package com.kitakkun.aria.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class AriaGradlePlugin : KotlinCompilerPluginSupportPlugin {

    /** Captured at [apply] time so [getPluginArtifact] can pick a per-Kotlin-version jar. */
    private var detectedKotlinVersion: String? = null

    override fun apply(target: Project) {
        detectedKotlinVersion = detectKotlinVersion(target)

        // Auto-add runtime dependency.
        target.afterEvaluate {
            target.dependencies.add("commonMainImplementation", "com.kitakkun.aria:aria-runtime:${target.version}")
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = ARIA_PLUGIN_ID

    /**
     * Resolves to the compiler-plugin artifact matching the user's Kotlin minor.
     *
     * Kotlin 2.3.x uses the canonical `aria-compiler-plugin`.
     * Kotlin 2.4.x uses `aria-compiler-plugin-k24` — compiled against Kotlin
     * 2.4.0-Beta2 with compat shims for the [`getKClassArgument`] signature
     * change (session param dropped).
     *
     * Unknown versions fall back to the canonical artifact; if the user's
     * compiler is incompatible they'll see a clear class-loading error at
     * compile time rather than a silent no-op.
     */
    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.kitakkun.aria",
        artifactId = artifactIdFor(detectedKotlinVersion),
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        // Ensure Aria's IR transformer runs BEFORE Compose's so `mappedScope` is
        // rewritten before Compose injects $composer/$changed params into
        // @Composable lambdas. Registration order inside the Kotlin compiler is
        // determined by this CLI flag when both plugins are present.
        kotlinCompilation.compilerOptions.options.freeCompilerArgs.add(
            "-Xcompiler-plugin-order=$ARIA_PLUGIN_ID>$COMPOSE_PLUGIN_ID",
        )

        return kotlinCompilation.target.project.provider {
            listOf(SubpluginOption("enabled", "true"))
        }
    }

    /**
     * Extract the Kotlin plugin version from the target project without
     * depending on the private `getKotlinPluginVersion()` API. The Kotlin
     * Gradle plugin always registers its version under the project's
     * extra-properties; failing that we fall through to null and the
     * artifact-selection logic defaults safely.
     */
    private fun detectKotlinVersion(target: Project): String? {
        val kotlinPlugin = target.plugins.findPlugin("org.jetbrains.kotlin.jvm")
            ?: target.plugins.findPlugin("org.jetbrains.kotlin.multiplatform")
            ?: target.plugins.findPlugin("org.jetbrains.kotlin.android")
            ?: return null
        // The plugin class is loaded from the kotlin-gradle-plugin jar; its
        // package's Implementation-Version manifest field is the Kotlin
        // release. Reflection is awkward but there's no stable public getter.
        return kotlinPlugin.javaClass.`package`?.implementationVersion
    }

    private companion object {
        const val ARIA_PLUGIN_ID = "com.kitakkun.aria"
        const val COMPOSE_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin"

        fun artifactIdFor(kotlinVersion: String?): String = when {
            kotlinVersion == null -> "aria-compiler-plugin"
            kotlinVersion.startsWith("2.4.") -> "aria-compiler-plugin-k24"
            else -> "aria-compiler-plugin"  // k23 default; covers 2.3.x
        }
    }
}
