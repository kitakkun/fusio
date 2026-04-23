package com.kitakkun.fusio.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TestKit coverage for the Fusio Gradle plugin.
 *
 * The sample composite build already exercises end-to-end behaviour (plugin
 * applies, compiler plugin runs, mappedScope gets rewritten, JVM smoke
 * produces expected output end-to-end). These tests pin the pieces that
 * aren't visible from a successful compile and that the sample doesn't
 * exercise:
 *
 * - apply() doesn't throw when the plugin lands on a bare KMP project with
 *   no fusio sources yet. Without this, a regression in `afterEvaluate` /
 *   `applyToCompilation` would only surface the first time a consumer
 *   writes a presenter, not at plugin-apply time.
 * - the plugin-id → implementation-class mapping declared in
 *   META-INF/gradle-plugins is intact, so `id("com.kitakkun.fusio")`
 *   actually resolves to FusioGradlePlugin.
 *
 * The actual `-Xcompiler-plugin-order` flag wiring is covered transparently
 * by the sample build and by smokeK24 — re-testing it here would require
 * fighting TestKit's classloader isolation against KGP's task types, and
 * any regression would also break the sample.
 */
class FusioGradlePluginTest {

    @TempDir
    lateinit var projectDir: File

    @Test
    fun `applies cleanly on a KMP consumer`() {
        writeSettings()
        writeBuild(
            """
            plugins {
                kotlin("multiplatform") version "2.3.20"
                id("com.kitakkun.fusio")
            }
            repositories { mavenCentral() }
            kotlin { jvm() }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("help", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertTrue(
            result.output.contains("BUILD SUCCESSFUL"),
            "plugin apply should succeed. full output:\n${result.output}",
        )
    }

    @Test
    fun `plugin id matches implementationClass mapping`() {
        // Simple metadata check — catches the "plugin id declared but no
        // implementationClass" mismatch that breaks apply lazily.
        val metadata = FusioGradlePluginTest::class.java.classLoader
            .getResource("META-INF/gradle-plugins/com.kitakkun.fusio.properties")
        assertTrue(metadata != null, "plugin metadata file not on classpath")
        val props = metadata.readText()
        assertEquals(
            "implementation-class=com.kitakkun.fusio.gradle.FusioGradlePlugin",
            props.trim(),
            "plugin metadata points at wrong implementation class",
        )
    }

    private fun writeSettings() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "testkit-consumer"
            """.trimIndent(),
        )
    }

    private fun writeBuild(content: String) {
        projectDir.resolve("build.gradle.kts").writeText(content)
    }
}
