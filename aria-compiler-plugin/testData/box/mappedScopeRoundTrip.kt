// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB
// WITH_ARIA_HEADLESS

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import aria.test.runHeadless
import com.kitakkun.aria.Aria
import com.kitakkun.aria.MapFrom
import com.kitakkun.aria.MapTo
import com.kitakkun.aria.PresenterScope
import com.kitakkun.aria.buildPresenter
import com.kitakkun.aria.mappedScope
import com.kitakkun.aria.on

sealed interface ChildEvent {
    data class Toggle(val id: String) : ChildEvent
}
sealed interface ChildEffect {
    data class Reply(val message: String) : ChildEffect
}
data class ChildState(val toggled: Boolean)

sealed interface ParentEvent {
    @MapTo(ChildEvent.Toggle::class)
    data class Flip(val id: String) : ParentEvent
}
sealed interface ParentEffect {
    @MapFrom(ChildEffect.Reply::class)
    data class Echo(val message: String) : ParentEffect
}
data class ScreenState(val child: ChildState)

@Composable
fun PresenterScope<ChildEvent, ChildEffect>.child(): ChildState {
    var toggled by remember { mutableStateOf(false) }
    on<ChildEvent.Toggle> { event ->
        toggled = !toggled
        emitEffect(ChildEffect.Reply("toggled=${event.id}"))
    }
    return ChildState(toggled)
}

@Composable
fun screenPresenter(
    events: kotlinx.coroutines.flow.Flow<ParentEvent>,
): Aria<ScreenState, ParentEffect> = buildPresenter(events) {
    val childState = mappedScope { child() }
    ScreenState(childState)
}

fun box(): String {
    var result = "uninitialised"
    runHeadless<ParentEvent, ScreenState, ParentEffect>(::screenPresenter) {
        emit(ParentEvent.Flip("a"))
        result = when {
            state?.child?.toggled != true -> "FAIL state: $state"
            effects.singleOrNull() != ParentEffect.Echo(message = "toggled=a") -> "FAIL effects: $effects"
            else -> "OK"
        }
    }
    return result
}
