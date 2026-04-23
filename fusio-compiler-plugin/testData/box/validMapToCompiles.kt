// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB

import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.MapTo
import kotlinx.coroutines.flow.emptyFlow

// Smoke test: valid @MapTo usage passes the FIR checkers, bytecode is emitted,
// and the runtime Fusio data class behaves as expected.
sealed interface Child {
    data class Toggle(val id: String) : Child
}

sealed interface Parent {
    @MapTo(Child.Toggle::class)
    data class ToggleFavorite(val id: String) : Parent
}

fun box(): String {
    val fusio: Presentation<String, Child> = Presentation(state = "ready", effectFlow = emptyFlow())
    if (fusio.state != "ready") return "FAIL: state=${fusio.state}"

    val parent = Parent.ToggleFavorite(id = "item-1")
    if (parent.id != "item-1") return "FAIL: parent.id=${parent.id}"

    return "OK"
}
