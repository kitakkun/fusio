package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.on

private const val MAX = 5

@Composable
fun PresenterScope<CounterEvent, CounterEffect>.counter(): CounterState {
    var value by remember { mutableStateOf(0) }

    on<CounterEvent.Increment> {
        if (value >= MAX) {
            // Clamp and notify — the bound is a counter-internal concern,
            // not something the parent or the sibling Wallet should know
            // about directly.
            emitEffect(CounterEffect.MaxReached)
        } else {
            value += 1
        }
    }

    on<CounterEvent.Reset> {
        value = 0
    }

    return CounterState(value = value, isAtMax = value >= MAX)
}
