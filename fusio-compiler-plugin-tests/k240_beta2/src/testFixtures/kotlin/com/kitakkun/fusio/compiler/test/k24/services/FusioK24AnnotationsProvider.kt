package com.kitakkun.fusio.compiler.test.k24.services

import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.test.builders.NonGroupingPhaseTestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.targetPlatform
import java.io.File

/**
 * K24 variant of the classpath injector. See the primary-lane version in
 * `fusio-compiler-plugin`'s test-fixtures for the rationale; this file
 * exists only because its receiver moved from `TestConfigurationBuilder`
 * to `NonGroupingPhaseTestConfigurationBuilder` in Kotlin 2.4.
 *
 * The "fusioRuntime.classpath" system property is populated from the k24
 * module's own `testArtifacts` configuration (see build.gradle.kts), which
 * pins every Kotlin runtime jar to 2.4.0-Beta2.
 */
fun NonGroupingPhaseTestConfigurationBuilder.configureFusioAnnotationsAndRuntime() {
    useConfigurators(::FusioK24RuntimeEnvironmentConfigurator)
    useCustomRuntimeClasspathProviders(::FusioK24RuntimeClasspathProvider)
}

private class FusioK24RuntimeEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        if (module.targetPlatform(testServices).isJvm()) {
            configuration.addJvmClasspathRoots(fusioRuntimeClasspath)
        }
    }
}

private class FusioK24RuntimeClasspathProvider(testServices: TestServices) : RuntimeClasspathProvider(testServices) {
    override fun runtimeClassPaths(module: TestModule): List<File> = if (module.targetPlatform(testServices).isJvm()) fusioRuntimeClasspath else emptyList()
}

private val fusioRuntimeClasspath: List<File> = run {
    val property = System.getProperty("fusioRuntime.classpath")
        ?: error("System property 'fusioRuntime.classpath' is not set; check fusio-compiler-plugin-tests/k240_beta2/build.gradle.kts")
    property.split(File.pathSeparator).map(::File)
}
