package com.kitakkun.fusio.compiler

import com.kitakkun.fusio.compiler.compat.CompatContextResolver
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

class FusioIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val compat = CompatContextResolver.resolve()
        moduleFragment.transform(MappedScopeTransformer(pluginContext, compat), null)
    }
}
