package com.kitakkun.aria.compiler.compat

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.Name

/**
 * Kotlin 2.4.0-Beta2: `getKClassArgument` dropped the session parameter and
 * now takes (name) only. We accept session for API parity with the k23 compat
 * but ignore it here.
 */
@Suppress("UNUSED_PARAMETER")
internal fun FirAnnotation.kclassArg(name: Name, session: FirSession): ConeKotlinType? =
    getKClassArgument(name)
