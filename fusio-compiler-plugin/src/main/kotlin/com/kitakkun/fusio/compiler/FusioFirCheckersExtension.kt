package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContextResolver
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class FusioFirCheckersExtension(
    session: FirSession,
    private val severity: EventHandlerExhaustiveSeverity = EventHandlerExhaustiveSeverity.WARNING,
) : FirAdditionalCheckersExtension(session) {
    // Resolved eagerly at extension construction so a misconfigured compiler
    // classpath (missing k** impl) surfaces before FIR analysis even begins.
    private val compat = CompatContextResolver.resolve()

    private val eventHandlerExhaustiveness = FusioEventHandlerExhaustivenessChecker(compat, severity)

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val classCheckers: Set<FirClassChecker> = setOf(
            FusioMappingChecker(compat, MappingDirection.MAP_TO),
            FusioMappingChecker(compat, MappingDirection.MAP_FROM),
            FusioExhaustivenessChecker(compat),
        )
    }

    // Top-level half of the on<>-exhaustiveness check, keyed on
    // `buildPresenter<E, F, S> { … }` call sites. Sub-presenter declaration
    // checking is intentionally absent — see [FusioEventHandlerExhaustivenessChecker]
    // for the per-Kotlin-version compat reasoning.
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(
            eventHandlerExhaustiveness.callChecker(),
        )
    }
}
