package com.kitakkun.fusio

import kotlinx.coroutines.flow.Flow

/**
 * The value a Fusio presenter produces: a snapshot of screen [state] paired
 * with the [effectFlow] of side effects it emitted alongside that snapshot.
 *
 * The name reflects what Fusio is doing at the call site — fusing the
 * sub-presenters' private state trees into one screen-level state and their
 * effect flows into one outbound channel, then returning both as a pair.
 */
public data class Presentation<State, Effect>(
    public val state: State,
    public val effectFlow: Flow<Effect>,
)
