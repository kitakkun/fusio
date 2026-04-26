package com.kitakkun.fusio.compiler.compat.k2320

import com.kitakkun.fusio.compiler.compat.CompatContext
import com.kitakkun.fusio.compiler.compat.SubPresenterAnalyzer
import com.kitakkun.fusio.compiler.compat.Version
import com.kitakkun.fusio.compiler.compat.VersionRange
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Kotlin 2.3.20+ [CompatContext]. Targets 2.3.20 through (excluding) 2.4.0.
 *
 * API specifics used:
 * - `FirAnnotation.getKClassArgument(name, session)` — 2.3 requires the session
 *   parameter; 2.4 dropped it.
 * - `IrCall.arguments[i] = expr`, `IrCall.typeArguments[i] = type` — list-access
 *   writes stabilised in 2.3; earlier versions used `putValueArgument` /
 *   `putTypeArgument`.
 * - `IrPluginContext.finderForBuiltins()` — added in 2.3.20; older 2.3 patches
 *   are handled by the sibling `:k230` impl.
 */
class CompatContextImpl : CompatContext {
    override fun FirAnnotation.kclassArg(name: Name, session: FirSession): ConeKotlinType? = getKClassArgument(name, session)

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

    override fun IrPluginContext.findClass(classId: ClassId): IrClassSymbol? = finderForBuiltins().findClass(classId)

    override fun IrPluginContext.findConstructors(classId: ClassId): Collection<IrConstructorSymbol> = finderForBuiltins().findConstructors(classId)

    override fun IrPluginContext.findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol> = finderForBuiltins().findFunctions(callableId)

    override val localFunctionForLambdaOrigin: IrDeclarationOrigin
        get() = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA

    // 2.3.20+ renamed the FirSimpleFunctionChecker.check parameter from
    // FirSimpleFunction to FirNamedFunction. Bytecode here references
    // FirNamedFunction; the matching k230 impl uses the older name.
    override fun createSubPresenterChecker(analyzer: SubPresenterAnalyzer): FirSimpleFunctionChecker = K2320SubPresenterChecker(analyzer)

    private class K2320SubPresenterChecker(
        private val analyzer: SubPresenterAnalyzer,
    ) : FirSimpleFunctionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirNamedFunction) {
            with(analyzer) {
                analyze(
                    receiverType = declaration.receiverParameter?.typeRef?.coneType,
                    body = declaration.body,
                    source = declaration.source,
                )
            }
        }
    }

    /**
     * Discovered by [java.util.ServiceLoader] via the META-INF/services
     * registration shipped in this module's resources.
     */
    class Factory : CompatContext.Factory {
        override val supportedRange: VersionRange = VersionRange(
            min = Version(2, 3, 20),
            max = Version(2, 4, 0),
        )

        override fun create(): CompatContext = CompatContextImpl()
    }
}
