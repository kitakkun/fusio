package com.kitakkun.fusio.compiler.compat

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.util.ServiceLoader

/**
 * Picks the correct [CompatContext] for the Kotlin compiler currently running
 * this plugin.
 *
 * All registered [CompatContext.Factory] instances are discovered via
 * [ServiceLoader]; the Gradle shadow plugin is responsible for merging each
 * subproject's `META-INF/services/.../CompatContext$Factory` into the final
 * shaded jar (service-file merging must be enabled explicitly in the main
 * plugin's shadowJar config).
 *
 * At runtime only one Kotlin compiler is on the classpath, so only one
 * factory's [CompatContext.Factory.supportedRange] will match. Non-matching
 * impls stay dormant — their implementation classes are never loaded, so the
 * JVM never tries to link references to Kotlin compiler symbols that aren't
 * there.
 */
object CompatContextResolver {

    /**
     * Resolves the single [CompatContext] appropriate for this compiler run.
     *
     * Throws with an actionable message if no factory matches the running
     * Kotlin compiler version — most commonly because the user is on a Kotlin
     * release Fusio hasn't been cut against yet.
     */
    fun resolve(): CompatContext {
        val version = Version.parse(KotlinCompilerVersion.VERSION)
        val factories = ServiceLoader
            .load(CompatContext.Factory::class.java, CompatContext::class.java.classLoader)
            .toList()
        val matching = factories.firstOrNull { version in it.supportedRange }
            ?: error(buildNoMatchingFactoryMessage(version, factories))
        return matching.create()
    }

    private fun buildNoMatchingFactoryMessage(
        version: Version,
        factories: List<CompatContext.Factory>,
    ): String = buildString {
        append("Fusio: no CompatContext implementation covers Kotlin $version (compiler reports ")
        append(KotlinCompilerVersion.VERSION)
        append("). ")
        if (factories.isEmpty()) {
            append(
                "No CompatContext.Factory was discovered on the classpath at all — this usually " +
                    "means the fusio-compiler-plugin jar was repackaged without merging " +
                    "META-INF/services entries.",
            )
        } else {
            append("Supported ranges: ")
            append(factories.joinToString { it.supportedRange.toString() })
            append(". ")
            append(
                "Either downgrade your Kotlin version, or upgrade Fusio to a release that " +
                    "declares support for this Kotlin version.",
            )
        }
    }
}
