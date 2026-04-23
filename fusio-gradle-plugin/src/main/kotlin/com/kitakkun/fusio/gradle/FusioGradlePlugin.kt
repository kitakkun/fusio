package com.kitakkun.fusio.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.util.Properties

class FusioGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // Auto-add runtime dependency. Version is baked from this plugin's own
        // build — consumers' `project.version` is unrelated to ours.
        target.afterEvaluate {
            target.dependencies.add(
                "commonMainImplementation",
                "com.kitakkun.fusio:fusio-runtime:$FUSIO_VERSION",
            )
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
        version = FUSIO_VERSION,
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

        /**
         * Read from a `version.properties` resource baked by the
         * `generateFusioVersionResource` Gradle task during the plugin's own
         * build. Coupling our plugin to its matching compiler-plugin /
         * runtime artifacts via this resource lets the Kotlin Gradle plugin
         * default (the Kotlin version itself) never leak into our resolution.
         */
        val FUSIO_VERSION: String = FusioGradlePlugin::class.java.classLoader
            .getResourceAsStream("com/kitakkun/fusio/gradle/version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?: error(
                "fusio-gradle-plugin: version.properties not found on classpath. " +
                    "The plugin jar was likely built without the generateFusioVersionResource task.",
            )
    }
}
