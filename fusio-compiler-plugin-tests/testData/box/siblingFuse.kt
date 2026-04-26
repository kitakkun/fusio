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

// Two independent child trees (A / B) under the same parent. Before the
// fuse sibling-isolation fix, the parent event flow of the A-child
// would receive B-child's events cast to AEvent and crash at runtime.

sealed interface AEvent {
    data class Ping(val n: Int) : AEvent
}
sealed interface AEffect {
    data class Pong(val n: Int) : AEffect
}
data class AState(val last: Int)

sealed interface BEvent {
    data class Toggle(val id: String) : BEvent
}
sealed interface BEffect {
    data class Flipped(val id: String) : BEffect
}
data class BState(val isOn: Boolean)

sealed interface ParentEvent {
    @MapTo(AEvent.Ping::class) data class PingA(val n: Int) : ParentEvent
    @MapTo(BEvent.Toggle::class) data class ToggleB(val id: String) : ParentEvent
}
sealed interface ParentEffect {
    @MapFrom(AEffect.Pong::class) data class FromA(val n: Int) : ParentEffect
    @MapFrom(BEffect.Flipped::class) data class FromB(val id: String) : ParentEffect
}
data class ScreenState(val a: AState, val b: BState)

@Composable
fun PresenterScope<AEvent, AEffect>.aChild(): AState {
    var last by remember { mutableStateOf(0) }
    on<AEvent.Ping> { e ->
        last = e.n
        emitEffect(AEffect.Pong(e.n))
    }
    return AState(last)
}

@Composable
fun PresenterScope<BEvent, BEffect>.bChild(): BState {
    var on by remember { mutableStateOf(false) }
    on<BEvent.Toggle> { e ->
        on = !on
        emitEffect(BEffect.Flipped(e.id))
    }
    return BState(on)
}

@Composable
fun screenPresenter(): Presentation<ScreenState, ParentEvent, ParentEffect> = buildPresenter {
    val a = fuse { aChild() }
    val b = fuse { bChild() }
    ScreenState(a, b)
}

fun box(): String {
    var result = "uninitialised"
    runHeadless<ParentEvent, ScreenState, ParentEffect>(::screenPresenter) {
        emit(ParentEvent.PingA(7))
        emit(ParentEvent.ToggleB("x"))
        result = when {
            state?.a?.last != 7 -> "FAIL a.last=${state?.a?.last}"
            state?.b?.isOn != true -> "FAIL b.isOn=${state?.b?.isOn}"
            effects.size != 2 -> "FAIL effects.size=${effects.size} effects=$effects"
            effects[0] != ParentEffect.FromA(7) -> "FAIL effect[0]=${effects[0]}"
            effects[1] != ParentEffect.FromB("x") -> "FAIL effect[1]=${effects[1]}"
            else -> "OK"
        }
    }
    return result
}
