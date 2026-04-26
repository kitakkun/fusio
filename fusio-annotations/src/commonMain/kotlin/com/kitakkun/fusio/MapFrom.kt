package com.kitakkun.fusio

import kotlin.reflect.KClass

/**
 * Lifts a child presenter's **effect** subtype into the parent's effect
 * stream.
 *
 * Place on a sealed subtype of the parent's effect type, with [source]
 * pointing at the child effect subtype this parent type wraps. When the
 * fused child emits an effect of [source], it's translated into the
 * annotated parent subtype and surfaces on `Presentation.effectFlow`.
 *
 * ```kotlin
 * sealed interface TaskListEffect {
 *     data class Added(val title: String) : TaskListEffect
 * }
 *
 * sealed interface MyScreenEffect {
 *     @MapFrom(TaskListEffect.Added::class)
 *     data class ShowTaskAdded(val title: String) : MyScreenEffect
 * }
 * ```
 *
 * The annotated class and [source] must share constructor parameter
 * names and types — the compiler-generated mapper copies them across by
 * name. The Fusio compiler plugin's FIR checker reports a
 * `PROPERTY_MISMATCH` diagnostic if the shapes don't line up, and
 * `MISSING_EFFECT_MAPPINGS` if a child effect subtype isn't covered by
 * any parent `@MapFrom`.
 *
 * Sibling annotation: [MapTo] for the event direction.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MapFrom(public val source: KClass<*>)
