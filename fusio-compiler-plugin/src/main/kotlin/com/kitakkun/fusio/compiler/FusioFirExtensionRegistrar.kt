package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class FusioFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::FusioFirCheckersExtension
        registerDiagnosticContainers(FusioErrors)
    }
}
