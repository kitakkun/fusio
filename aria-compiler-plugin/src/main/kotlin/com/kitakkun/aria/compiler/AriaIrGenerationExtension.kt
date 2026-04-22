package com.kitakkun.aria.compiler

import com.kitakkun.aria.compiler.compat.CompatContextResolver
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

class AriaIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val compat = CompatContextResolver.resolve()
        moduleFragment.transform(MappedScopeTransformer(pluginContext, compat), null)
    }
}
