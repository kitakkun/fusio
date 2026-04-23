package com.kitakkun.aria.compiler

import com.kitakkun.aria.compiler.compat.CompatContextResolver
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class AriaCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.kitakkun.aria"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(AriaConfigurationKeys.ENABLED, true).not()) return

        // Direct FirExtensionRegistrarAdapter.registerExtension / IrGenerationExtension
        // .registerExtension calls would bind to the 2.3 bytecode shape (ProjectExtension-
        // Descriptor), which NoSuchMethodError's under Kotlin 2.4 (ExtensionPointDescriptor
        // rename). Route through CompatContext so each k** impl's bytecode targets the
        // right signature for its Kotlin version.
        val compat = CompatContextResolver.resolve()
        with(compat) {
            registerFirExtension(AriaFirExtensionRegistrar())
            registerIrGenerationExtension(AriaIrGenerationExtension())
        }
    }
}
