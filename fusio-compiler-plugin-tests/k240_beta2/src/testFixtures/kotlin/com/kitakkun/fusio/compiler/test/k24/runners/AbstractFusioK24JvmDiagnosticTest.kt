package com.kitakkun.fusio.compiler.test.k24.runners

import com.kitakkun.fusio.compiler.test.k24.services.configureFusioPlugin
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.NonGroupingPhaseTestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractFusioK24JvmDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider = EnvironmentBasedStandardLibrariesPathProvider

    override fun configure(builder: NonGroupingPhaseTestConfigurationBuilder) = with(builder) {
        super.configure(builder)
        defaultDirectives {
            +JvmEnvironmentConfigurationDirectives.FULL_JDK
            // Compose runtime ships JVM 21 bytecode; without raising the test
            // target inline `@Composable` calls (e.g. `on<>`) trip
            // INLINE_FROM_HIGHER_PLATFORM during diagnostic runs.
            JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_21
        }
        configureFusioPlugin()
    }
}
