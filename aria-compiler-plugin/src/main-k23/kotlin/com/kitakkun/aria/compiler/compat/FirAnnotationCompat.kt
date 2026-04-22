package com.kitakkun.aria.compiler.compat

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.Name

/**
 * Kotlin 2.3.20: `getKClassArgument` takes (name, session). The single-arg
 * overload on master doesn't exist yet in 2.3.20's compiler jar, so we MUST
 * pass session here.
 */
internal fun FirAnnotation.kclassArg(name: Name, session: FirSession): ConeKotlinType? =
    getKClassArgument(name, session)
