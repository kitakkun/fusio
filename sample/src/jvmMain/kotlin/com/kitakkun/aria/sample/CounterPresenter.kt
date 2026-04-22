package com.kitakkun.aria.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.aria.Aria
import com.kitakkun.aria.PresenterScope
import com.kitakkun.aria.on
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun PresenterScope<CounterEvent, CounterEffect>.counter(): Aria<CounterState, CounterEffect> {
    var value by remember { mutableIntStateOf(0) }

    on<CounterEvent.Increment> {
        value += 1
        emitEffect(CounterEffect.CounterChanged(value))
    }

    return Aria(
        state = CounterState(value = value),
        effectFlow = emptyFlow(),
    )
}
