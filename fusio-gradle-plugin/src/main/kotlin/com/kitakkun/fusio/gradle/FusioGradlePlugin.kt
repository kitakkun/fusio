package com.kitakkun.fusio.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.kotlinToolingVersion
import java.util.Properties

class FusioGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // Register the `fusio { … }` DSL extension so consumers can override
        // defaults from their own build script. Defaults come from
        // [resolveSeverity] at applyToCompilation time so a consumer that
        // never touches the extension still gets sensible behaviour.
        target.extensions.create("fusio", FusioPluginExtension::class.java)

        // Auto-add runtime dependency. Version is baked from this plugin's
        // own build — consumers' `project.version` is unrelated to ours.
        //
        // The configuration name that accepts "main" code dependencies
        // depends on which Kotlin plugin flavour the consumer applied.
        // Each `withPlugin` callback fires when (and only when) the
        // matching plugin is applied, regardless of whether Fusio's plugin
        // is registered before or after it in the consumer's `plugins {}`
        // block. This avoids the `afterEvaluate` ordering trap of needing
        // every plugin to be present at evaluation time.
        val coordinate = "com.kitakkun.fusio:fusio-runtime:$FUSIO_VERSION"
        target.pluginManager.withPlugin(KMP_PLUGIN_ID) {
            // KMP projects (including the Android KMP library variant)
            // expose `commonMainImplementation` for shared-source deps.
            target.dependencies.add("commonMainImplementation", coordinate)
            warnIfKotlinUnsupported(target)
        }
        target.pluginManager.withPlugin(KOTLIN_JVM_PLUGIN_ID) {
            target.dependencies.add("implementation", coordinate)
            warnIfKotlinUnsupported(target)
        }
        target.pluginManager.withPlugin(KOTLIN_ANDROID_PLUGIN_ID) {
            target.dependencies.add("implementation", coordinate)
            warnIfKotlinUnsupported(target)
        }
    }

    /**
     * DSL value (if set) → Gradle property fallback → [EventHandlerExhaustiveSeverity.WARNING]
     * default. Property values are matched case-insensitively so the wire
     * names `error` / `warning` / `none` and the enum names `ERROR` /
     * `WARNING` / `NONE` both work.
     */
    private fun resolveSeverity(target: Project): EventHandlerExhaustiveSeverity {
        val ext = target.extensions.findByType(FusioPluginExtension::class.java)
        ext?.eventHandlerExhaustiveSeverity?.orNull?.let { return it }

        val raw = target.providers.gradleProperty(EVENT_HANDLER_EXHAUSTIVE_SEVERITY_PROPERTY)
            .orNull
            ?: return EventHandlerExhaustiveSeverity.WARNING

        return runCatching { EventHandlerExhaustiveSeverity.valueOf(raw.uppercase()) }
            .getOrElse {
                target.logger.warn(
                    "Fusio: ignoring invalid $EVENT_HANDLER_EXHAUSTIVE_SEVERITY_PROPERTY='$raw'; " +
                        "expected one of ${EventHandlerExhaustiveSeverity.values().joinToString { it.name.lowercase() }}.",
                )
                EventHandlerExhaustiveSeverity.WARNING
            }
    }

    /**
     * Surfaces an apply-time warning if the consumer's Kotlin compiler version
     * sits outside the range Fusio is tested against. The actual runtime
     * `CompatContextResolver` would still throw at compile time on an
     * unsupported version — this just shifts the diagnostic earlier so the
     * consumer sees it during gradle config rather than buried in a compile
     * stacktrace.
     *
     * Suppress with `-Pfusio.version.check=false` (or the matching env var)
     * when the consumer is intentionally running on a Kotlin version Fusio
     * hasn't been cut against yet.
     */
    private fun warnIfKotlinUnsupported(target: Project) {
        val checkEnabled = target.providers.gradleProperty(VERSION_CHECK_PROPERTY)
            .orNull
            ?.equals("false", ignoreCase = true)
            ?.not()
            ?: true
        if (!checkEnabled) return

        val consumer = readConsumerKotlinVersion(target) ?: return
        if (consumer in SUPPORTED_RANGE) return

        target.logger.warn(
            "Fusio $FUSIO_VERSION is tested against Kotlin ${SUPPORTED_RANGE.min}–${SUPPORTED_RANGE.max}; " +
                "consumer ${target.path} uses $consumer. Compilation may fail at the Fusio compiler " +
                "plugin's CompatContextResolver. Suppress this warning with " +
                "-P$VERSION_CHECK_PROPERTY=false.",
        )
    }

    /**
     * Reads the Kotlin compiler version the consumer is configured to use via
     * KGP's [kotlinToolingVersion] extension. Returns null when the version
     * isn't readable for any reason (KGP API absent, parse failure) — the
     * caller treats null as "skip the check" rather than failing the build.
     */
    private fun readConsumerKotlinVersion(target: Project): KotlinPatch? {
        val raw = runCatching { target.kotlinToolingVersion.toString() }.getOrNull() ?: return null
        return KotlinPatch.parse(raw)
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
        // Ensure Fusio's IR transformer runs BEFORE Compose's so `fuse` is
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

        val project = kotlinCompilation.target.project
        return project.provider {
            listOf(
                SubpluginOption("enabled", "true"),
                SubpluginOption(
                    "event-handler-exhaustive-severity",
                    resolveSeverity(project).name.lowercase(),
                ),
            )
        }
    }

    private companion object {
        const val FUSIO_PLUGIN_ID = "com.kitakkun.fusio"
        const val COMPOSE_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin"
        const val KMP_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
        const val KOTLIN_JVM_PLUGIN_ID = "org.jetbrains.kotlin.jvm"
        const val KOTLIN_ANDROID_PLUGIN_ID = "org.jetbrains.kotlin.android"

        /** Gradle property name that disables the apply-time Kotlin version warning. */
        const val VERSION_CHECK_PROPERTY = "fusio.version.check"

        /** Gradle property name that selects the event-handler-exhaustive severity. */
        const val EVENT_HANDLER_EXHAUSTIVE_SEVERITY_PROPERTY = "fusio.event-handler-exhaustive-severity"

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

        /**
         * Min..max Kotlin patch range Fusio is tested against, parsed from the
         * `supported-kotlin-versions.txt` resource baked by the plugin's own
         * build. Kept lazy so the resource read happens once, after the class
         * is first used by an apply().
         */
        val SUPPORTED_RANGE: KotlinPatchRange = readSupportedRange()

        private fun readSupportedRange(): KotlinPatchRange {
            val resource = FusioGradlePlugin::class.java.classLoader
                .getResourceAsStream("com/kitakkun/fusio/gradle/supported-kotlin-versions.txt")
                ?: error(
                    "fusio-gradle-plugin: supported-kotlin-versions.txt not found on classpath. " +
                        "The plugin jar was likely built without copySupportedKotlinVersions.",
                )
            val versions = resource.bufferedReader().useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { KotlinPatch.parse(it) }
                    .toList()
            }
            require(versions.isNotEmpty()) {
                "fusio-gradle-plugin: supported-kotlin-versions.txt parsed empty"
            }
            return KotlinPatchRange(versions.min(), versions.max())
        }
    }
}

/**
 * Parsed Kotlin patch version with pre-release qualifiers stripped to a numeric
 * `major.minor.patch` triple. `2.4.0-Beta2` → `2.4.0`. Comparable, so the
 * containing-range check is straight numeric.
 *
 * Internal to the gradle plugin; no relation to (or dependency on) the
 * `Version` type used inside `fusio-compiler-compat` at runtime.
 */
internal data class KotlinPatch(val major: Int, val minor: Int, val patch: Int) :
    Comparable<KotlinPatch> {
    override fun compareTo(other: KotlinPatch): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String): KotlinPatch? {
            val parts = raw.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }
            if (parts.size < 2) return null
            return KotlinPatch(parts[0], parts[1], parts.getOrElse(2) { 0 })
        }
    }
}

/** Inclusive Kotlin patch range used for the apply-time supported-version check. */
internal data class KotlinPatchRange(val min: KotlinPatch, val max: KotlinPatch) {
    operator fun contains(version: KotlinPatch): Boolean = version in min..max
}
