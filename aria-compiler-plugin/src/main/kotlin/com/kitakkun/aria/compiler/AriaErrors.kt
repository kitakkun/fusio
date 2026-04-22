package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.error3
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory

object AriaErrors : KtDiagnosticsContainer() {
    val MAP_TO_INVALID_TARGET by error1<PsiElement, String>()
    val MAP_FROM_INVALID_SOURCE by error1<PsiElement, String>()
    val PROPERTY_MISMATCH by error3<PsiElement, String, String, String>()
    val ANNOTATION_ON_NON_SEALED_SUBTYPE by error1<PsiElement, String>()
    val MISSING_EVENT_MAPPINGS by error2<PsiElement, String, String>()
    val MISSING_EFFECT_MAPPINGS by error2<PsiElement, String, String>()

    override fun getRendererFactory() = AriaErrorMessages
}

object AriaErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Aria") { map ->
        map.put(AriaErrors.MAP_TO_INVALID_TARGET, "Invalid @MapTo target: {0}", null)
        map.put(AriaErrors.MAP_FROM_INVALID_SOURCE, "Invalid @MapFrom source: {0}", null)
        map.put(AriaErrors.PROPERTY_MISMATCH, "Property mismatch between ''{0}'' and ''{1}'': {2}", null, null, null)
        map.put(AriaErrors.ANNOTATION_ON_NON_SEALED_SUBTYPE, "@MapTo/@MapFrom on non-sealed subtype: {0}", null)
        map.put(AriaErrors.MISSING_EVENT_MAPPINGS, "Missing @MapTo mappings for ''{0}'' subtypes: {1}", null, null)
        map.put(AriaErrors.MISSING_EFFECT_MAPPINGS, "Missing @MapFrom mappings for ''{0}'' subtypes: {1}", null, null)
    }
}
