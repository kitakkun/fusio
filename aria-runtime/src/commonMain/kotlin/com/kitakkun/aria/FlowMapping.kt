package com.kitakkun.aria

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Runtime helper used by Aria's IR transformer to map parent events to child events.
 *
 * The compiler plugin generates the [mapper] lambda from `@MapTo` annotations on the
 * parent Event sealed subtypes. Each mapping becomes a branch in a `when` expression.
 *
 * This stays in runtime (not generated as raw IR) so we don't have to synthesize
 * Flow.mapNotNull + lambda from scratch — the compiler plugin only has to build the
 * mapping lambda body, which is much simpler.
 */
@PublishedApi
internal fun <ParentEvent, ChildEvent> Flow<ParentEvent>.mapEvents(
    mapper: (ParentEvent) -> ChildEvent?,
): Flow<ChildEvent> = mapNotNull { mapper(it) }
