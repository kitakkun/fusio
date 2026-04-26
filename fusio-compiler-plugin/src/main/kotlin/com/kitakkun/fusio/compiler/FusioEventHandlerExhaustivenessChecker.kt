package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContext
import com.kitakkun.fusio.compiler.compat.SubPresenterAnalyzer
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.utils.isSealed
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Reports parent-Event sealed subtypes that no `on<E>` handler covers and
 * no `@MapTo` annotation routes through a `fuse { … }` child.
 *
 * The current implementation hooks `buildPresenter<E, F, S> { body }` call
 * sites only. Standalone sub-presenter declarations
 * (`@Composable fun PresenterScope<E, F>.foo(): S { body }`) aren't
 * checked at the declaration site itself — they're only reached
 * indirectly via the parent's `fuse` rewrite. Adding a declaration-site
 * checker would currently require a per-Kotlin-version compat shim
 * because the function-FIR class was renamed
 * (`FirSimpleFunction` → `FirNamedFunction`) inside the 2.3.x line, so
 * the bytecode signature of any `FirSimpleFunctionChecker` override
 * binds to one or the other and won't load against the off-version.
 * Top-level coverage is the higher-value half regardless — the user's
 * original missing-handler example was a `buildPresenter` body — so we
 * keep the simple form and revisit sub-presenter coverage if observed
 * pain justifies the per-version compat work.
 *
 * Inside the `buildPresenter` lambda body we walk once with
 * [OnFuseCollector], collect the `on<T>` type arguments and
 * `fuse<CE, …>` first type arguments, then compute coverage:
 *
 * - A subtype X is **directly handled** if its supertype chain reaches
 *   the `T` of any `on<T>` (`on<EventBase>` covers all subtypes for free).
 *   Walked by class id rather than full type-system isSubtypeOf to keep
 *   the code stable across Kotlin minor releases — sealed Event
 *   hierarchies don't use type parameters in practice, so the loss of
 *   generic-substitution rigor doesn't matter here.
 * - A subtype X is **fuse-routed** if it carries `@MapTo(target = Y::class)`
 *   and Y is in the sealed hierarchy of any of the fused child Event types.
 *
 * Anything left after both passes is reported.
 *
 * ## Severity
 *
 * Selected from [FusioConfigurationKeys.EVENT_HANDLER_EXHAUSTIVE_SEVERITY]
 * (default WARNING). `NONE` short-circuits before walking the body.
 * The two factories ([FusioErrors.MISSING_EVENT_HANDLER_ERROR] /
 * `_WARNING`) carry their severity at construction — Kotlin's diagnostic
 * plumbing pins it there, so we have to choose one based on config rather
 * than mutate severity after the fact.
 */
