package com.kitakkun.aria.compiler.compat.k2320

import com.kitakkun.aria.compiler.compat.CompatContext
import com.kitakkun.aria.compiler.compat.Version
import com.kitakkun.aria.compiler.compat.VersionRange
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

/**
 * Kotlin 2.3.x [CompatContext]. Targets 2.3.0 through (excluding) 2.4.0.
 *
 * API specifics used:
 * - `FirAnnotation.getKClassArgument(name, session)` — 2.3 requires the session
 *   parameter; 2.4 dropped it.
 * - `IrCall.arguments[i] = expr`, `IrCall.typeArguments[i] = type` — list-access
 *   writes stabilised in 2.3; earlier versions used `putValueArgument` /
 *   `putTypeArgument`.
 */
class CompatContextImpl : CompatContext {
    override fun FirAnnotation.kclassArg(name: Name, session: FirSession): ConeKotlinType? =
        getKClassArgument(name, session)

    override fun IrFunctionAccessExpression.setArg(index: Int, expr: IrExpression) {
        arguments[index] = expr
    }

    override fun IrFunctionAccessExpression.setTypeArg(index: Int, type: IrType) {
        typeArguments[index] = type
    }

    override fun CompilerPluginRegistrar.ExtensionStorage.registerFirExtension(registrar: FirExtensionRegistrar) {
        FirExtensionRegistrarAdapter.registerExtension(registrar)
    }

    override fun CompilerPluginRegistrar.ExtensionStorage.registerIrGenerationExtension(extension: IrGenerationExtension) {
        IrGenerationExtension.registerExtension(extension)
    }

    /**
     * Discovered by [java.util.ServiceLoader] via the META-INF/services
     * registration shipped in this module's resources.
     */
    class Factory : CompatContext.Factory {
        override val supportedRange: VersionRange = VersionRange(
            min = Version(2, 3, 0),
            max = Version(2, 4, 0),
        )

        override fun create(): CompatContext = CompatContextImpl()
    }
}
