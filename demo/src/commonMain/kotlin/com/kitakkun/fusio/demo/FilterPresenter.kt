package com.kitakkun.fusio.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.on

@Composable
fun PresenterScope<FilterEvent, FilterEffect>.filter(): FilterState {
    var current by remember { mutableStateOf(TaskFilter.All) }

    on<FilterEvent.Select> { event ->
        if (current != event.filter) {
            current = event.filter
            emitEffect(FilterEffect.Changed(event.filter))
        }
    }

    return FilterState(current)
}
