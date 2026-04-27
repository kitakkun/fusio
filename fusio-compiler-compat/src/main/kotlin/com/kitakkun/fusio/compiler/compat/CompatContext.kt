package com.kitakkun.fusio.compiler.compat

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Abstraction over every Kotlin-compiler API call whose signature or receiver
 * shape shifts between supported Kotlin minor releases. The main plugin code
 * should route all such calls through a [CompatContext] instance instead of
 * touching the moving APIs directly.
 *
 * One [CompatContext] implementation exists per supported Kotlin version, in
 * its own `fusio-compiler-compat/kXXX/` subproject (e.g. `k2320`, `k240_beta2`)
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
     * Reads a `vararg KClass<*>`-typed argument off a [FirAnnotation] —
     * each `KClass` element is returned as a separate [ConeKotlinType].
     * Used for `@MapFrom(vararg val source: KClass<*>)` where a single
     * parent effect subtype can fan in multiple child effect sources.
     *
     * The argument's FIR shape is `FirVarargArgumentsExpression(arguments
     * = [FirGetClassCall, ...])` regardless of whether the call site
     * passed one element or many; this helper unwraps the wrapper and
     * evaluates each [FirGetClassCall] element. A non-vararg single
     * `KClass` argument also resolves through the fallback branch.
     */
    fun FirAnnotation.kclassesArg(name: Name, session: FirSession): List<ConeKotlinType>

    /**
     * Writes the value argument at [index] on any IR function-access expression
     * (regular calls, constructor calls, delegating calls, enum entry calls).
     *
     * - Kotlin 2.3.x+: `call.arguments[index] = expr`
     * - Kotlin 2.1–2.2: `call.putValueArgument(index, expr)` (dispatch/extension
     *   receivers lived on dedicated properties)
     *
     * Going through this helper keeps call sites independent of the transition.
     */
    fun IrFunctionAccessExpression.setArg(index: Int, expr: IrExpression)

    /**
     * Writes the type argument at [index] on any IR function-access expression.
     *
     * - Kotlin 2.3.x+: `call.typeArguments[index] = type`
     * - Kotlin 2.1–2.2: `call.putTypeArgument(index, type)`
     */
    fun IrFunctionAccessExpression.setTypeArg(index: Int, type: IrType)

    /**
     * Registers a FIR extension registrar against the current compiler's
     * plugin storage.
     *
     * - Kotlin 2.3.x: `FirExtensionRegistrarAdapter.registerExtension(registrar)` —
     *   Companion object extends `ProjectExtensionDescriptor<FirExtensionRegistrar>`,
     *   and `ExtensionStorage.registerExtension` takes `ProjectExtensionDescriptor<T>`.
     * - Kotlin 2.4.x: same call site, but `ProjectExtensionDescriptor` has been
     *   renamed to `ExtensionPointDescriptor` (different class FQN in the
     *   companion hierarchy and the ExtensionStorage method signature), so
     *   bytecode compiled against 2.3 NoSuchMethodError's under 2.4.
     *
     * Routed through CompatContext so each k** impl's bytecode references the
     * type its compiler jar actually ships.
     */
    fun CompilerPluginRegistrar.ExtensionStorage.registerFirExtension(registrar: FirExtensionRegistrar)

    /**
     * Sister of [registerFirExtension] for IR-generation extensions. Same
     * underlying `ProjectExtensionDescriptor` → `ExtensionPointDescriptor`
     * break, same fix: per-k**-impl bytecode.
     */
    fun CompilerPluginRegistrar.ExtensionStorage.registerIrGenerationExtension(extension: IrGenerationExtension)

    /**
     * Looks up an IR class symbol by [ClassId] on a running [IrPluginContext].
     *
     * - Kotlin 2.3.20+: `pluginContext.finderForBuiltins().findClass(classId)`
     *   (the `DeclarationFinder` API, IC-compatible).
     * - Kotlin 2.3.0 – 2.3.19: `DeclarationFinder` doesn't exist yet. Falls back
     *   to the legacy `pluginContext.referenceClass(classId)`.
     *
     * Routed through CompatContext so each k** impl's bytecode references the
     * API its compiler jar actually ships — without this, the shaded plugin
     * jar link-fails on 2.3.0 / 2.3.10 because `DeclarationFinder` is absent.
     */
    fun IrPluginContext.findClass(classId: ClassId): IrClassSymbol?

    /**
     * Looks up all constructors of [classId]. See [findClass] for the per-version
     * API story.
     */
    fun IrPluginContext.findConstructors(classId: ClassId): Collection<IrConstructorSymbol>

    /**
     * Looks up all top-level / class-member functions matching [callableId]. See
     * [findClass] for the per-version API story.
     */
    fun IrPluginContext.findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol>

    /**
     * `IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA` — used as the origin for
     * synthetic `@MapTo` / `@MapFrom` mapper lambdas the IR transformer
     * generates.
     *
     * - Kotlin 2.3.0 – 2.3.19: `IrDeclarationOrigin.Companion.getLOCAL_FUNCTION_FOR_LAMBDA()`
     *   returns `IrDeclarationOriginImpl` (subtype).
     * - Kotlin 2.3.20+: same call returns `IrDeclarationOrigin` (the interface).
     *
     * JVM considers the two signatures distinct, so the 2.3.21-compiled plugin
     * bytecode `NoSuchMethodError`s under 2.3.0 runtime. Routing through this
     * property gives each k** impl the chance to call its own compiler's
     * getter and up-cast to the common interface type.
     */
    val localFunctionForLambdaOrigin: IrDeclarationOrigin

    /**
     * Builds a `FirSimpleFunctionChecker` subclass whose `check(...)` body
     * extracts a function declaration's `receiverParameter` / `body` /
     * `source` and forwards them to [analyzer].
     *
     * Routed through CompatContext because the parameter type of
     * `FirSimpleFunctionChecker.check` was renamed inside the 2.3.x line:
     *
     * - Kotlin 2.3.0 – 2.3.10: `check(declaration: FirSimpleFunction)`
     * - Kotlin 2.3.20+:        `check(declaration: FirNamedFunction)`
     *
     * Each `kXXX` impl can subclass against the right type for its compiler
     * jar, so the resulting bytecode loads cleanly under both shapes. The
     * shared analysis function takes only Kotlin-version-stable types
     * (`ConeKotlinType`, `FirElement`, `KtSourceElement`).
     *
     * Return type is `Any` rather than `FirSimpleFunctionChecker` because
     * `FirSimpleFunctionChecker`'s generic supertype shifts between
     * `FirDeclarationChecker<FirSimpleFunction>` (2.3.0–2.3.10) and
     * `FirDeclarationChecker<FirNamedFunction>` (2.3.20+), which Kotlin's
     * variance check rejects as a non-subtype across the abstract method.
     * Callers cast the result to `FirSimpleFunctionChecker` — runtime
     * checkcast only inspects the raw class identity, which is identical
     * across versions.
     */
    fun createSubPresenterChecker(analyzer: SubPresenterAnalyzer): Any

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
 * Stable surface the per-version sub-presenter checker forwards into. All
 * three parameters are Kotlin-version-stable types — receiver type as
 * `ConeKotlinType`, body as `FirElement`, source as `KtSourceElement` —
 * so the shared analysis side never has to care which `FirNamedFunction`
 * vs `FirSimpleFunction` the checker overrode.
 */
interface SubPresenterAnalyzer {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    fun analyze(
        receiverType: ConeKotlinType?,
        body: FirElement?,
        source: KtSourceElement?,
    )
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
