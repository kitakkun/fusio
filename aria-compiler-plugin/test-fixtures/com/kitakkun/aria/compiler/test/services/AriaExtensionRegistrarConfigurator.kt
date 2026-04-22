package com.kitakkun.aria.compiler.test.services

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.kitakkun.aria.compiler.AriaFirExtensionRegistrar
import com.kitakkun.aria.compiler.AriaIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

fun TestConfigurationBuilder.configureAriaPlugin() {
    useConfigurators(::AriaExtensionRegistrarConfigurator)
    configureAriaAnnotationsAndRuntime()
}

/**
 * Registers Aria BEFORE Compose so `mappedScope { ... }` is rewritten
 * before Compose injects `$composer`/`$changed` parameters into @Composable
 * lambdas — same ordering constraint the Gradle plugin enforces in production
 * via `-Xcompiler-plugin-order=com.kitakkun.aria>androidx.compose.compiler...`.
 *
 * Under IrGenerationExtension, the registration order IS the execution order,
 * so registering Aria first then Compose second is sufficient here.
 */
@OptIn(ExperimentalCompilerApi::class)
private class AriaExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        FirExtensionRegistrarAdapter.registerExtension(AriaFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(AriaIrGenerationExtension())

        with(ComposePluginRegistrar()) {
            registerExtensions(configuration)
        }
    }
}
