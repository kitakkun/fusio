package com.kitakkun.aria.compiler.test.runners

import com.kitakkun.aria.compiler.test.services.AriaHeadlessRunnerSourceProvider
import com.kitakkun.aria.compiler.test.services.configureAriaPlugin
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.codegen.AbstractFirLightTreeBlackBoxCodegenTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

/**
 * Compiles each testData file with the Aria compiler plugin enabled, then runs
 * its `fun box(): String` and asserts the returned value is `"OK"`.
 *
 * This is the end-to-end acceptance lane for the IR transformer + FIR checkers
 * together — a bug that lets bytecode generation succeed but produces the wrong
 * runtime behaviour will be caught here but not by diagnostic tests.
 */
open class AbstractAriaJvmBoxTest : AbstractFirLightTreeBlackBoxCodegenTest() {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            defaultDirectives {
                +JvmEnvironmentConfigurationDirectives.FULL_JDK
                // compose-runtime and kotlinx-coroutines on the test classpath are
                // built against modern JDK bytecode; bump to 21 so their inline
                // functions can be inlined into the testData source.
                JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_21
                // Don't load R8 from the classpath — we only care about JVM bytecode here.
                +CodegenTestDirectives.IGNORE_DEXING
            }
            configureAriaPlugin()
            useAdditionalSourceProviders(::AriaHeadlessRunnerSourceProvider)
        }
    }
}
