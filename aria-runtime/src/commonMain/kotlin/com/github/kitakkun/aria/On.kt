package com.github.kitakkun.aria

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.filterIsInstance

@Composable
inline fun <reified E> PresenterScope<*, *>.on(
    noinline handler: suspend (E) -> Unit,
) {
    val flow = eventFlow
    LaunchedEffect(Unit) {
        flow.filterIsInstance<E>().collect { handler(it) }
    }
}
