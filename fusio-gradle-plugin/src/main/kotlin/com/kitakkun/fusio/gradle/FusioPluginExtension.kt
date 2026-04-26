package com.kitakkun.fusio.gradle

import org.gradle.api.provider.Property

/**
 * `fusio { … }` DSL block configuration. Apply the plugin and tweak from
 * a consumer's `build.gradle.kts`:
 *
 * ```kotlin
 * fusio {
 *     eventHandlerExhaustiveSeverity = EventHandlerExhaustiveSeverity.ERROR
 * }
 * ```
 *
 * Every property is also resolvable from a Gradle property of the matching
 * `fusio.…` name, so CI overrides can inject values without editing the
 * build script.
 */
public abstract class FusioPluginExtension {
    /**
     * Severity of the event-handler-exhaustiveness check.
     *
     * Default: [EventHandlerExhaustiveSeverity.WARNING]. Falls back to the
     * `fusio.event-handler-exhaustive-severity` Gradle property when not
     * set in the DSL.
     */
    public abstract val eventHandlerExhaustiveSeverity: Property<EventHandlerExhaustiveSeverity>
}
