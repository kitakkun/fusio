package com.kitakkun.aria.compiler.test.services

import com.kitakkun.aria.compiler.AriaFirExtensionRegistrar
import com.kitakkun.aria.compiler.AriaIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configureAriaPlugin() {
    useConfigurators(::AriaExtensionRegistrarConfigurator)
    configureAriaAnnotationsAndRuntime()
}

private class AriaExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        FirExtensionRegistrarAdapter.registerExtension(AriaFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(AriaIrGenerationExtension())
    }
}
