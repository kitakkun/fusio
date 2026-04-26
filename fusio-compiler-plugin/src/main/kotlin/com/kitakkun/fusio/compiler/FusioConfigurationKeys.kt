package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object FusioConfigurationKeys {
    val ENABLED: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("fusio.enabled")

    /**
     * Severity of the event-handler exhaustiveness check (does every Event
     * subtype have a matching `on<E>` in the presenter body, or route to a
     * `fuse { … }` child via `@MapTo`?). Default WARNING.
     */
    val EVENT_HANDLER_EXHAUSTIVE_SEVERITY: CompilerConfigurationKey<EventHandlerExhaustiveSeverity> =
        CompilerConfigurationKey.create("fusio.event-handler-exhaustive-severity")
}

/**
 * Internal mirror of the gradle plugin's `EventHandlerExhaustiveSeverity`
 * enum. The wire format between the two layers is the lowercase string of
 * each value (`error` / `warning` / `none`), so these enums don't need to
 * share a class — they only need to agree on the names.
 */
enum class EventHandlerExhaustiveSeverity { ERROR, WARNING, NONE }
