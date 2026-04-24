package com.kitakkun.fusio.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.on
import com.kitakkun.fusio.presenter

val filter = presenter<FilterEvent, FilterEffect, FilterState> {
    var current by remember { mutableStateOf(TaskFilter.All) }

    on<FilterEvent.Select> { event ->
        if (current != event.filter) {
            current = event.filter
            emitEffect(FilterEffect.Changed(event.filter))
        }
    }

    FilterState(current)
}
