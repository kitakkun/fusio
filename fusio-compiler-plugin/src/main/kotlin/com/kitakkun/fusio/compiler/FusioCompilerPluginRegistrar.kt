package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContextResolver
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class FusioCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.kitakkun.fusio"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(FusioConfigurationKeys.ENABLED, true).not()) return

        val severity = configuration.get(
            FusioConfigurationKeys.EVENT_HANDLER_EXHAUSTIVE_SEVERITY,
            EventHandlerExhaustiveSeverity.WARNING,
        )

        // Direct FirExtensionRegistrarAdapter.registerExtension / IrGenerationExtension
        // .registerExtension calls would bind to the 2.3 bytecode shape (ProjectExtension-
        // Descriptor), which NoSuchMethodError's under Kotlin 2.4 (ExtensionPointDescriptor
        // rename). Route through CompatContext so each k** impl's bytecode targets the
        // right signature for its Kotlin version.
        val compat = CompatContextResolver.resolve()
        with(compat) {
            registerFirExtension(FusioFirExtensionRegistrar(severity))
            registerIrGenerationExtension(FusioIrGenerationExtension())
        }
    }
}
