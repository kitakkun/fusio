package com.kitakkun.fusio.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class FusioGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // Auto-add runtime dependency.
        target.afterEvaluate {
            target.dependencies.add("commonMainImplementation", "com.kitakkun.fusio:fusio-runtime:${target.version}")
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = FUSIO_PLUGIN_ID

    /**
     * A single shaded `fusio-compiler-plugin` jar covers every supported Kotlin
     * version. At compile time the in-jar `CompatContextResolver` inspects the
     * running Kotlin compiler via `KotlinCompilerVersion.VERSION` and loads the
     * matching `CompatContext` impl via `ServiceLoader`. Per-Kotlin-minor
     * artifact fan-out is no longer needed on the Gradle side.
     */
    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.kitakkun.fusio",
        artifactId = "fusio-compiler-plugin",
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        // Ensure Fusio's IR transformer runs BEFORE Compose's so `mappedScope` is
        // rewritten before Compose injects $composer/$changed params into
        // @Composable lambdas. Registration order inside the Kotlin compiler is
        // determined by this CLI flag when both plugins are present.
        // Configure via compileTaskProvider — the KotlinCompilation.compilerOptions
        // shortcut is deprecated in KGP 2.3+.
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.compilerOptions.freeCompilerArgs.add(
                "-Xcompiler-plugin-order=$FUSIO_PLUGIN_ID>$COMPOSE_PLUGIN_ID",
            )
        }

        return kotlinCompilation.target.project.provider {
            listOf(SubpluginOption("enabled", "true"))
        }
    }

    private companion object {
        const val FUSIO_PLUGIN_ID = "com.kitakkun.fusio"
        const val COMPOSE_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin"
    }
}
