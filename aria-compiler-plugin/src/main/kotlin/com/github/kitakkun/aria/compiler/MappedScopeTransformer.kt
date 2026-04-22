package com.github.kitakkun.aria.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.properties
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
        // Type arguments: <ChildEvent, ChildEffect, ChildState>
        val childEventType = call.typeArguments[0]!!
        val childEffectType = call.typeArguments[1]!!
        val childStateType = call.typeArguments[2]!!

        // Arguments layout for extension function `PresenterScope<*, *>.mappedScope(block)`:
        //   arguments[0] = extension receiver (parent PresenterScope)
        //   arguments[1] = block lambda (IrFunctionExpression)
        val parentScopeExpr = call.arguments[0]!!
        val lambdaExpr = call.arguments[1]!! as IrFunctionExpression

        val currentSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, currentSymbol, call.startOffset, call.endOffset)

        return builder.irBlock(resultType = childStateType) {
            // val parentScope = <parentScopeExpr>
            val parentScopeVar = irTemporary(parentScopeExpr, nameHint = "ariaParentScope")

            // val childEventFlow = parentScope.eventFlow (no event mapping yet)
            val eventFlowCall = irCall(presenterScopeEventFlowGetter).also {
                it.arguments[0] = irGet(parentScopeVar)
            }
            val childEventFlowVar = irTemporary(eventFlowCall, nameHint = "ariaChildEventFlow")

            // val childScope = PresenterScope<ChildEvent, ChildEffect>(childEventFlow)
            val childScopeType = presenterScopeClass.typeWith(childEventType, childEffectType)
            val childScopeCall = irCall(presenterScopeConstructor, childScopeType).also {
                it.typeArguments[0] = childEventType
                it.typeArguments[1] = childEffectType
                it.arguments[0] = irGet(childEventFlowVar)
            }
            val childScopeVar = irTemporary(childScopeCall, nameHint = "ariaChildScope")

            // val childResult = lambda.invoke(childScope)
            // Instead of calling the lambda function symbol directly (which fails after
            // Compose plugin injects $composer/$changed), we call the FunctionN.invoke()
            // method with the lambda instance as dispatchReceiver.
            val functionClass = lambdaExpr.type.classOrNull!!
            val invokeFun = functionClass.owner.functions
                .single { it.name == Name.identifier("invoke") }
            val ariaInstanceType = ariaClass.typeWith(childStateType, childEffectType)
            val childResultCall = irCall(invokeFun.symbol, ariaInstanceType).also { ic ->
                ic.arguments[0] = lambdaExpr   // dispatch receiver = lambda instance
                ic.arguments[1] = irGet(childScopeVar)  // first real arg = childScope
            }
            val childResultVar = irTemporary(childResultCall, nameHint = "ariaChildResult")

            // childResult.state
            +irCall(ariaStateGetter, childStateType).also {
                it.arguments[0] = irGet(childResultVar)
            }
        }
    }
}
