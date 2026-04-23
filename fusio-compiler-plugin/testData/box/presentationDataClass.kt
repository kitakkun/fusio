// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB

import com.kitakkun.fusio.Presentation
import kotlinx.coroutines.flow.emptyFlow

// Pin the structural-equality contract of Presentation<S, E>. It used to be
// a data class; now it's a regular class with hand-written equals / hashCode /
// toString. Destructuring / `copy` are intentionally gone — this test
// guards only the behaviour a library consumer should be able to rely on.
fun box(): String {
    val flow = emptyFlow<String>()
    val a = Presentation(state = 42, effectFlow = flow)
    val b = Presentation(state = 42, effectFlow = flow)
    val c = Presentation(state = 7, effectFlow = flow)

    if (a != b) return "FAIL: equal states should be equal: $a vs $b"
    if (a.hashCode() != b.hashCode()) return "FAIL: equal instances should share hashCode"
    if (a == c) return "FAIL: differing states should not be equal"

    val s = a.toString()
    if (!s.contains("state=42")) return "FAIL: toString missing state: $s"

    return "OK"
}
