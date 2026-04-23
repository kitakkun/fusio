// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB

import com.kitakkun.fusio.Fusio
import kotlinx.coroutines.flow.emptyFlow

// Pin the generated data-class shape of Fusio<S, E>: equals / copy / componentN.
fun box(): String {
    val a = Fusio(state = 42, effectFlow = emptyFlow<String>())
    val b = a.copy(state = 42)
    if (a != b) return "FAIL: copy not equal: $a vs $b"

    val (state, _) = a
    if (state != 42) return "FAIL: destructured state=$state"

    val c = a.copy(state = 7)
    if (c.state != 7) return "FAIL: copied state=${c.state}"
    if (a == c) return "FAIL: distinct copies should not be equal"

    return "OK"
}
