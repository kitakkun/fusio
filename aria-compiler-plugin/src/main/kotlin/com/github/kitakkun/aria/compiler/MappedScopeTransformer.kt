package com.github.kitakkun.aria.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
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
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@OptIn(UnsafeDuringIrConstructionAPI::class)
class MappedScopeTransformer(
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoidWithContext() {

    private val finder by lazy { pluginContext.finderForBuiltins() }

    private val presenterScopeClass: IrClassSymbol by lazy {
        finder.findClass(AriaClassIds.PRESENTER_SCOPE)!!
    }

    private val presenterScopeConstructor: IrConstructorSymbol by lazy {
        finder.findConstructors(AriaClassIds.PRESENTER_SCOPE).single()
    }

    private val presenterScopeEventFlowGetter: IrSimpleFunctionSymbol by lazy {
        presenterScopeClass.owner.properties
            .first { it.name == Name.identifier("eventFlow") }
            .getter!!.symbol
    }

    private val ariaClass: IrClassSymbol by lazy {
        finder.findClass(AriaClassIds.ARIA)!!
    }

    private val ariaStateGetter: IrSimpleFunctionSymbol by lazy {
        ariaClass.owner.properties
            .first { it.name == Name.identifier("state") }
            .getter!!.symbol
    }

    private val mapEventsFn: IrSimpleFunctionSymbol by lazy {
        finder.findFunctions(AriaClassIds.MAP_EVENTS).single()
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        if (callee.name != Name.identifier("mappedScope")) {
            return super.visitCall(expression)
        }

        val parentFqName = callee.parent.kotlinFqName
        if (parentFqName != FqName("com.github.kitakkun.aria")) {
            return super.visitCall(expression)
        }

        return transformMappedScope(expression)
    }

    private fun transformMappedScope(call: IrCall): IrExpression {
        val childEventType = call.typeArguments[0]!!
        val childEffectType = call.typeArguments[1]!!
        val childStateType = call.typeArguments[2]!!

        val parentScopeExpr = call.arguments[0]!!
        val lambdaExpr = call.arguments[1]!! as IrFunctionExpression

        // Extract ParentEvent type from the concrete type of the extension receiver.
        // parentScopeExpr.type should be PresenterScope<ParentEvent, ParentEffect>.
        val parentScopeType = parentScopeExpr.type as? IrSimpleType
        val parentEventType: IrType? = parentScopeType?.arguments?.getOrNull(0)?.typeOrNull

        val currentSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, currentSymbol, call.startOffset, call.endOffset)

        return builder.irBlock(resultType = childStateType) {
            val parentScopeVar = irTemporary(parentScopeExpr, nameHint = "ariaParentScope")

            // parentScope.eventFlow
            val parentEventFlowCall = irCall(presenterScopeEventFlowGetter).also {
                it.arguments[0] = irGet(parentScopeVar)
            }

            // Try to build an event-mapping pipeline: parentScope.eventFlow.mapEvents { ... }
            // If we can't resolve the parent event type or find any @MapTo mappings, fall
            // back to passing the flow through unchanged (the child scope's on<> handlers
            // simply won't match anything).
            val childEventFlowExpr: IrExpression = if (parentEventType != null) {
                val mapperLambda = buildEventMapperLambda(
                    parentEventType = parentEventType,
                    childEventType = childEventType,
                    parent = currentDeclarationParent!!,
                )
                if (mapperLambda != null) {
                    val flowOfChild = pluginContext.irBuiltIns.anyType // placeholder — we'll let Flow<ChildEvent> be inferred structurally
                    irCall(mapEventsFn).also { mc ->
                        mc.typeArguments[0] = parentEventType
                        mc.typeArguments[1] = childEventType
                        mc.arguments[0] = parentEventFlowCall  // extension receiver
                        mc.arguments[1] = mapperLambda          // mapper arg
                    }
                } else {
                    parentEventFlowCall
                }
            } else {
                parentEventFlowCall
            }
            val childEventFlowVar = irTemporary(childEventFlowExpr, nameHint = "ariaChildEventFlow")

            // val childScope = PresenterScope<ChildEvent, ChildEffect>(childEventFlow)
            val childScopeType = presenterScopeClass.typeWith(childEventType, childEffectType)
            val childScopeCall = irCall(presenterScopeConstructor, childScopeType).also {
                it.typeArguments[0] = childEventType
                it.typeArguments[1] = childEffectType
                it.arguments[0] = irGet(childEventFlowVar)
            }
            val childScopeVar = irTemporary(childScopeCall, nameHint = "ariaChildScope")

            // val childResult = lambda.invoke(childScope)
            val functionClass = lambdaExpr.type.classOrNull!!
            val invokeFun = functionClass.owner.functions
                .single { it.name == Name.identifier("invoke") }
            val ariaInstanceType = ariaClass.typeWith(childStateType, childEffectType)
            val childResultCall = irCall(invokeFun.symbol, ariaInstanceType).also { ic ->
                ic.arguments[0] = lambdaExpr
                ic.arguments[1] = irGet(childScopeVar)
            }
            val childResultVar = irTemporary(childResultCall, nameHint = "ariaChildResult")

            // childResult.state
            +irCall(ariaStateGetter, childStateType).also {
                it.arguments[0] = irGet(childResultVar)
            }
        }
    }

    /**
     * Build an IrFunctionExpression of type Function1<ParentEvent, ChildEvent?> whose body
     * is a when-expression dispatching on the @MapTo annotations found on the sealed
     * subtypes of [parentEventType].
     *
     * Returns null if no @MapTo annotations are found.
     */
    private fun buildEventMapperLambda(
        parentEventType: IrType,
        childEventType: IrType,
        parent: IrDeclarationParent,
    ): IrFunctionExpression? {
        val parentEventClass = parentEventType.classOrNull?.owner ?: return null

        data class Mapping(
            val parentSubtypeClass: IrClassSymbol,
            val childSubtypeClass: IrClassSymbol,
        )

        val childEventClassId = childEventType.classOrNull?.owner?.classId
        val mappings = parentEventClass.sealedSubclasses.mapNotNull { parentSubSymbol ->
            val parentSub = parentSubSymbol.owner
            val ann = parentSub.annotations.firstOrNull {
                it.symbol.owner.parentAsClassIdOrNull == AriaClassIds.MAP_TO
            } ?: return@mapNotNull null
            val targetArg = ann.arguments.firstOrNull() as? IrClassReference ?: return@mapNotNull null
            val targetSymbol = targetArg.classType.classOrNull ?: return@mapNotNull null
            // Only include mappings whose target belongs to the child event sealed hierarchy
            val targetClass = targetSymbol.owner
            val belongsToChildHierarchy = childEventClassId == null ||
                targetClass.classId == childEventClassId ||
                targetClass.superTypes.any { it.classOrNull?.owner?.classId == childEventClassId }
            if (!belongsToChildHierarchy) return@mapNotNull null
            Mapping(parentSubSymbol, targetSymbol)
        }
        if (mappings.isEmpty()) return null

        val nullableChildType = childEventType.makeNullable()
        val function1Class = pluginContext.irBuiltIns.functionN(1)
        val functionType = function1Class.typeWith(parentEventType, nullableChildType)

        val lambdaFun = pluginContext.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = nullableChildType
        }.apply {
            this.parent = parent
            val param = addValueParameter("it", parentEventType)
            val innerBuilder = DeclarationIrBuilder(pluginContext, symbol)
            body = innerBuilder.irBlockBody {
                val branches = mutableListOf<org.jetbrains.kotlin.ir.expressions.IrBranch>()
                for (m in mappings) {
                    // Find the default (no-arg or primary) constructor to call.
                    val targetClass = m.childSubtypeClass.owner
                    val primaryCtor = targetClass.constructors.firstOrNull { it.isPrimary }
                        ?: targetClass.constructors.firstOrNull()
                        ?: continue
                    // Build constructor call. For now, copy matching properties by name
                    // from the parent subtype instance to the child subtype constructor.
                    val parentSub = m.parentSubtypeClass.owner
                    val parentProps = parentSub.properties.associateBy { it.name.asString() }
                    val ctorArgs = primaryCtor.parameters.map { p ->
                        val parentProp = parentProps[p.name.asString()]
                        if (parentProp?.getter != null) {
                            innerBuilder.irCall(parentProp.getter!!.symbol).also { g ->
                                g.arguments[0] = innerBuilder.irGet(param)
                                    // we need to cast the parameter to the parent subtype first
                            }
                        } else {
                            null
                        }
                    }
                    // If any arg is null, we can't build this mapping safely; skip.
                    if (ctorArgs.any { it == null }) continue

                    val ctorCall = innerBuilder.irCall(primaryCtor.symbol, targetClass.defaultTypeWithArgsOrNull() ?: continue).also { c ->
                        for ((i, a) in ctorArgs.withIndex()) {
                            c.arguments[i] = a
                        }
                    }
                    branches.add(
                        IrBranchImpl(
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            condition = innerBuilder.irIs(innerBuilder.irGet(param), m.parentSubtypeClass.owner.defaultType),
                            result = ctorCall,
                        )
                    )
                }
                // else -> null
                branches.add(innerBuilder.irElseBranch(innerBuilder.irNull(nullableChildType)))
                +irReturn(innerBuilder.irWhen(nullableChildType, branches))
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

    // Utility: parent class id from a constructor-call target (used on IrConstructorCall.symbol).
    private val org.jetbrains.kotlin.ir.declarations.IrConstructor.parentAsClassIdOrNull: ClassId?
        get() = (this.parent as? org.jetbrains.kotlin.ir.declarations.IrClass)?.classId

    private fun org.jetbrains.kotlin.ir.declarations.IrClass.defaultTypeWithArgsOrNull(): IrType? {
        return if (typeParameters.isEmpty()) this.defaultType else null
    }
}

private const val UNDEFINED_OFFSET: Int = -1
