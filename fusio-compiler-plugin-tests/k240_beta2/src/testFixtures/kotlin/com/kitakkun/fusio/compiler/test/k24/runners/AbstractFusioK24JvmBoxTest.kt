package com.kitakkun.fusio.compiler.test.k24.runners

import com.kitakkun.fusio.compiler.test.k24.services.FusioK24HeadlessRunnerSourceProvider
import com.kitakkun.fusio.compiler.test.k24.services.configureFusioPlugin
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.NonGroupingPhaseTestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.codegen.AbstractFirLightTreeBlackBoxCodegenTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

/**
 * Kotlin 2.4.0-Beta2 box-test lane. Exact same behaviour as
 * [com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmBoxTest] in the
 * primary 2.3 module; we just re-declare it here because
 * `AbstractKotlinCompilerTest.configure` changed its parameter type from
 * `TestConfigurationBuilder` (2.3) to `NonGroupingPhaseTestConfigurationBuilder`
 * (2.4) and Kotlin won't let one class override both signatures.
 */
open class AbstractFusioK24JvmBoxTest : AbstractFirLightTreeBlackBoxCodegenTest() {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider = EnvironmentBasedStandardLibrariesPathProvider

    override fun configure(builder: NonGroupingPhaseTestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            defaultDirectives {
                +JvmEnvironmentConfigurationDirectives.FULL_JDK
                JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_21
                +CodegenTestDirectives.IGNORE_DEXING
            }
            configureFusioPlugin()
            useAdditionalSourceProviders(::FusioK24HeadlessRunnerSourceProvider)
        }
    }
}
