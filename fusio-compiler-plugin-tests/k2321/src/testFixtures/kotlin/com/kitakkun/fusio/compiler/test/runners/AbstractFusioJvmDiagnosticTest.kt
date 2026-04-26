package com.kitakkun.fusio.compiler.test.runners

import com.kitakkun.fusio.compiler.test.services.configureFusioPlugin
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractFusioJvmDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
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
