// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.kitakkun.aria.Aria
import com.kitakkun.aria.MapFrom
import com.kitakkun.aria.MapTo
import com.kitakkun.aria.PresenterScope
import com.kitakkun.aria.buildPresenter
import com.kitakkun.aria.mappedScope
import com.kitakkun.aria.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// Sub-presenter types
sealed interface ChildEvent {
    data class Toggle(val id: String) : ChildEvent
}
sealed interface ChildEffect {
    data class Reply(val message: String) : ChildEffect
}
data class ChildState(val toggled: Boolean)

// Screen-level types with @MapTo / @MapFrom wiring
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
fun PresenterScope<ChildEvent, ChildEffect>.child(): Aria<ChildState, ChildEffect> {
    var toggled by remember { mutableStateOf(false) }
    on<ChildEvent.Toggle> { event ->
        toggled = !toggled
        emitEffect(ChildEffect.Reply("toggled=${event.id}"))
    }
    return Aria(ChildState(toggled), emptyFlow())
}

@Composable
fun screenPresenter(
    events: kotlinx.coroutines.flow.Flow<ParentEvent>,
): Aria<ScreenState, ParentEffect> = buildPresenter(events) {
    // This is the call the IR transformer rewrites. If @MapTo event mapping
    // or @MapFrom effect forwarding misfire, the assertions below will FAIL.
    val childState = mappedScope { child() }
    ScreenState(childState)
}

private class HeadlessApplier : AbstractApplier<Unit>(Unit) {
    override fun onClear() = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
}

fun box(): String = runBlocking {
    val events = MutableSharedFlow<ParentEvent>(extraBufferCapacity = 16)
    val clock = BroadcastFrameClock()
    val ctx = coroutineContext + clock + Job(coroutineContext[Job])
    val recomposer = Recomposer(ctx)
    val runner = CoroutineScope(ctx)
    runner.launch { recomposer.runRecomposeAndApplyChanges() }

    val state = mutableStateOf<ScreenState?>(null)
    val effects = mutableListOf<ParentEffect>()

    val composition = Composition(HeadlessApplier(), recomposer)
    composition.setContent {
        val aria = screenPresenter(events)
        state.value = aria.state
        LaunchedEffect(aria.effectFlow) {
            aria.effectFlow.collect { effects.add(it) }
        }
    }

    suspend fun pump() {
        repeat(3) {
            delay(5)
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
        }
    }
    pump()

    events.emit(ParentEvent.Flip(id = "a"))
    pump()

    val result = when {
        state.value?.child?.toggled != true -> "FAIL state: ${state.value}"
        effects.singleOrNull() != ParentEffect.Echo(message = "toggled=a") -> "FAIL effects: $effects"
        else -> "OK"
    }

    composition.dispose()
    recomposer.close()
    runner.coroutineContext[Job]?.cancel()
    result
}
