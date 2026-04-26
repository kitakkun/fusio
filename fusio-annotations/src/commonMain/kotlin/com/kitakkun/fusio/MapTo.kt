package com.kitakkun.fusio

import kotlin.reflect.KClass

/**
 * Routes a parent **event** subtype into a fused child presenter.
 *
 * Place on a sealed subtype of the parent's event type, with [target]
 * pointing at the matching child event subtype. When the parent receives
 * an event of this subtype, it's translated into the child subtype and
 * fed into the child presenter's `on<>` handlers via `fuse { … }`'s
 * compiler-generated wiring.
 *
 * ```kotlin
 * sealed interface MyScreenEvent {
 *     @MapTo(TaskListEvent.Add::class)
 *     data class AddTask(val title: String) : MyScreenEvent
 * }
 *
 * sealed interface TaskListEvent {
 *     data class Add(val title: String) : TaskListEvent
 * }
 * ```
 *
 * The annotated class and [target] must share constructor parameter
 * names and types — the compiler-generated mapper copies them across by
 * name. The Fusio compiler plugin's FIR checker reports a
 * `PROPERTY_MISMATCH` diagnostic if the shapes don't line up.
 *
 * Sibling annotation: [MapFrom] for the effect direction.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MapTo(public val target: KClass<*>)
