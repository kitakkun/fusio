package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class FusioFirExtensionRegistrar(
    private val severity: EventHandlerExhaustiveSeverity = EventHandlerExhaustiveSeverity.WARNING,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: org.jetbrains.kotlin.fir.FirSession ->
            FusioFirCheckersExtension(session, severity)
        }
        registerDiagnosticContainers(FusioErrors)
    }
}
