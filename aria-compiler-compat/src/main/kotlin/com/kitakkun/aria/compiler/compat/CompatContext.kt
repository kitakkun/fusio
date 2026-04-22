package com.kitakkun.aria.compiler.compat

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

/**
 * Abstraction over every Kotlin-compiler API call whose signature or receiver
 * shape shifts between supported Kotlin minor releases. The main plugin code
 * should route all such calls through a [CompatContext] instance instead of
 * touching the moving APIs directly.
 *
 * One [CompatContext] implementation exists per supported Kotlin version, in
 * its own `aria-compiler-compat/kXXX/` subproject (e.g. `k2320`, `k240_beta2`)
 * compiled against that version's `kotlin-compiler-embeddable`.
 * [CompatContextResolver] picks the matching impl at compiler runtime via
 * `java.util.ServiceLoader`.
 *
 * Design pattern borrowed from ZacSweers/Metro's `compiler-compat` module;
 * see project NOTICE.
 */
interface CompatContext {
    /**
     * Reads a `KClass<*>`-typed argument off a [FirAnnotation].
     *
     * - Kotlin 2.3.x: `getKClassArgument(name, session)`
     * - Kotlin 2.4.x: `getKClassArgument(name)` (session dropped)
     */
    fun FirAnnotation.kclassArg(name: Name, session: FirSession): ConeKotlinType?

    /**
     * Writes the value argument at [index] on an [IrCall].
     *
     * - Kotlin 2.3.x+: `call.arguments[index] = expr`
     * - Kotlin 2.1–2.2: `call.putValueArgument(index, expr)` (dispatch/extension
     *   receivers lived on dedicated properties)
     *
     * Going through this helper keeps call sites independent of the transition.
     */
    fun IrCall.setArg(index: Int, expr: IrExpression)

    /**
     * Writes the type argument at [index] on an [IrCall].
     *
     * - Kotlin 2.3.x+: `call.typeArguments[index] = type`
     * - Kotlin 2.1–2.2: `call.putTypeArgument(index, type)`
     */
    fun IrCall.setTypeArg(index: Int, type: IrType)

    /**
     * Registered per subproject via META-INF/services. [CompatContextResolver]
     * instantiates the first factory whose [supportedRange] contains the
     * running Kotlin compiler's version.
     */
    interface Factory {
        /** Half-open Kotlin version range this impl covers: `[min, max)`. */
        val supportedRange: VersionRange

        /** Instantiates the matching [CompatContext]. Called at most once per run. */
        fun create(): CompatContext
    }
}

/**
 * Parsed Kotlin version triple. Pre-release qualifiers like `-Beta2`, `-RC`,
 * `-dev-NNNN` are dropped during [parse]; we only compare on the numeric
 * `major.minor.patch`. For a 2.4.0-Beta2 compiler, this means the same range
 * that matches stable 2.4.0 matches the Beta too — which is what we want
 * during pre-release work.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String): Version {
            // Take the first three numeric segments; ignore any trailing qualifier.
            val parts = raw.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }
            require(parts.size >= 2) { "Cannot parse Kotlin version: $raw" }
            return Version(parts[0], parts[1], parts.getOrElse(2) { 0 })
        }
    }
}

/** Half-open version range: [min] inclusive, [max] exclusive. */
data class VersionRange(val min: Version, val max: Version) {
    init {
        require(min < max) { "VersionRange min ($min) must be < max ($max)" }
    }

    operator fun contains(version: Version): Boolean = version in min..<max

    override fun toString(): String = "[$min, $max)"
}
