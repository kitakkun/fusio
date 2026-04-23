package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.on

@Composable
fun PresenterScope<CounterEvent, CounterEffect>.counter(): CounterState {
    var value by remember { mutableStateOf(0) }

    on<CounterEvent.Increment> {
        value += 1
        emitEffect(CounterEffect.Value(value))
    }

    return CounterState(value)
}
