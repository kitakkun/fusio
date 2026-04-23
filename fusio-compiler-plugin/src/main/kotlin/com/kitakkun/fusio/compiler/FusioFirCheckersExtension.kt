package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContextResolver
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class FusioFirCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    // Resolved eagerly at extension construction so a misconfigured compiler
    // classpath (missing k** impl) surfaces before FIR analysis even begins.
    private val compat = CompatContextResolver.resolve()

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val classCheckers: Set<FirClassChecker> = setOf(
            FusioMapToChecker(compat),
            FusioMapFromChecker(compat),
            FusioExhaustivenessChecker(compat),
        )
    }
}
