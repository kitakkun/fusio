package com.kitakkun.fusio.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Android entry point. Hosts the same [App] Composable that the Desktop
 * target boots in `Main.kt` — the demo's presenter graph is platform-agnostic
 * and lives in `commonMain`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
