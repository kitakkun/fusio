package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.error3
import org.jetbrains.kotlin.diagnostics.warning2
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtElement

object FusioErrors : KtDiagnosticsContainer() {
    val MAP_TO_INVALID_TARGET by error1<KtElement, String>()
    val MAP_FROM_INVALID_SOURCE by error1<KtElement, String>()
    val PROPERTY_MISMATCH by error3<KtElement, String, String, String>()
    val MISSING_EVENT_MAPPINGS by error2<KtElement, String, String>()
    val MISSING_EFFECT_MAPPINGS by error2<KtElement, String, String>()

    /**
     * Pair of factories — one ERROR, one WARNING — for the
     * event-handler-exhaustiveness check. The checker selects between them
     * at fire time based on the severity configured via the
     * `event-handler-exhaustive-severity` compiler option (or skips firing
     * altogether when `none`). Two factories instead of a single
     * dynamically-severity one because Kotlin's diagnostic infrastructure
     * pins severity at factory construction.
     */
    val MISSING_EVENT_HANDLER_ERROR by error2<KtElement, String, String>()
    val MISSING_EVENT_HANDLER_WARNING by warning2<KtElement, String, String>()

    override fun getRendererFactory() = FusioErrorMessages
}

object FusioErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Fusio") { map ->
        map.put(FusioErrors.MAP_TO_INVALID_TARGET, "Invalid @MapTo target: {0}", null)
        map.put(FusioErrors.MAP_FROM_INVALID_SOURCE, "Invalid @MapFrom source: {0}", null)
        map.put(FusioErrors.PROPERTY_MISMATCH, "Property mismatch between ''{0}'' and ''{1}'': {2}", null, null, null)
        map.put(FusioErrors.MISSING_EVENT_MAPPINGS, "Missing @MapTo mappings for ''{0}'' subtypes: {1}", null, null)
        map.put(FusioErrors.MISSING_EFFECT_MAPPINGS, "Missing @MapFrom mappings for ''{0}'' subtypes: {1}", null, null)
        map.put(
            FusioErrors.MISSING_EVENT_HANDLER_ERROR,
            "Event ''{0}'' has subtypes with no `on<>` handler and no `@MapTo` route via fuse: {1}",
            null,
            null,
        )
        map.put(
            FusioErrors.MISSING_EVENT_HANDLER_WARNING,
            "Event ''{0}'' has subtypes with no `on<>` handler and no `@MapTo` route via fuse: {1}",
            null,
            null,
        )
    }
}
