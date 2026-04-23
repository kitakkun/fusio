package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isSealed
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.name.ClassId

@OptIn(SymbolInternals::class)
internal fun resolveClassById(classId: ClassId, session: FirSession): FirRegularClass? {
    val symbol: FirClassLikeSymbol<*> = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return null
    return symbol.fir as? FirRegularClass
}

internal fun findSealedParent(firClass: FirRegularClass, session: FirSession): FirRegularClass? {
    for (superTypeRef in firClass.superTypeRefs) {
        val superClassId = superTypeRef.coneType.classId ?: continue
        val superClass = resolveClassById(superClassId, session) ?: continue
        if (superClass.isSealed) return superClass
    }
    return null
}

/**
 * Primary-constructor value parameters of a sealed subtype, keyed by parameter name.
 * Secondary constructors are intentionally ignored — Fusio's mapping model treats the
 * primary constructor as the canonical shape.
 */
@OptIn(DirectDeclarationsAccess::class)
internal fun FirRegularClass.primaryConstructorParameters(): Map<String, ConeKotlinType> {
    val primary = declarations.filterIsInstance<FirConstructor>().firstOrNull { it.isPrimary }
        ?: return emptyMap()
    return primary.valueParameters.associate { p ->
        p.name.asString() to p.returnTypeRef.coneType
    }
}

/**
 * Validate that [source] can satisfy the constructor of [target]: for every target
 * parameter there must exist a source property with the same name and a type
 * assignable to the target's parameter type.
 *
 * Reports PROPERTY_MISMATCH diagnostics on [sourceElement] for each problem found.
 * Continues reporting across all parameters so users see everything in one pass,
 * rather than fixing one and discovering the next on the next compile.
 */
internal fun validatePropertyCompatibility(
    source: FirRegularClass,
    target: FirRegularClass,
    session: FirSession,
    reporter: DiagnosticReporter,
    context: CheckerContext,
    sourceElement: KtSourceElement?,
) {
    val sourceProps = source.primaryConstructorParameters()
    val targetProps = target.primaryConstructorParameters()
    val sourceName = source.name.asString()
    val targetName = target.name.asString()

    for ((name, targetType) in targetProps) {
        val srcType = sourceProps[name]
        if (srcType == null) {
            reporter.reportOn(
                sourceElement,
                FusioErrors.PROPERTY_MISMATCH,
                sourceName,
                targetName,
                "missing property '$name' (required by target)",
                context,
            )
            continue
        }
        if (!srcType.isSubtypeOf(targetType, session)) {
            reporter.reportOn(
                sourceElement,
                FusioErrors.PROPERTY_MISMATCH,
                sourceName,
                targetName,
                "property '$name' has type ${srcType.renderReadable()}, " +
                    "not assignable to ${targetType.renderReadable()}",
                context,
            )
        }
    }
}
