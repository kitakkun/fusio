package com.kitakkun.fusio

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
