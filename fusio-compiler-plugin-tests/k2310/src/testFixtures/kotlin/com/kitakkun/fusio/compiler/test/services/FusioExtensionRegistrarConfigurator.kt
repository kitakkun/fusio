package com.kitakkun.fusio.compiler.test.services

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.kitakkun.fusio.compiler.FusioFirExtensionRegistrar
import com.kitakkun.fusio.compiler.FusioIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configureFusioPlugin() {
    useConfigurators(::FusioExtensionRegistrarConfigurator)
    configureFusioAnnotationsAndRuntime()
}

/**
 * Registers Fusio BEFORE Compose so `fuse { ... }` is rewritten
 * before Compose injects `$composer`/`$changed` parameters into @Composable
 * lambdas — same ordering constraint the Gradle plugin enforces in production
 * via `-Xcompiler-plugin-order=com.kitakkun.fusio>androidx.compose.compiler...`.
 *
 * Under IrGenerationExtension, the registration order IS the execution order,
 * so registering Fusio first then Compose second is sufficient here.
 */
@OptIn(ExperimentalCompilerApi::class)
private class FusioExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        FirExtensionRegistrarAdapter.registerExtension(FusioFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(FusioIrGenerationExtension())

        with(ComposePluginRegistrar()) {
            registerExtensions(configuration)
        }
    }
}