class FusioEventHandlerExhaustivenessChecker(
    private val compat: CompatContext,
    private val severity: EventHandlerExhaustiveSeverity,
) {

    /** Registers this checker's `buildPresenter`-call half. */
    fun callChecker(): FirFunctionCallChecker = CallShell()

    /**
     * Builds the sub-presenter declaration-side half via [CompatContext]
     * because the underlying `FirSimpleFunctionChecker.check(...)` parameter
     * type was renamed (`FirSimpleFunction` → `FirNamedFunction`) inside
     * the 2.3.x line. Each `kXXX` compat impl provides bytecode targeting
     * the right parameter name; both forward into [analyzer].
     */
    fun functionChecker(compat: CompatContext): FirSimpleFunctionChecker =
        compat.createSubPresenterChecker(SubPresenterAnalyzerImpl()) as FirSimpleFunctionChecker

    private inner class SubPresenterAnalyzerImpl : SubPresenterAnalyzer {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun analyze(
            receiverType: ConeKotlinType?,
            body: FirElement?,
            source: KtSourceElement?,
        ) {
            if (severity == EventHandlerExhaustiveSeverity.NONE) return
            val rType = receiverType ?: return
            val rClassId = rType.classId ?: return
            if (rClassId != FusioClassIds.PRESENTER_SCOPE) return

            // First type argument of PresenterScope<Event, Effect> is the Event hierarchy.
            val firstArg = rType.typeArguments.firstOrNull() ?: return
            val eventType = (firstArg as? org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection)
                ?.type ?: return
            val bodyEl = body ?: return

            checkExhaustiveness(eventType, bodyEl, source)
        }
    }

    private inner class CallShell : FirFunctionCallChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirFunctionCall) {
            if (severity == EventHandlerExhaustiveSeverity.NONE) return
            val callableId = expression.calleeReference.toResolvedFunctionSymbol()?.callableId ?: return
            if (callableId != FusioClassIds.BUILD_PRESENTER) return

            val eventTypeProjection = expression.typeArguments.firstOrNull() ?: return
            val eventType = (eventTypeProjection as? FirTypeProjectionWithVariance)
                ?.typeRef?.coneType ?: return

            val lambda = expression.argumentList.arguments
                .filterIsInstance<FirAnonymousFunctionExpression>()
                .firstOrNull() ?: return
            val body = lambda.anonymousFunction.body ?: return

            checkExhaustiveness(eventType, body, expression.source)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkExhaustiveness(
        eventType: ConeKotlinType,
        body: FirElement,
        sourceElement: KtSourceElement?,
    ) {
        val session = context.session
        val eventClassId = eventType.classId ?: return
        val eventClass = resolveClassById(eventClassId, session) ?: return
        if (!eventClass.isSealed) return

        val subtypeIds = eventClass.getSealedClassInheritors(session)
        if (subtypeIds.isEmpty()) return

        val collector = OnFuseCollector()
        body.accept(collector, Unit)

        // Class-id sets for fast subtype-of comparison.
        val onClassIds = collector.onTypes.mapNotNull { it.classId }.toSet()
        if (onClassIds.isEmpty() && collector.fuseChildEventTypes.isEmpty()) {
            // Body doesn't handle anything at all and doesn't fuse anything.
            // Every subtype is missing — short-circuit.
            reportMissing(eventClass.name.asString(), subtypeIds.map { it.shortClassName.asString() }, sourceElement)
            return
        }

        val fuseRoutedChildSubtypeIds = collectFuseRoutedChildSubtypeIds(collector.fuseChildEventTypes, session)

        val missing = mutableListOf<String>()
        for (subtypeId in subtypeIds) {
            val subtypeClass = resolveClassById(subtypeId, session) ?: continue
            if (subtypeIsHandledByOn(subtypeClass, onClassIds, session)) continue
            if (subtypeIsFuseRouted(subtypeClass, fuseRoutedChildSubtypeIds, session)) continue
            missing.add(subtypeId.shortClassName.asString())
        }

        if (missing.isNotEmpty()) {
            reportMissing(eventClass.name.asString(), missing, sourceElement)
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportMissing(
        eventName: String,
        missingSubtypeNames: List<String>,
        sourceElement: KtSourceElement?,
    ) {
        val factory = when (severity) {
            EventHandlerExhaustiveSeverity.ERROR -> FusioErrors.MISSING_EVENT_HANDLER_ERROR
            EventHandlerExhaustiveSeverity.WARNING -> FusioErrors.MISSING_EVENT_HANDLER_WARNING
            EventHandlerExhaustiveSeverity.NONE -> return
        }
        reporter.reportOn(
            sourceElement,
            factory,
            eventName,
            missingSubtypeNames.sorted().joinToString(", "),
            context,
        )
    }

    /**
     * `true` when [subtypeClass]'s supertype chain reaches any of the
     * `on<T>` type-argument class ids in [onClassIds]. Recursive walk
     * over [FirRegularClass.superTypeRefs] keeps the check stable across
     * Kotlin minor releases — full `ConeKotlinType.isSubtypeOf` would
     * pull in more API surface for marginal gain on Fusio's typically
     * generic-free sealed Event hierarchies.
     */
    private fun subtypeIsHandledByOn(
        subtypeClass: FirRegularClass,
        onClassIds: Set<ClassId>,
        session: FirSession,
    ): Boolean = walkSupertypesContains(subtypeClass, onClassIds, session)

    private fun walkSupertypesContains(
        firClass: FirRegularClass,
        targetIds: Set<ClassId>,
        session: FirSession,
        seen: MutableSet<ClassId> = mutableSetOf(),
    ): Boolean {
        val classId = firClass.symbol.classId
        if (classId in targetIds) return true
        if (!seen.add(classId)) return false
        for (superTypeRef in firClass.superTypeRefs) {
            val superClassId = superTypeRef.coneType.classId ?: continue
            if (superClassId in targetIds) return true
            val superClass = resolveClassById(superClassId, session) ?: continue
            if (walkSupertypesContains(superClass, targetIds, session, seen)) return true
        }
        return false
    }

    private fun collectFuseRoutedChildSubtypeIds(
        fuseChildEventTypes: List<ConeKotlinType>,
        session: FirSession,
    ): Set<ClassId> {
        if (fuseChildEventTypes.isEmpty()) return emptySet()
        val result = mutableSetOf<ClassId>()
        for (childEventType in fuseChildEventTypes) {
            val childClassId = childEventType.classId ?: continue
            val childClass = resolveClassById(childClassId, session) ?: continue
            if (!childClass.isSealed) continue
            for (childSubtypeId in childClass.getSealedClassInheritors(session)) {
                result.add(childSubtypeId)
            }
        }
        return result
    }

    private fun subtypeIsFuseRouted(
        subtypeClass: FirRegularClass,
        fuseRoutedChildSubtypeIds: Set<ClassId>,
        session: FirSession,
    ): Boolean {
        if (fuseRoutedChildSubtypeIds.isEmpty()) return false
        val mapToAnnotation = subtypeClass.getAnnotationByClassId(FusioClassIds.MAP_TO, session)
            ?: return false
        val targetType = with(compat) { mapToAnnotation.kclassArg(MAP_TO_TARGET_ARG, session) } ?: return false
        val targetClassId = targetType.classId ?: return false
        return targetClassId in fuseRoutedChildSubtypeIds
    }

    private companion object {
        val MAP_TO_TARGET_ARG: Name = Name.identifier("target")
    }
}

/**
 * Visitor that gathers the type arguments of every `on<T>(…)` call and the
 * first type argument of every `fuse<CE, CEff, CS>(…)` call inside an
 * arbitrary FIR subtree. Descends into all children — works on if/when/try
 * blocks, lambdas, nested function calls — so a presenter body's structure
 * doesn't constrain how the user lays out their handlers.
 */
private class OnFuseCollector : FirDefaultVisitor<Unit, Unit>() {
    val onTypes = mutableListOf<ConeKotlinType>()
    val fuseChildEventTypes = mutableListOf<ConeKotlinType>()

    override fun visitElement(element: FirElement, data: Unit) {
        element.acceptChildren(this, data)
    }

    override fun visitFunctionCall(functionCall: FirFunctionCall, data: Unit) {
        val callableId = functionCall.calleeReference.toResolvedFunctionSymbol()?.callableId
        when (callableId) {
            FusioClassIds.ON -> {
                functionCall.typeArguments.firstOrNull()
                    ?.let { (it as? FirTypeProjectionWithVariance)?.typeRef?.coneType }
                    ?.let(onTypes::add)
            }
            FusioClassIds.FUSE -> {
                functionCall.typeArguments.firstOrNull()
                    ?.let { (it as? FirTypeProjectionWithVariance)?.typeRef?.coneType }
                    ?.let(fuseChildEventTypes::add)
            }
        }
        // Continue descending so on<>'s lambda body or fuse's lambda body
        // (which itself can contain further fuses for nested presenters)
        // are also visited.
        super.visitFunctionCall(functionCall, data)
    }
}
