package com.github.kitakkun.aria.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@Suppress("DEPRECATION")
class MappedScopeTransformer(
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoidWithContext() {

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        if (callee.name != Name.identifier("mappedScope")) {
            return super.visitCall(expression)
        }

        // Verify it's our mappedScope
        val parentFqName = callee.parent.kotlinFqName
        if (parentFqName != FqName("com.github.kitakkun.aria")) {
            return super.visitCall(expression)
        }

        // TODO: Full IR transformation will be implemented incrementally.
        // For now, let the stub function run (it will throw with a clear error message).
        // This allows the FIR checkers to be tested first.
        return super.visitCall(expression)
    }
}
