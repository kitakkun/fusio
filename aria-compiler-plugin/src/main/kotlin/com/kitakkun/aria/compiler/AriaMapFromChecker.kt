package com.kitakkun.aria.compiler

import com.kitakkun.aria.compiler.compat.CompatContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.Name

class AriaMapFromChecker(compat: CompatContext) :
    FirClassChecker(MppCheckerKind.Common),
    CompatContext by compat {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return

        val mapFromAnnotation = declaration.getAnnotationByClassId(AriaClassIds.MAP_FROM, context.session) ?: return

        val sourceType = mapFromAnnotation.kclassArg(Name.identifier("source"), context.session)
            ?: run {
                reporter.reportOn(declaration.source, AriaErrors.MAP_FROM_INVALID_SOURCE, "Missing source argument", context)
                return
            }
        val sourceClassId = sourceType.classId ?: run {
            reporter.reportOn(declaration.source, AriaErrors.MAP_FROM_INVALID_SOURCE, "Source must be a class reference", context)
            return
        }

        val sourceClass = resolveClassById(sourceClassId, context.session)
        if (sourceClass == null) {
            reporter.reportOn(declaration.source, AriaErrors.MAP_FROM_INVALID_SOURCE, "Cannot resolve source class: $sourceClassId", context)
            return
        }

        // For @MapFrom, `declaration` is the parent effect subtype we construct
        // and the annotation's `source` is the child effect subtype whose data we copy.
        // So the direction flips vs. @MapTo: source=child effect, target=parent effect.
        validatePropertyCompatibility(
            source = sourceClass,
            target = declaration,
            session = context.session,
            reporter = reporter,
            context = context,
            sourceElement = declaration.source,
        )
    }
}
