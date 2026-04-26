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
        CliOption(
            "event-handler-exhaustive-severity",
            "<error|warning|none>",
            "Severity of the event-handler exhaustiveness check (default: warning)",
            required = false,
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            "enabled" -> configuration.put(FusioConfigurationKeys.ENABLED, value.toBoolean())
            "event-handler-exhaustive-severity" -> {
                val severity = runCatching {
                    EventHandlerExhaustiveSeverity.valueOf(value.uppercase())
                }.getOrElse {
                    error(
                        "Fusio: invalid event-handler-exhaustive-severity '$value'; " +
                            "expected one of ${EventHandlerExhaustiveSeverity.values().joinToString { it.name.lowercase() }}.",
                    )
                }
                configuration.put(FusioConfigurationKeys.EVENT_HANDLER_EXHAUSTIVE_SEVERITY, severity)
            }
        }
    }
}
