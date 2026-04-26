// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB
// WITH_FUSIO_HEADLESS

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fusio.test.runHeadless
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.MapTo
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.fuse
import com.kitakkun.fusio.on

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
fun screen(): Presentation<Int, ParentEvent, ParentEffect> =
    buildPresenter { fuse { counter() } }

fun box(): String {
    var result = "uninitialised"
    runHeadless<ParentEvent, Int, ParentEffect>(::screen) {
        emit(ParentEvent.Poke)
        emit(ParentEvent.Poke)
        result = if (state == 2) "OK" else "FAIL count=$state"
    }
    return result
}
