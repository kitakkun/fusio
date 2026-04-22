package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import com.kitakkun.aria.compiler.compat.kclassArg
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.Name

object AriaMapToChecker : FirClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return

        val mapToAnnotation = declaration.getAnnotationByClassId(AriaClassIds.MAP_TO, context.session) ?: return

        val targetType = mapToAnnotation.kclassArg(Name.identifier("target"), context.session)
            ?: run {
                reporter.reportOn(declaration.source, AriaErrors.MAP_TO_INVALID_TARGET, "Missing target argument", context)
                return
            }
        val targetClassId = targetType.classId ?: run {
            reporter.reportOn(declaration.source, AriaErrors.MAP_TO_INVALID_TARGET, "Target must be a class reference", context)
            return
        }

        val targetClass = resolveClassById(targetClassId, context.session)
        if (targetClass == null) {
            reporter.reportOn(declaration.source, AriaErrors.MAP_TO_INVALID_TARGET, "Cannot resolve target class: $targetClassId", context)
            return
        }

        // For @MapTo, `declaration` is the parent event subtype (source of the copy)
        // and the annotation's `target` is the child event subtype we build.
        validatePropertyCompatibility(
            source = declaration,
            target = targetClass,
            session = context.session,
            reporter = reporter,
            context = context,
            sourceElement = declaration.source,
        )
    }
}
