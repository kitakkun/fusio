package com.kitakkun.fusio

import kotlinx.coroutines.flow.Flow

public data class Fusio<State, Effect>(
    public val state: State,
    public val effectFlow: Flow<Effect>,
)
