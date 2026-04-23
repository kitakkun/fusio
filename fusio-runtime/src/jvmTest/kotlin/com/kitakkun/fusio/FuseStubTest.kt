package com.kitakkun.fusio

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the no-plugin fallback of `fuse`: if the Fusio compiler plugin
 * isn't registered, every fuse call site must throw with a helpful
 * message pointing the user at the plugin. A silent no-op would leave
 * events/effects unrouted and be painful to diagnose.
 *
 * Easiest stable check: confirm the error message string literal is embedded
 * in the compiled classfile's constant pool. If someone ever simplifies the
 * stub body to `return null as ChildState` during a refactor, this fails —
 * exactly the regression we want to catch.
 *
 * We used to also reflectively invoke the stub, but after making fuse
 * `inline` (so the function itself can drop `@Composable`) the JVM method
 * signature became tied to the Compose compiler plugin's lambda shape and
 * varied enough that the reflection test was fragile. The constant-pool
 * check is stable across inlining / @Composable decisions.
 */
class FuseStubTest {

    @Test
    fun stub_carries_the_plugin_missing_error_message_in_its_classfile() {
        val bytes = javaClass.classLoader
            .getResourceAsStream("com/kitakkun/fusio/FuseKt.class")!!
            .use { it.readBytes() }
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue(
            "fuse { ... } requires the Fusio Compiler Plugin" in text,
            "Error message missing from FuseKt bytecode. Did the stub body change?",
        )
        assertTrue(
            "com.kitakkun.fusio" in text,
            "Plugin ID hint missing from stub error message.",
        )
    }
}
