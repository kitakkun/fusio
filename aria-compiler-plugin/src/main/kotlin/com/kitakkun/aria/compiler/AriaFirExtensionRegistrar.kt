package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class AriaFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::AriaFirCheckersExtension
        registerDiagnosticContainers(AriaErrors)
    }
}
