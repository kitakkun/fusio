package com.kitakkun.fusio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Maps a parent event flow into a child event flow, dropping unmapped
 * subtypes.
 *
 * **Not part of the user-facing API.** Called from compiler-plugin-emitted
 * code at every `fuse { … }` rewrite, where [mapper] is generated from
 * the parent event type's `@MapTo` annotations. `@PublishedApi internal`
 * because the generated call site is in the consumer module but this
 * function shouldn't appear in IDE completion.
 */
@PublishedApi
internal fun <ParentEvent, ChildEvent> Flow<ParentEvent>.mapEvents(
    mapper: (ParentEvent) -> ChildEvent?,
): Flow<ChildEvent> = mapNotNull { mapper(it) }
