package com.kitakkun.fusio.gradle

import org.gradle.api.provider.Property

/**
 * `fusio { … }` DSL block. Tune Fusio's compiler-plugin behaviour from
 * a consumer's `build.gradle.kts`:
 *
 * ```kotlin
 * plugins {
 *     id("com.kitakkun.fusio")
 * }
 *
 * fusio {
 *     eventHandlerExhaustiveSeverity = EventHandlerExhaustiveSeverity.ERROR
 * }
 * ```
 *
 * Each property also resolves from a matching `-Pfusio.…` Gradle
 * property, so CI lanes can override values without editing the build
 * script. The DSL value wins when both are set.
 */
public abstract class FusioPluginExtension {
    /**
     * How loudly the event-handler exhaustiveness check should report
     * uncovered `Event` subtypes — see [EventHandlerExhaustiveSeverity]
     * for the available levels.
     *
     * Default: [EventHandlerExhaustiveSeverity.WARNING]. Falls back to
     * `-Pfusio.event-handler-exhaustive-severity=…` when unset.
     */
    public abstract val eventHandlerExhaustiveSeverity: Property<EventHandlerExhaustiveSeverity>
}
