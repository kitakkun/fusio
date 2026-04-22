package com.kitakkun.aria

import kotlinx.coroutines.flow.Flow

data class Aria<State, Effect>(
    val state: State,
    val effectFlow: Flow<Effect>,
)
