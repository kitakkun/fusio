package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContext
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Rewrites every `fuse<ChildEvent, ChildEffect, ChildState> { block }`
 * call site into inline IR that plumbs events in and effects out between
 * the parent [PresenterScope] and the child one that [block] runs
 * against.
 *
 * Generated shape (pseudocode):
 * ```
 * run {
 *   val childEventFlow = parentScope.eventFlow.mapEvents { @MapTo-based when }
 *   val childScope = PresenterScope<ChildEvent, ChildEffect>(childEventFlow)
 *   forwardEffects(childScope, parentScope) { @MapFrom-based when }  // optional
 *   block.invoke(childScope)  // returns ChildState directly
 * }
 * ```
 *
 * The two when-lambdas are synthesised by [buildMapperLambda] from the
 * `@MapTo` / `@MapFrom` annotations on the parent event/effect sealed subtypes.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class FuseTransformer(
    private val pluginContext: IrPluginContext,
    compat: CompatContext,
) : IrElementTransformerVoidWithContext(),
    CompatContext by compat {

    // Lookups route through CompatContext (findClass / findConstructors /
    // findFunctions) so the shaded plugin jar works on the whole 2.3.0+ range:
    // on 2.3.20+ the k2320 / k240_beta2 impls use the new `finderForBuiltins`
    // API, on 2.3.0 / 2.3.10 the k230 impl falls back to the legacy
    // `referenceClass` / `referenceConstructors` / `referenceFunctions`.
    private val presenterScopeClass: IrClassSymbol by lazy {
        pluginContext.findClass(FusioClassIds.PRESENTER_SCOPE)!!
    }
    private val presenterScopeConstructor: IrConstructorSymbol by lazy {
        pluginContext.findConstructors(FusioClassIds.PRESENTER_SCOPE).single()
    }
    private val presenterScopeEventFlowGetter: IrSimpleFunctionSymbol by lazy {
        presenterScopeClass.owner.properties
            .first { it.name == Name.identifier("eventFlow") }
            .getter!!.symbol
    }
    private val mapEventsFn: IrSimpleFunctionSymbol by lazy {
        pluginContext.findFunctions(FusioClassIds.MAP_EVENTS).single()
    }
    private val forwardEffectsFn: IrSimpleFunctionSymbol by lazy {
        pluginContext.findFunctions(FusioClassIds.FORWARD_EFFECTS).single()
    }
    private val forwardEventErrorsFn: IrSimpleFunctionSymbol by lazy {
        pluginContext.findFunctions(FusioClassIds.FORWARD_EVENT_ERRORS).single()
    }
    private val rememberFn: IrSimpleFunctionSymbol by lazy {
        // `remember` in androidx.compose.runtime has five overloads (0–4 explicit
        // keys + vararg). We want the zero-key form: one parameter, the
        // `calculation: () -> T` lambda.
        pluginContext.findFunctions(FusioClassIds.COMPOSE_REMEMBER)
            .single { it.owner.parameters.size == 1 }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        if (callee.name != FUSE_NAME) return super.visitCall(expression)
        if (callee.parent.kotlinFqName != FusioClassIds.FUSIO_PACKAGE) return super.visitCall(expression)
        return transformFuse(expression)
    }

    private fun transformFuse(call: IrCall): IrExpression {
        val childEventType = call.typeArguments[0]!!
        val childEffectType = call.typeArguments[1]!!
        val childStateType = call.typeArguments[2]!!

        val parentScopeExpr = call.arguments[0]!!
        val lambdaExpr = call.arguments[1]!! as IrFunctionExpression

        // fuse is declared on PresenterScope<*, *>, so its extension receiver's
        // declared type is star-projected. But the concrete type at the call site
        // carries the real ParentEvent/ParentEffect arguments — we read them here.
        val parentScopeType = parentScopeExpr.type as? IrSimpleType
        val parentEventType: IrType? = parentScopeType?.arguments?.getOrNull(0)?.typeOrNull
        val parentEffectType: IrType? = parentScopeType?.arguments?.getOrNull(1)?.typeOrNull

        val currentSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, currentSymbol, call.startOffset, call.endOffset)
        val parentDecl = currentDeclarationParent!!

        return builder.irBlock(resultType = childStateType) {
            val parentScopeVar = irTemporary(parentScopeExpr, nameHint = "fusioParentScope")
            val childScopeVar = buildChildScope(
                parentScopeVar = parentScopeVar,
                parentEventType = parentEventType,
                childEventType = childEventType,
                childEffectType = childEffectType,
                parentDecl = parentDecl,
            )
            emitEffectForwarding(
                parentScopeVar = parentScopeVar,
                childScopeVar = childScopeVar,
                parentEffectType = parentEffectType,
                childEffectType = childEffectType,
                parentDecl = parentDecl,
            )
            emitEventErrorForwarding(
                parentScopeVar = parentScopeVar,
                childScopeVar = childScopeVar,
            )
            +invokeLambda(lambdaExpr, childScopeVar, childStateType)
        }
    }

    /**
     * Builds and declares:
     * ```
     * val childScope = remember {
     *     val childEventFlow = parentScope.eventFlow.mapEvents { @MapTo when }
     *     PresenterScope<ChildEvent, ChildEffect>(childEventFlow)
     * }
     * ```
     *
     * Wrapping the allocation in `remember { ... }` gives the child scope a
     * stable identity across parent recompositions. Without this the child
     * scope would be re-allocated every frame; the `LaunchedEffect`s inside
     * `forwardEffects` / `forwardEventErrors` that capture it at first
     * invocation would then observe a scope the child's `on<>` handlers
     * stop publishing into (the handlers re-capture the fresh `this` each
     * recomposition — a bug that surfaces as "effects emitted after the
     * second send never reach the scenario").
     */
    private fun IrBlockBuilder.buildChildScope(
        parentScopeVar: IrVariable,
        parentEventType: IrType?,
        childEventType: IrType,
        childEffectType: IrType,
        parentDecl: IrDeclarationParent,
    ): IrVariable {
        val childScopeType = presenterScopeClass.typeWith(childEventType, childEffectType)

        // The `remember`'s calculation lambda: () -> PresenterScope<ChildEvent, ChildEffect>.
        val calculationType = pluginContext.irBuiltIns.functionN(0).typeWith(childScopeType)

        val calculationFun = pluginContext.irFactory.buildFun {
            origin = localFunctionForLambdaOrigin
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = childScopeType
        }.apply {
            this.parent = parentDecl
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                val parentEventFlowCall = irCall(presenterScopeEventFlowGetter).also {
                    it.setArg(0, irGet(parentScopeVar))
                }

                val childEventFlowExpr: IrExpression = parentEventType
                    ?.let { pEvent ->
                        val mapper = buildMapperLambda(
                            fromType = pEvent,
                            toType = childEventType,
                            parent = this@apply,
                            annotationClassId = FusioClassIds.MAP_TO,
                            fromClass = pEvent.classOrNull?.owner,
                            // Filter by the child event root so sibling fuse
                            // calls don't poach each other's @MapTo annotations.
                            expectedOtherClass = childEventType.classOrNull?.owner,
                        )
                        if (mapper != null) {
                            irCall(mapEventsFn).also { mc ->
                                mc.setTypeArg(0, pEvent)
                                mc.setTypeArg(1, childEventType)
                                mc.setArg(0, parentEventFlowCall)
                                mc.setArg(1, mapper)
                            }
                        } else {
                            null
                        }
                    }
                    ?: parentEventFlowCall

                val childEventFlowVar = irTemporary(childEventFlowExpr, nameHint = "fusioChildEventFlow")

                val childScopeCall = irCall(presenterScopeConstructor, childScopeType).also {
                    it.setTypeArg(0, childEventType)
                    it.setTypeArg(1, childEffectType)
                    it.setArg(0, irGet(childEventFlowVar))
                }
                +irReturn(childScopeCall)
            }
        }

        val calculationExpr = IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = calculationType,
            function = calculationFun,
            origin = IrStatementOrigin.LAMBDA,
        )

        val rememberCall = irCall(rememberFn, childScopeType).also {
            it.setTypeArg(0, childScopeType)
            it.setArg(0, calculationExpr)
        }
        return irTemporary(rememberCall, nameHint = "fusioChildScope")
    }

    /**
     * Inserts `forwardEffects(childScope, parentScope) { @MapFrom when }` when the
     * parent effect type resolves and at least one `@MapFrom` mapping is found.
     * Must run BEFORE the lambda is invoked so LaunchedEffect is registered in the
     * same composition scope as the child presenter body.
     */
    private fun IrBlockBuilder.emitEffectForwarding(
        parentScopeVar: IrVariable,
        childScopeVar: IrVariable,
        parentEffectType: IrType?,
        childEffectType: IrType,
        parentDecl: IrDeclarationParent,
    ) {
        if (parentEffectType == null) return

        // @MapFrom sits on the PARENT effect subtypes and the annotation's argument
        // is the CHILD effect subtype. buildMapperLambda walks sealed subclasses of
        // `fromClass`, so we pass the parent class and set reverseDirection=true to
        // flip the from/to roles in each Mapping.
        val mapper = buildMapperLambda(
            fromType = childEffectType,
            toType = parentEffectType,
            parent = parentDecl,
            annotationClassId = FusioClassIds.MAP_FROM,
            fromClass = parentEffectType.classOrNull?.owner,
            reverseDirection = true,
            // Same sibling-isolation filter as the event side — a parent's
            // @MapFrom that points at a different child-effect tree is not
            // this fuse's concern.
            expectedOtherClass = childEffectType.classOrNull?.owner,
        ) ?: return

        +irCall(forwardEffectsFn).also { fc ->
            fc.setTypeArg(0, childEffectType)
            fc.setTypeArg(1, parentEffectType)
            fc.setArg(0, irGet(childScopeVar))
            fc.setArg(1, irGet(parentScopeVar))
            fc.setArg(2, mapper)
        }
    }

    /**
     * Inserts `forwardEventErrors(childScope, parentScope)` unconditionally.
     * Unlike effect forwarding, this isn't gated on `@MapFrom` annotations —
     * a handler crash in a child scope should always bubble up to the parent
     * so a single root-level observer sees every swallowed exception in the
     * tree. `Throwable` flows through unchanged, so no mapper lambda.
     */
    private fun IrBlockBuilder.emitEventErrorForwarding(
        parentScopeVar: IrVariable,
        childScopeVar: IrVariable,
    ) {
        +irCall(forwardEventErrorsFn).also { fc ->
            fc.setArg(0, irGet(childScopeVar))
            fc.setArg(1, irGet(parentScopeVar))
        }
    }

    /**
     * Calls the user's lambda via [Function1.invoke] rather than calling the
     * lambda function symbol directly. Direct invocation crashes JVM codegen
     * because the backend can't inline raw lambda bodies into an arbitrary call
     * site; going through the FunctionN interface uses the standard lambda-
     * invocation convention and composes cleanly with what Compose does to
     * @Composable lambdas.
     */
    private fun IrBlockBuilder.invokeLambda(
        lambdaExpr: IrFunctionExpression,
        childScopeVar: IrVariable,
        childStateType: IrType,
    ): IrExpression {
        val functionClass = lambdaExpr.type.classOrNull!!
        val invokeFun = functionClass.owner.functions.single { it.name == Name.identifier("invoke") }
        return irCall(invokeFun.symbol, childStateType).also { ic ->
            ic.setArg(0, lambdaExpr)
            ic.setArg(1, irGet(childScopeVar))
        }
    }

    /**
     * Builds an `IrFunctionExpression` of type `(From) -> To?` whose body is a
     * `when` expression dispatching over the sealed subclasses of [fromClass].
     *
     * For each annotated subclass (by [annotationClassId]), the annotation's
     * first argument (a `KClass<*>` reference) names the "other side" of the
     * mapping. In the normal direction (e.g. `@MapTo`), the annotated subclass
     * IS the from-subtype and the annotation argument IS the to-subtype. In
     * [reverseDirection] mode (e.g. `@MapFrom`), the roles flip: the annotated
     * subclass is the to-subtype and the annotation argument is the from-subtype.
     *
     * Target constructor args are copied from the from-subtype's matching
     * same-named properties. FIR checkers should already have guaranteed the
     * name/type match; here we silently skip any that fail to line up.
     */
    private fun buildMapperLambda(
        fromType: IrType,
        toType: IrType,
        parent: IrDeclarationParent,
        annotationClassId: ClassId,
        fromClass: IrClass?,
        reverseDirection: Boolean = false,
        expectedOtherClass: IrClass? = null,
    ): IrFunctionExpression? {
        if (fromClass == null) return null

        val mappings = collectMappings(fromClass, annotationClassId, reverseDirection, expectedOtherClass)
        if (mappings.isEmpty()) return null

        val nullableToType = toType.makeNullable()
        val functionType = pluginContext.irBuiltIns.functionN(1).typeWith(fromType, nullableToType)

        val lambdaFun = pluginContext.irFactory.buildFun {
            // Routed through CompatContext so the underlying companion call is
            // emitted in per-version bytecode — 2.3.0's companion getter returns
            // `IrDeclarationOriginImpl`, 2.3.20+ returns `IrDeclarationOrigin`.
            origin = localFunctionForLambdaOrigin
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = nullableToType
        }.apply {
            this.parent = parent
            val param = addValueParameter("it", fromType)
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                +irReturn(buildWhen(param, mappings, nullableToType))
            }
        }

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = functionType,
            function = lambdaFun,
            origin = IrStatementOrigin.LAMBDA,
        )
    }

    private fun collectMappings(
        fromClass: IrClass,
        annotationClassId: ClassId,
        reverseDirection: Boolean,
        expectedOtherClass: IrClass?,
    ): List<Mapping> = buildList {
        for (annotatedSubSymbol in fromClass.sealedSubclasses) {
            val annotation = annotatedSubSymbol.owner.annotations.firstOrNull {
                (it.symbol.owner.parent as? IrClass)?.classId == annotationClassId
            } ?: continue
            val kclassRef = annotation.arguments.firstOrNull() as? IrClassReference ?: continue
            val otherSymbol = kclassRef.classType.classOrNull ?: continue

            // Siblings at the same parent level can @MapTo / @MapFrom different
            // child trees (Favorite's subtypes vs Wallet's subtypes, for
            // example). Skip annotations whose "other side" lives in a tree
            // this fuse doesn't own — otherwise the generated when-
            // lambda tries to return Wallet* from a Favorite?-typed branch
            // and JVM checkcast-fails at runtime.
            if (expectedOtherClass != null &&
                !otherSymbol.owner.isSubclassOfOrEqual(expectedOtherClass)
            ) {
                continue
            }

            val (fromSub, toSub) = if (reverseDirection) {
                otherSymbol to annotatedSubSymbol
            } else {
                annotatedSubSymbol to otherSymbol
            }
            add(Mapping(fromSub, toSub))
        }
    }

    /**
     * Walks the [IrClass.superTypes] graph looking for [target]. Sealed
     * hierarchies in Fusio use both classes and interfaces as parent types,
     * so a plain instanceof check isn't enough — we recurse through each
     * supertype's class symbol until we hit [target] or run out of parents.
     */
    private fun IrClass.isSubclassOfOrEqual(target: IrClass): Boolean {
        if (this == target) return true
        if (classId == target.classId) return true
        return superTypes.any { st ->
            val owner = st.classOrNull?.owner ?: return@any false
            owner.isSubclassOfOrEqual(target)
        }
    }

    private fun IrBuilderWithScope.buildWhen(
        param: org.jetbrains.kotlin.ir.declarations.IrValueParameter,
        mappings: List<Mapping>,
        nullableToType: IrType,
    ): IrExpression {
        val branches = mutableListOf<org.jetbrains.kotlin.ir.expressions.IrBranch>()
        for (m in mappings) {
            val result = buildMappingResult(param, m) ?: continue
            branches += IrBranchImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                condition = irIs(irGet(param), m.fromSubtypeClass.owner.defaultType),
                result = result,
            )
        }
        branches += irElseBranch(irNull(nullableToType))
        return irWhen(nullableToType, branches)
    }

    /**
     * Constructs the to-subtype instance:
     * - `object` / `data object` → `IrGetObjectValue` (singleton)
     * - ordinary class → call primary constructor with args copied from
     *   same-named getters on the from-subtype cast of `it`.
     * Returns null (and the caller skips the branch) when we can't line up args
     * — the FIR checker should already have flagged that case at compile time.
     */
    private fun IrBuilderWithScope.buildMappingResult(
        param: org.jetbrains.kotlin.ir.declarations.IrValueParameter,
        m: Mapping,
    ): IrExpression? {
        val toClass = m.toSubtypeClass.owner
        if (toClass.typeParameters.isNotEmpty()) return null

        if (toClass.isObject) return irGetObject(m.toSubtypeClass)

        val primaryCtor = toClass.constructors.firstOrNull { it.isPrimary }
            ?: toClass.constructors.firstOrNull()
            ?: return null

        val fromProps = m.fromSubtypeClass.owner.properties.associateBy { it.name.asString() }
        val ctorArgs = primaryCtor.parameters.map { p ->
            val prop = fromProps[p.name.asString()] ?: return null
            val getter = prop.getter ?: return null
            irCall(getter.symbol).also { g -> g.setArg(0, irGet(param)) }
        }

        return irCall(primaryCtor.symbol, toClass.defaultType).also { c ->
            for ((i, a) in ctorArgs.withIndex()) c.setArg(i, a)
        }
    }

    private data class Mapping(
        val fromSubtypeClass: IrClassSymbol,
        val toSubtypeClass: IrClassSymbol,
    )

    private companion object {
        val FUSE_NAME: Name = FusioClassIds.FUSE.callableName
    }
}
