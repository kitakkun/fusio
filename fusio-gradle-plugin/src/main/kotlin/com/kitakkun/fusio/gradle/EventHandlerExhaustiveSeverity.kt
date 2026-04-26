package com.kitakkun.fusio.gradle

/**
 * Severity of Fusio's event-handler-exhaustiveness check, configured via
 * [FusioPluginExtension.eventHandlerExhaustiveSeverity] or the
 * `fusio.event-handler-exhaustive-severity` Gradle property.
 *
 * The check reports parent-Event sealed subtypes that no `on<E>` handler
 * covers and no `@MapTo` annotation routes through a `fuse { … }` child.
 */
public enum class EventHandlerExhaustiveSeverity {
    /** Treat missing handlers as a hard compile error. */
    ERROR,

    /** Log a warning per missing handler; build still succeeds. (Default.) */
    WARNING,

    /** Disable the check entirely. */
    NONE,
}
