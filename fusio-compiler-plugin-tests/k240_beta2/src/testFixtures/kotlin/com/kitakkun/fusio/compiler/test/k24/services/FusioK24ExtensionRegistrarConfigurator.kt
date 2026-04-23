package com.kitakkun.fusio.compiler.test.k24.services

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.kitakkun.fusio.compiler.FusioFirExtensionRegistrar
import com.kitakkun.fusio.compiler.FusioIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.builders.NonGroupingPhaseTestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun NonGroupingPhaseTestConfigurationBuilder.configureFusioPlugin() {
    useConfigurators(::FusioK24ExtensionRegistrarConfigurator)
    configureFusioAnnotationsAndRuntime()
}

/**
 * Same ordering contract as the primary-lane configurator: Fusio's IR
 * extension registered first, then Compose's. Duplicated in the k24 test
 * module because the `configure(...)` signature that hosts this builder
 * takes [NonGroupingPhaseTestConfigurationBuilder] instead of the
 * single-phase `TestConfigurationBuilder` that 2.3 ships.
 */
@OptIn(ExperimentalCompilerApi::class)
private class FusioK24ExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        FirExtensionRegistrarAdapter.registerExtension(FusioFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(FusioIrGenerationExtension())

        with(ComposePluginRegistrar()) {
            registerExtensions(configuration)
        }
    }
}
