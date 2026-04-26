package com.kitakkun.fusio.gradle

/**
 * Diagnostic severity for Fusio's event-handler exhaustiveness check.
 *
 * The check fires on every
 * `buildPresenter<Event, Effect, UiState> { … }` call site and on every
 * `@Composable PresenterScope<Event, Effect>.foo()` sub-presenter
 * declaration; it reports each sealed `Event` subtype that no `on<E>`
 * handler covers and that no `@MapTo` annotation routes through a
 * `fuse { … }` child.
 *
 * Set via [FusioPluginExtension.eventHandlerExhaustiveSeverity] or
 * the `-Pfusio.event-handler-exhaustive-severity=…` Gradle property.
 *
 * ```kotlin
 * fusio {
 *     eventHandlerExhaustiveSeverity = EventHandlerExhaustiveSeverity.ERROR
 * }
 * ```
 */
public enum class EventHandlerExhaustiveSeverity {
    /** Missing handlers fail compilation. */
    ERROR,

    /** Missing handlers log a warning; the build still succeeds. (Default.) */
    WARNING,

    /** The check is disabled — nothing is reported. */
    NONE,
}
