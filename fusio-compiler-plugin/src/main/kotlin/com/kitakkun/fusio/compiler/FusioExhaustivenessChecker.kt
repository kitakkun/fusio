package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.utils.isSealed
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * For each sealed parent Event/Effect type that declares any `@MapTo` /
 * `@MapFrom` mappings on its subtypes, checks that every subtype of the
 * referenced child sealed type is covered. Fires MISSING_EVENT_MAPPINGS /
 * MISSING_EFFECT_MAPPINGS otherwise — so a child subtype added later without
 * a corresponding parent mapping surfaces as a compile error, not a silent drop.
 */
class FusioExhaustivenessChecker(compat: CompatContext) :
    FirClassChecker(MppCheckerKind.Common),
    CompatContext by compat {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return
        if (!declaration.isSealed) return

        checkExhaustiveness(declaration, ExhaustivenessDirection.EVENT)
        checkExhaustiveness(declaration, ExhaustivenessDirection.EFFECT)
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkExhaustiveness(
        sealedClass: FirRegularClass,
        direction: ExhaustivenessDirection,
    ) {
        val inheritors = sealedClass.getSealedClassInheritors(context.session)
            .mapNotNull { resolveClassById(it, context.session) }

        // Collect the set of child subtypes that this parent sealed interface
        // covers, grouped by the child sealed type they belong to. Parents may
        // map into multiple different child hierarchies; each is validated
        // independently.
        val coveredByChildSealed = mutableMapOf<ClassId, MutableSet<ClassId>>()
        for (inheritor in inheritors) {
            val annotation = inheritor.getAnnotationByClassId(direction.annotationClassId, context.session) ?: continue
            val otherType = annotation.kclassArg(direction.argumentName, context.session) ?: continue
            val otherClassId = otherType.classId ?: continue
            val otherClass = resolveClassById(otherClassId, context.session) ?: continue
            val childSealedParent = findSealedParent(otherClass, context.session) ?: continue

            coveredByChildSealed
                .getOrPut(childSealedParent.symbol.classId) { mutableSetOf() }
                .add(otherClassId)
        }

        for ((childSealedId, covered) in coveredByChildSealed) {
            val childSealed = resolveClassById(childSealedId, context.session) ?: continue
            val allChildSubtypes = childSealed.getSealedClassInheritors(context.session).toSet()
            val missing = allChildSubtypes - covered
            if (missing.isNotEmpty()) {
                reporter.reportOn(
                    sealedClass.source,
                    direction.errorFactory,
                    childSealed.name.asString(),
                    missing.joinToString(", ") { it.shortClassName.asString() },
                    context,
                )
            }
        }
    }

    /**
     * The two checks are structurally identical — walk sealed subtypes of the
     * parent, collect the child subtypes they map to/from, diff against the
     * child sealed hierarchy, report. Only these three values differ, so we
     * parameterise with them rather than duplicating the whole loop.
     */
    private enum class ExhaustivenessDirection(
        val annotationClassId: ClassId,
        val argumentName: Name,
        val errorFactory: KtDiagnosticFactory2<String, String>,
    ) {
        EVENT(FusioClassIds.MAP_TO, Name.identifier("target"), FusioErrors.MISSING_EVENT_MAPPINGS),
        EFFECT(FusioClassIds.MAP_FROM, Name.identifier("source"), FusioErrors.MISSING_EFFECT_MAPPINGS),
    }
}
