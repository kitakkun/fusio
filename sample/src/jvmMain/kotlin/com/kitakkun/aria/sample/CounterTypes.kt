package com.kitakkun.aria.sample

// Grandchild presenter types — exercises nested mappedScope
sealed interface CounterEvent {
    data object Increment : CounterEvent
}

sealed interface CounterEffect {
    data class CounterChanged(val newValue: Int) : CounterEffect
}

data class CounterState(
    val value: Int = 0,
)
