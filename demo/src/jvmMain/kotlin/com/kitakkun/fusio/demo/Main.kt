package com.kitakkun.fusio.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Desktop JVM entry point. Just hosts `App()` in a standard Compose Window.
 * Nothing Fusio-specific — this file exists solely because Compose Desktop
 * requires a platform-specific `application { Window { ... } }` boot.
 */
fun main(): Unit = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Fusio demo",
        state = rememberWindowState(size = DpSize(480.dp, 540.dp)),
    ) {
        App()
    }
}
