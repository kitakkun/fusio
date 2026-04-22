package com.kitakkun.aria.compiler.test.services

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
 * Puts aria-annotations and aria-runtime jars on the compiler classpath of each test
 * module so testData sources can import com.kitakkun.aria.* directly.
 *
 * The jars are passed in via the "ariaRuntime.classpath" system property which is set
 * in aria-compiler-plugin/build.gradle.kts from the `testArtifacts` configuration.
 */
fun TestConfigurationBuilder.configureAriaAnnotationsAndRuntime() {
    useConfigurators(::AriaRuntimeEnvironmentConfigurator)
    useCustomRuntimeClasspathProviders(::AriaRuntimeClasspathProvider)
}

private class AriaRuntimeEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        if (module.targetPlatform(testServices).isJvm()) {
            configuration.addJvmClasspathRoots(ariaRuntimeClasspath)
        }
    }
}

private class AriaRuntimeClasspathProvider(testServices: TestServices) : RuntimeClasspathProvider(testServices) {
    override fun runtimeClassPaths(module: TestModule): List<File> =
        if (module.targetPlatform(testServices).isJvm()) ariaRuntimeClasspath else emptyList()
}

private val ariaRuntimeClasspath: List<File> = run {
    val property = System.getProperty("ariaRuntime.classpath")
        ?: error("System property 'ariaRuntime.classpath' is not set; check aria-compiler-plugin/build.gradle.kts")
    property.split(File.pathSeparator).map(::File)
}
