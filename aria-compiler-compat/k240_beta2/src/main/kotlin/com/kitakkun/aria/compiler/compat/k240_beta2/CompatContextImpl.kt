package com.kitakkun.aria.compiler.compat.k240_beta2

import com.kitakkun.aria.compiler.compat.CompatContext
import com.kitakkun.aria.compiler.compat.Version
import com.kitakkun.aria.compiler.compat.VersionRange
import com.kitakkun.aria.compiler.compat.k2320.CompatContextImpl as K2320CompatContextImpl
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.Name

/**
 * Kotlin 2.4.x [CompatContext]. Targets 2.4.0 through (excluding) 2.5.0.
 *
 * Only one API actually shifted between 2.3.20 and 2.4.0-Beta2:
 * `FirAnnotation.getKClassArgument` dropped its [FirSession] parameter. Every
 * other method delegates to the 2.3.20 impl, whose bytecode is byte-for-byte
 * compatible at runtime because the IR writer APIs it touches didn't move.
 *
 * The delegation target `K2320CompatContextImpl` is loaded whenever this class
 * is — at runtime under 2.4 that's fine because the types `IrFunctionAccess-
 * Expression`, `FirAnnotation`, etc. live at the same FQNs. The only method
 * on the delegate that *would* link-fail under 2.4 is `kclassArg`, and we
 * override it here so the delegate's version is never invoked.
 */
class CompatContextImpl : CompatContext by K2320CompatContextImpl() {

    override fun FirAnnotation.kclassArg(name: Name, session: FirSession): ConeKotlinType? {
        // 2.4 dropped the `session` parameter; we accept it for interface parity
        // and discard it here.
        return getKClassArgument(name)
    }

    // Extension registration: the underlying `ProjectExtensionDescriptor` type
    // used in ExtensionStorage.registerExtension's signature was renamed to
    // `ExtensionPointDescriptor` between 2.3 and 2.4. Our 2.3-compiled delegate
    // would NoSuchMethodError at runtime, so we re-implement in 2.4 bytecode
    // that targets the new method signature. The Kotlin source below is
    // identical to k2320's; what differs is the bytecode compiled against this
    // module's 2.4 kotlin-compiler-embeddable pin.
    override fun CompilerPluginRegistrar.ExtensionStorage.registerFirExtension(registrar: FirExtensionRegistrar) {
        FirExtensionRegistrarAdapter.registerExtension(registrar)
    }

    override fun CompilerPluginRegistrar.ExtensionStorage.registerIrGenerationExtension(extension: IrGenerationExtension) {
        IrGenerationExtension.registerExtension(extension)
    }

    class Factory : CompatContext.Factory {
        override val supportedRange: VersionRange = VersionRange(
            min = Version(2, 4, 0),
            max = Version(2, 5, 0),
        )

        override fun create(): CompatContext = CompatContextImpl()
    }
}
