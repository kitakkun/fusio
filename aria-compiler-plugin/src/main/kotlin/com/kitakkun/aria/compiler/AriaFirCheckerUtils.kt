package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isSealed
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
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
