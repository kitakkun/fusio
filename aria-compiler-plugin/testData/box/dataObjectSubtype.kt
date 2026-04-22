// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB
// WITH_ARIA_HEADLESS

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import aria.test.runHeadless
import com.kitakkun.aria.Aria
import com.kitakkun.aria.MapTo
import com.kitakkun.aria.PresenterScope
import com.kitakkun.aria.buildPresenter
import com.kitakkun.aria.mappedScope
import com.kitakkun.aria.on

// data object subtypes must be mapped with IrGetObjectValue, not a constructor
// call — otherwise the IR transformer crashes at bytecode gen with
// NoSuchMethodError: <init>(DefaultConstructorMarker).
sealed interface ChildEvent {
    data object Ping : ChildEvent
}
sealed interface ChildEffect

sealed interface ParentEvent {
    @MapTo(ChildEvent.Ping::class)
    data object Poke : ParentEvent
}
sealed interface ParentEffect

@Composable
fun PresenterScope<ChildEvent, ChildEffect>.counter(): Int {
    var count by remember { mutableIntStateOf(0) }
    on<ChildEvent.Ping> { count += 1 }
    return count
}

@Composable
fun screen(events: kotlinx.coroutines.flow.Flow<ParentEvent>): Aria<Int, ParentEffect> =
    buildPresenter(events) { mappedScope { counter() } }

fun box(): String {
    var result = "uninitialised"
    runHeadless<ParentEvent, Int, ParentEffect>(::screen) {
        emit(ParentEvent.Poke)
        emit(ParentEvent.Poke)
        result = if (state == 2) "OK" else "FAIL count=$state"
    }
    return result
}
