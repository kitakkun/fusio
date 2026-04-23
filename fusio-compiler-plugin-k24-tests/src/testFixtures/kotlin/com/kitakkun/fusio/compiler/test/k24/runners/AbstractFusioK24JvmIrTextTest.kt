package com.kitakkun.fusio.compiler.test.k24.runners

import com.kitakkun.fusio.compiler.test.k24.services.FusioK24HeadlessRunnerSourceProvider
import com.kitakkun.fusio.compiler.test.k24.services.configureFusioPlugin
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.NonGroupingPhaseTestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.ir.AbstractFirLightTreeJvmIrTextTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

/**
 * Kotlin 2.4.0-Beta2 IR text lane. Uses its own testData under
 * `fusio-compiler-plugin-k24-tests/testData/ir/` with .kt files that
 * mirror the primary lane + version-specific `.fir.ir.txt` /
 * `.fir.kt.txt` goldens. Compiler patch releases regenerate the IR dump
 * format; keeping goldens pinned to a compiler version is what makes the
 * snapshot a meaningful regression guard instead of a shifting target.
 *
 * Regenerate with:
 *   ./gradlew :fusio-compiler-plugin-k24-tests:test -Pkotlin.test.update.test.data=true
 */
open class AbstractFusioK24JvmIrTextTest : AbstractFirLightTreeJvmIrTextTest() {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: NonGroupingPhaseTestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            defaultDirectives {
                +JvmEnvironmentConfigurationDirectives.FULL_JDK
                JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_21
                +CodegenTestDirectives.IGNORE_DEXING
                +CodegenTestDirectives.SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK
            }
            configureFusioPlugin()
            useAdditionalSourceProviders(::FusioK24HeadlessRunnerSourceProvider)
        }
    }
}
