// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_STDLIB

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.kitakkun.aria.Aria
import com.kitakkun.aria.MapTo
import com.kitakkun.aria.PresenterScope
import com.kitakkun.aria.buildPresenter
import com.kitakkun.aria.mappedScope
import com.kitakkun.aria.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    val lastCount = androidx.compose.runtime.mutableStateOf(-1)
    val composition = Composition(HeadlessApplier(), recomposer)
    composition.setContent {
        val aria = screen(events)
        lastCount.value = aria.state
    }

    suspend fun pump() {
        repeat(3) {
            delay(5)
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
        }
    }
    pump()

    // Two Pokes — each should be mapped to the Ping singleton and bumping count.
    events.emit(ParentEvent.Poke)
    pump()
    events.emit(ParentEvent.Poke)
    pump()

    val result = if (lastCount.value == 2) "OK" else "FAIL count=${lastCount.value}"

    composition.dispose()
    recomposer.close()
    runner.coroutineContext[Job]?.cancel()
    result
}
