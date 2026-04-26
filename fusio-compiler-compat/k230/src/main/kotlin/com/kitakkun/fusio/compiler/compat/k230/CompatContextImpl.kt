package com.kitakkun.fusio.compiler.compat.k230

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
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
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
 * Kotlin 2.3.0 – 2.3.19 [CompatContext].
 *
 * Delegation-by-object would save duplication against `:k2320`, but that
 * would force loading k2320's bytecode — which references
 * `finderForBuiltins()` / `DeclarationFinder` — and fail with
 * `NoClassDefFoundError` on 2.3.0 / 2.3.10 even though the delegate's
 * overrides would never be invoked. So every method is re-implemented here
 * against bytecode compiled only against the 2.3.0 surface.
 *
 * The finder trio uses the legacy `referenceClass` / `referenceConstructors`
 * / `referenceFunctions` APIs that predate `DeclarationFinder`.
 */
@Suppress("DEPRECATION")
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

    override fun IrPluginContext.findClass(classId: ClassId): IrClassSymbol? =
        referenceClass(classId)

    override fun IrPluginContext.findConstructors(classId: ClassId): Collection<IrConstructorSymbol> =
        referenceConstructors(classId)

    override fun IrPluginContext.findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol> =
        referenceFunctions(callableId)

    // In 2.3.0 the companion getter's return type is `IrDeclarationOriginImpl`;
    // the implicit upcast to `IrDeclarationOrigin` happens here in 2.3.0 bytecode.
    override val localFunctionForLambdaOrigin: IrDeclarationOrigin
        get() = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA

    // 2.3.0 / 2.3.10 keep the old name `FirSimpleFunction` on the
    // FirSimpleFunctionChecker.check parameter; bytecode compiled here
    // references that name and loads cleanly only on the 2.3.0 line.
    override fun createSubPresenterChecker(analyzer: SubPresenterAnalyzer): FirSimpleFunctionChecker =
        K230SubPresenterChecker(analyzer)

    private class K230SubPresenterChecker(
        private val analyzer: SubPresenterAnalyzer,
    ) : FirSimpleFunctionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirSimpleFunction) {
            with(analyzer) {
                analyze(
                    receiverType = declaration.receiverParameter?.typeRef?.coneType,
                    body = declaration.body,
                    source = declaration.source,
                )
            }
        }
    }

    class Factory : CompatContext.Factory {
        override val supportedRange: VersionRange = VersionRange(
            min = Version(2, 3, 0),
            max = Version(2, 3, 20),
        )

        override fun create(): CompatContext = CompatContextImpl()
    }
}
