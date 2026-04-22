package com.kitakkun.aria.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.aria.PresenterScope
import com.kitakkun.aria.on

@Composable
fun PresenterScope<CounterEvent, CounterEffect>.counter(): CounterState {
    var value by remember { mutableIntStateOf(0) }

    on<CounterEvent.Increment> {
        value += 1
        emitEffect(CounterEffect.CounterChanged(value))
    }

    return CounterState(value = value)
}
