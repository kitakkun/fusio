// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB
// DUMP_IR

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.fuse
import com.kitakkun.fusio.on

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
fun screenPresenter(): Presentation<ScreenState, ParentEffect, ParentEvent> = buildPresenter {
    val childState = fuse { child() }
    ScreenState(childState)
}
