package com.github.kitakkun.aria.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
class AriaCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.github.kitakkun.aria"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(AriaConfigurationKeys.ENABLED, true).not()) return

        FirExtensionRegistrarAdapter.registerExtension(AriaFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(AriaIrGenerationExtension())
    }
}
