package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object FusioConfigurationKeys {
    val ENABLED: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("fusio.enabled")
}
