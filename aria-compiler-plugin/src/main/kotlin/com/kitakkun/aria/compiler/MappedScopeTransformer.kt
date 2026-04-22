package com.kitakkun.aria.compiler

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
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
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
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private const val UNDEFINED_OFFSET: Int = -1

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

    private val forwardEffectsFn: IrSimpleFunctionSymbol by lazy {
        finder.findFunctions(AriaClassIds.FORWARD_EFFECTS).single()
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        if (callee.name != Name.identifier("mappedScope")) {
            return super.visitCall(expression)
        }

        val parentFqName = callee.parent.kotlinFqName
        if (parentFqName != FqName("com.kitakkun.aria")) {
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

        // Extract ParentEvent / ParentEffect types from the concrete type of the
        // extension receiver. parentScopeExpr.type should be PresenterScope<ParentEvent, ParentEffect>.
        val parentScopeType = parentScopeExpr.type as? IrSimpleType
        val parentEventType: IrType? = parentScopeType?.arguments?.getOrNull(0)?.typeOrNull
        val parentEffectType: IrType? = parentScopeType?.arguments?.getOrNull(1)?.typeOrNull

        val currentSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, currentSymbol, call.startOffset, call.endOffset)
        val parentDecl = currentDeclarationParent!!

        return builder.irBlock(resultType = childStateType) {
            val parentScopeVar = irTemporary(parentScopeExpr, nameHint = "ariaParentScope")

            // parentScope.eventFlow
            val parentEventFlowCall = irCall(presenterScopeEventFlowGetter).also {
                it.arguments[0] = irGet(parentScopeVar)
            }

            // Optionally wrap with mapEvents(mapper) if @MapTo mappings exist.
            val childEventFlowExpr: IrExpression = if (parentEventType != null) {
                val mapperLambda = buildMapperLambda(
                    fromType = parentEventType,
                    toType = childEventType,
                    parent = parentDecl,
                    annotationClassId = AriaClassIds.MAP_TO,
                    fromClass = parentEventType.classOrNull?.owner,
                    targetExtractor = { ann -> ann.arguments.firstOrNull() as? IrClassReference },
                )
                if (mapperLambda != null) {
                    irCall(mapEventsFn).also { mc ->
                        mc.typeArguments[0] = parentEventType
                        mc.typeArguments[1] = childEventType
                        mc.arguments[0] = parentEventFlowCall  // extension receiver
                        mc.arguments[1] = mapperLambda
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

            // Optionally wire up effect forwarding via @MapFrom mappings.
            // Must be inserted BEFORE invoking the lambda so the LaunchedEffect is
            // registered in the same composition as the child presenter's body.
            if (parentEffectType != null) {
                val effectMapperLambda = buildMapperLambda(
                    fromType = childEffectType,
                    toType = parentEffectType,
                    parent = parentDecl,
                    annotationClassId = AriaClassIds.MAP_FROM,
                    // For @MapFrom, the annotation lives on the PARENT effect subtypes
                    // and its argument is the CHILD effect subtype. But our generic
                    // builder walks sealed subclasses of `fromClass` (child). Here we
                    // swap: walk parent effect sealed subclasses instead.
                    fromClass = parentEffectType.classOrNull?.owner,
                    targetExtractor = { ann -> ann.arguments.firstOrNull() as? IrClassReference },
                    reverseDirection = true,
                )
                if (effectMapperLambda != null) {
                    +irCall(forwardEffectsFn).also { fc ->
                        fc.typeArguments[0] = childEffectType
                        fc.typeArguments[1] = parentEffectType
                        fc.arguments[0] = irGet(childScopeVar)
                        fc.arguments[1] = irGet(parentScopeVar)
                        fc.arguments[2] = effectMapperLambda
                    }
                }
            }

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
     * Generic builder for a mapping lambda `(From) -> To?`.
     *
     * Walks the sealed subclasses of [fromClass] looking for [annotationClassId]
     * annotations. Each annotation's first argument (KClass reference) is the
     * "other side" of the mapping. Property values are copied from the source
     * instance into the target constructor by parameter name.
     *
     * For @MapTo (events): annotation sits on parent subtypes, target is child.
     *   → fromClass=parentEventClass, toType=childEventType, reverseDirection=false
     *     (builder treats the annotated class itself as the from-subtype, target as to-subtype)
     *
     * For @MapFrom (effects): annotation sits on parent subtypes, source is child.
     *   → fromClass=parentEffectClass (but we want source=child), reverseDirection=true
     *     (builder treats the annotated class as the to-subtype, target as from-subtype)
     */
    private fun buildMapperLambda(
        fromType: IrType,
        toType: IrType,
        parent: IrDeclarationParent,
        annotationClassId: ClassId,
        fromClass: IrClass?,
        targetExtractor: (org.jetbrains.kotlin.ir.expressions.IrConstructorCall) -> IrClassReference?,
        reverseDirection: Boolean = false,
    ): IrFunctionExpression? {
        if (fromClass == null) return null

        data class Mapping(
            val fromSubtypeClass: IrClassSymbol,
            val toSubtypeClass: IrClassSymbol,
        )

        val mappings = mutableListOf<Mapping>()

        // In the normal direction, the annotation lives on [fromClass] sealed subtypes.
        // In the reverse direction (e.g. @MapFrom on parent Effect subtypes), the
        // annotation lives on a different sealed class — we pass THAT as [fromClass]
        // via the caller, then invert the fromSubtype / toSubtype roles here.
        for (annotatedSubSymbol in fromClass.sealedSubclasses) {
            val annotatedSub = annotatedSubSymbol.owner
            val ann = annotatedSub.annotations.firstOrNull {
                (it.symbol.owner.parent as? IrClass)?.classId == annotationClassId
            } ?: continue
            val kclassRef = targetExtractor(ann) ?: continue
            val otherSymbol = kclassRef.classType.classOrNull ?: continue

            val fromSub = if (reverseDirection) otherSymbol else annotatedSubSymbol
            val toSub = if (reverseDirection) annotatedSubSymbol else otherSymbol
            mappings += Mapping(fromSub, toSub)
        }
        if (mappings.isEmpty()) return null

        val nullableToType = toType.makeNullable()
        val function1Class = pluginContext.irBuiltIns.functionN(1)
        val functionType = function1Class.typeWith(fromType, nullableToType)

        val lambdaFun = pluginContext.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            returnType = nullableToType
        }.apply {
            this.parent = parent
            val param = addValueParameter("it", fromType)
            val innerBuilder = DeclarationIrBuilder(pluginContext, symbol)
            body = innerBuilder.irBlockBody {
                val branches = mutableListOf<org.jetbrains.kotlin.ir.expressions.IrBranch>()
                for (m in mappings) {
                    val toClass = m.toSubtypeClass.owner
                    if (toClass.typeParameters.isNotEmpty()) continue
                    val fromSubClass = m.fromSubtypeClass.owner

                    // For `object` / `data object` targets there is no user-accessible
                    // constructor — the singleton is the canonical instance.
                    val result: IrExpression = if (toClass.isObject) {
                        innerBuilder.irGetObject(m.toSubtypeClass)
                    } else {
                        val primaryCtor = toClass.constructors.firstOrNull { it.isPrimary }
                            ?: toClass.constructors.firstOrNull()
                            ?: continue

                        val fromProps = fromSubClass.properties.associateBy { it.name.asString() }
                        val ctorArgs = primaryCtor.parameters.map { p ->
                            val prop = fromProps[p.name.asString()] ?: return@map null
                            val getter = prop.getter ?: return@map null
                            innerBuilder.irCall(getter.symbol).also { g ->
                                g.arguments[0] = innerBuilder.irGet(param)
                            }
                        }
                        if (ctorArgs.any { it == null }) continue

                        innerBuilder.irCall(primaryCtor.symbol, toClass.defaultType).also { c ->
                            for ((i, a) in ctorArgs.withIndex()) {
                                c.arguments[i] = a
                            }
                        }
                    }

                    branches += IrBranchImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        condition = innerBuilder.irIs(innerBuilder.irGet(param), fromSubClass.defaultType),
                        result = result,
                    )
                }
                branches += innerBuilder.irElseBranch(innerBuilder.irNull(nullableToType))
                +irReturn(innerBuilder.irWhen(nullableToType, branches))
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
}
