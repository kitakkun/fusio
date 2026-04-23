package com.kitakkun.fusio.compiler.test.services

import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.targetPlatform
import java.io.File

/**
 * Puts fusio-annotations and fusio-runtime jars on the compiler classpath of each test
 * module so testData sources can import com.kitakkun.fusio.* directly.
 *
 * The jars are passed in via the "fusioRuntime.classpath" system property which is set
 * in each test lane's own build.gradle.kts from a `testArtifacts` configuration.
 */
fun TestConfigurationBuilder.configureFusioAnnotationsAndRuntime() {
    useConfigurators(::FusioRuntimeEnvironmentConfigurator)
    useCustomRuntimeClasspathProviders(::FusioRuntimeClasspathProvider)
}

private class FusioRuntimeEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        if (module.targetPlatform(testServices).isJvm()) {
            configuration.addJvmClasspathRoots(fusioRuntimeClasspath)
        }
    }
}

private class FusioRuntimeClasspathProvider(testServices: TestServices) : RuntimeClasspathProvider(testServices) {
    override fun runtimeClassPaths(module: TestModule): List<File> =
        if (module.targetPlatform(testServices).isJvm()) fusioRuntimeClasspath else emptyList()
}

private val fusioRuntimeClasspath: List<File> = run {
    val property = System.getProperty("fusioRuntime.classpath")
        ?: error("System property 'fusioRuntime.classpath' is not set; check fusio-compiler-plugin/build.gradle.kts")
    property.split(File.pathSeparator).map(::File)
}
