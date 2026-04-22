package com.github.kitakkun.aria.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.utils.isSealed
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

object AriaExhaustivenessChecker : FirClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return
        if (!declaration.isSealed) return

        checkEventExhaustiveness(declaration, context, reporter)
        checkEffectExhaustiveness(declaration, context, reporter)
    }

    private fun checkEventExhaustiveness(
        sealedClass: FirRegularClass,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        val inheritorIds = sealedClass.getSealedClassInheritors(context.session)
        val inheritors = inheritorIds.mapNotNull { resolveClassById(it, context.session) }

        val mapToTargets = mutableMapOf<ClassId, MutableSet<ClassId>>()

        for (inheritor in inheritors) {
            val mapToAnnotation = inheritor.getAnnotationByClassId(AriaClassIds.MAP_TO, context.session) ?: continue
            val targetType = mapToAnnotation.getKClassArgument(Name.identifier("target"), context.session) ?: continue
            val targetClassId = targetType.classId ?: continue
            val targetClass = resolveClassById(targetClassId, context.session) ?: continue
            val childSealedParent = findSealedParent(targetClass, context.session) ?: continue

            mapToTargets
                .getOrPut(childSealedParent.symbol.classId) { mutableSetOf() }
                .add(targetClassId)
        }

        for ((childSealedId, coveredSubtypes) in mapToTargets) {
            val childSealed = resolveClassById(childSealedId, context.session) ?: continue
            val allChildSubtypes = childSealed.getSealedClassInheritors(context.session).toSet()
            val missingSubtypes = allChildSubtypes - coveredSubtypes
            if (missingSubtypes.isNotEmpty()) {
                val missingNames = missingSubtypes.joinToString(", ") { it.shortClassName.asString() }
                reporter.reportOn(
                    sealedClass.source,
                    AriaErrors.MISSING_EVENT_MAPPINGS,
                    childSealed.name.asString(),
                    missingNames,
                    context,
                )
            }
        }
    }

    private fun checkEffectExhaustiveness(
        sealedClass: FirRegularClass,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        val inheritorIds = sealedClass.getSealedClassInheritors(context.session)
        val inheritors = inheritorIds.mapNotNull { resolveClassById(it, context.session) }

        val mapFromSources = mutableMapOf<ClassId, MutableSet<ClassId>>()

        for (inheritor in inheritors) {
            val mapFromAnnotation = inheritor.getAnnotationByClassId(AriaClassIds.MAP_FROM, context.session) ?: continue
            val sourceType = mapFromAnnotation.getKClassArgument(Name.identifier("source"), context.session) ?: continue
            val sourceClassId = sourceType.classId ?: continue
            val sourceClass = resolveClassById(sourceClassId, context.session) ?: continue
            val childSealedParent = findSealedParent(sourceClass, context.session) ?: continue

            mapFromSources
                .getOrPut(childSealedParent.symbol.classId) { mutableSetOf() }
                .add(sourceClassId)
        }

        for ((childSealedId, coveredSubtypes) in mapFromSources) {
            val childSealed = resolveClassById(childSealedId, context.session) ?: continue
            val allChildSubtypes = childSealed.getSealedClassInheritors(context.session).toSet()
            val missingSubtypes = allChildSubtypes - coveredSubtypes
            if (missingSubtypes.isNotEmpty()) {
                val missingNames = missingSubtypes.joinToString(", ") { it.shortClassName.asString() }
                reporter.reportOn(
                    sealedClass.source,
                    AriaErrors.MISSING_EFFECT_MAPPINGS,
                    childSealed.name.asString(),
                    missingNames,
                    context,
                )
            }
        }
    }
}
