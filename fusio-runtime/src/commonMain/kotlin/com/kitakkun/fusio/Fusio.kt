package com.kitakkun.fusio

import kotlinx.coroutines.flow.Flow

data class Fusio<State, Effect>(
    val state: State,
    val effectFlow: Flow<Effect>,
)
