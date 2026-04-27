// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB
// WITH_FUSIO_HEADLESS

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fusio.test.runHeadless
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.fuse
import com.kitakkun.fusio.on

// Fan-in: a single parent effect type aggregates multiple child effect
// subtypes via @MapFrom(vararg). Both ChildEffect.Created and
// ChildEffect.Updated lift into ParentEffect.Notify so the UI gets one
// uniform "something changed" signal.

sealed interface ChildEvent {
    data class DoCreate(val message: String) : ChildEvent
    data class DoUpdate(val message: String) : ChildEvent
}
sealed interface ChildEffect {
    data class Created(val message: String) : ChildEffect
    data class Updated(val message: String) : ChildEffect
}
data class ChildState(val createCount: Int, val updateCount: Int)

sealed interface ParentEvent {
    @MapTo(ChildEvent.DoCreate::class)
    data class Create(val message: String) : ParentEvent

    @MapTo(ChildEvent.DoUpdate::class)
    data class Update(val message: String) : ParentEvent
}
sealed interface ParentEffect {
    @MapFrom(ChildEffect.Created::class, ChildEffect.Updated::class)
    data class Notify(val message: String) : ParentEffect
}
data class ScreenState(val child: ChildState)

@Composable
fun PresenterScope<ChildEvent, ChildEffect>.child(): ChildState {
    var createCount by remember { mutableStateOf(0) }
    var updateCount by remember { mutableStateOf(0) }
    on<ChildEvent.DoCreate> { event ->
        createCount += 1
        emitEffect(ChildEffect.Created(event.message))
    }
    on<ChildEvent.DoUpdate> { event ->
        updateCount += 1
        emitEffect(ChildEffect.Updated(event.message))
    }
    return ChildState(createCount, updateCount)
}

@Composable
fun screenPresenter(): Presentation<ScreenState, ParentEvent, ParentEffect> = buildPresenter {
    val childState = fuse { child() }
    ScreenState(childState)
}

fun box(): String {
    var result = "uninitialised"
    runHeadless<ParentEvent, ScreenState, ParentEffect>(::screenPresenter) {
        emit(ParentEvent.Create("hello"))
        emit(ParentEvent.Update("world"))
        result = when {
            state?.child?.createCount != 1 -> "FAIL createCount: ${state?.child?.createCount}"
            state?.child?.updateCount != 1 -> "FAIL updateCount: ${state?.child?.updateCount}"
            effects.size != 2 -> "FAIL effects size: ${effects.size}"
            effects[0] != ParentEffect.Notify("hello") -> "FAIL effects[0]: ${effects[0]}"
            effects[1] != ParentEffect.Notify("world") -> "FAIL effects[1]: ${effects[1]}"
            else -> "OK"
        }
    }
    return result
}
