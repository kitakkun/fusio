// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB

import androidx.compose.runtime.Composable

// Starter check: the Compose compiler plugin is active in tests, so @Composable
// functions are accepted and codegen succeeds even when we never run them.
@Composable
fun greet(name: String): String = "hello, $name"

fun box(): String {
    // We don't invoke the @Composable directly (that requires a Compose context);
    // just asserting the file compiled is the box-test contract.
    return "OK"
}
