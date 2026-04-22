package com.kitakkun.aria.compiler

import com.kitakkun.aria.compiler.compat.CompatContextResolver
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class AriaFirCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    // Resolved eagerly at extension construction so a misconfigured compiler
    // classpath (missing k** impl) surfaces before FIR analysis even begins.
    private val compat = CompatContextResolver.resolve()

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val classCheckers: Set<FirClassChecker> = setOf(
            AriaMapToChecker(compat),
            AriaMapFromChecker(compat),
            AriaExhaustivenessChecker(compat),
        )
    }
}
