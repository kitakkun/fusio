package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Unified checker for `@MapTo` and `@MapFrom`. The two annotations have
 * symmetric validation needs — both reference a "other class" via a KClass
 * argument, both require that class to resolve, and both feed into
 * [validatePropertyCompatibility] — they only differ in:
 *
 * 1. Which annotation / argument name they read.
 * 2. Which error diagnostic to raise on a resolution failure.
 * 3. Whether `declaration` plays the `source` or `target` role in the
 *    property-compatibility check.
 *
 * [direction] parameterises those three differentials so we register one
 * checker class twice (once per direction) instead of maintaining two
 * near-copies that can drift on any future cross-cutting change.
 */
class FusioMappingChecker(
    compat: CompatContext,
    private val direction: MappingDirection,
) : FirClassChecker(MppCheckerKind.Common),
    CompatContext by compat {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return

        val annotation = declaration.getAnnotationByClassId(direction.annotationClassId, context.session) ?: return

        // `kclassesArg` collapses to a 1-element list for single-arg annotations
        // (`@MapTo(target = X::class)`) and returns N elements for vararg
        // annotations (`@MapFrom(A::class, B::class)`). Each element gets the
        // same property-compatibility check against the annotated class — i.e.
        // every fan-in source must structurally match the parent subtype.
        val otherTypes = annotation.kclassesArg(direction.argumentName, context.session)
        if (otherTypes.isEmpty()) {
            reporter.reportOn(declaration.source, direction.invalidArgFactory, "Missing ${direction.argumentName.asString()} argument", context)
            return
        }

        for (otherType in otherTypes) {
            val otherClassId = otherType.classId ?: run {
                reporter.reportOn(declaration.source, direction.invalidArgFactory, "${direction.argumentName.asString().replaceFirstChar { it.uppercase() }} must be a class reference", context)
                return
            }

            val otherClass = resolveClassById(otherClassId, context.session)
            if (otherClass == null) {
                reporter.reportOn(declaration.source, direction.invalidArgFactory, "Cannot resolve ${direction.argumentName.asString()} class: $otherClassId", context)
                return
            }

            val (sourceForCompat, targetForCompat) = direction.pickSourceTarget(declaration, otherClass)
            validatePropertyCompatibility(
                source = sourceForCompat,
                target = targetForCompat,
                session = context.session,
                reporter = reporter,
                context = context,
                sourceElement = declaration.source,
            )
        }
    }
}

/**
 * Directional parameters for [FusioMappingChecker]. One entry per
 * annotation; the selector method captures which side of the mapping the
 * annotated class sits on.
 */
enum class MappingDirection(
    val annotationClassId: ClassId,
    val argumentName: Name,
    val invalidArgFactory: KtDiagnosticFactory1<String>,
) {
    /** `@MapTo`: annotated class is the *parent event subtype* (source); target is the child event subtype. */
    MAP_TO(FusioClassIds.MAP_TO, Name.identifier("target"), FusioErrors.MAP_TO_INVALID_TARGET) {
        override fun pickSourceTarget(
            declaration: FirRegularClass,
            other: FirRegularClass,
        ): Pair<FirRegularClass, FirRegularClass> = declaration to other
    },

    /** `@MapFrom`: annotated class is the *parent effect subtype* (target); source is the child effect subtype. */
    MAP_FROM(FusioClassIds.MAP_FROM, Name.identifier("source"), FusioErrors.MAP_FROM_INVALID_SOURCE) {
        override fun pickSourceTarget(
            declaration: FirRegularClass,
            other: FirRegularClass,
        ): Pair<FirRegularClass, FirRegularClass> = other to declaration
    }, ;

    abstract fun pickSourceTarget(
        declaration: FirRegularClass,
        other: FirRegularClass,
    ): Pair<FirRegularClass, FirRegularClass>
}
