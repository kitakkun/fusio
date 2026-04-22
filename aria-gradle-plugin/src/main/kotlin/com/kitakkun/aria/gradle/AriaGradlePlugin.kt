package com.kitakkun.aria.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class AriaGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        // Auto-add runtime dependency.
        target.afterEvaluate {
            target.dependencies.add("commonMainImplementation", "com.kitakkun.aria:aria-runtime:${target.version}")
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = ARIA_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.kitakkun.aria",
        artifactId = "aria-compiler-plugin",
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

    private companion object {
        const val ARIA_PLUGIN_ID = "com.kitakkun.aria"
        const val COMPOSE_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin"
    }
}
