package com.kitakkun.fusio

import kotlin.reflect.KClass

/**
 * Lifts a child presenter's **effect** subtype into the parent's effect
 * stream.
 *
 * Place on a sealed subtype of the parent's effect type, with [source]
 * pointing at the child effect subtypes this parent type wraps. When a
 * fused child emits an effect of any [source], it's translated into the
 * annotated parent subtype and surfaces on `Presentation.effectFlow`.
 *
 * ```kotlin
 * sealed interface TaskListEffect {
 *     data class Added(val title: String) : TaskListEffect
 *     data class Updated(val title: String) : TaskListEffect
 * }
 *
 * sealed interface MyScreenEffect {
 *     // Single source — common case.
 *     @MapFrom(TaskListEffect.Added::class)
 *     data class ShowTaskAdded(val title: String) : MyScreenEffect
 *
 *     // Multiple sources — fan-in. Both child effects produce the same
 *     // parent effect (e.g. a unified "task changed" snackbar).
 *     @MapFrom(TaskListEffect.Added::class, TaskListEffect.Updated::class)
 *     data class ShowTaskChanged(val title: String) : MyScreenEffect
 * }
 * ```
 *
 * The annotated class and **every** entry in [source] must share
 * constructor parameter names and types — the compiler-generated mapper
 * copies them across by name. The Fusio compiler plugin's FIR checker
 * reports a `PROPERTY_MISMATCH` diagnostic if any shape doesn't line up,
 * and `MISSING_EFFECT_MAPPINGS` if a child effect subtype isn't covered
 * by any parent `@MapFrom`.
 *
 * Sibling annotation: [MapTo] for the event direction. Note that
 * `@MapTo` deliberately accepts a single target — fan-out (one parent
 * event into multiple child events) doesn't have a clean runtime
 * semantics.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MapFrom(public vararg val source: KClass<*>)
