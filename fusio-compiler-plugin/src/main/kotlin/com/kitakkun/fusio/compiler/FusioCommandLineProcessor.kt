package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class FusioCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "com.kitakkun.fusio"

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption("enabled", "<true|false>", "Enable Fusio compiler plugin", required = false),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            "enabled" -> configuration.put(FusioConfigurationKeys.ENABLED, value.toBoolean())
        }
    }
}
