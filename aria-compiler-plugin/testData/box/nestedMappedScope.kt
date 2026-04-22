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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.kitakkun.aria.Aria // top-level buildPresenter return type
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// Three-level hierarchy: Screen -> Mid -> Leaf.
// Events propagate inward via two @MapTo hops; effects propagate outward via two
// @MapFrom hops. The state at Leaf must surface at Screen through Mid.

sealed interface LeafEvent {
    data object Bump : LeafEvent
}
sealed interface LeafEffect {
    data class Bumped(val value: Int) : LeafEffect
}

sealed interface MidEvent {
    @MapTo(LeafEvent.Bump::class)
    data object ForwardBump : MidEvent
}
sealed interface MidEffect {
    @MapFrom(LeafEffect.Bumped::class)
    data class Relayed(val value: Int) : MidEffect
}

sealed interface ScreenEvent {
    @MapTo(MidEvent.ForwardBump::class)
    data object Bump : ScreenEvent
}
sealed interface ScreenEffect {
    @MapFrom(MidEffect.Relayed::class)
    data class Announce(val value: Int) : ScreenEffect
}

data class MidState(val leaf: Int)
data class ScreenState(val mid: MidState)

@Composable
fun PresenterScope<LeafEvent, LeafEffect>.leaf(): Int {
    var count by remember { mutableIntStateOf(0) }
    on<LeafEvent.Bump> {
        count += 1
        emitEffect(LeafEffect.Bumped(count))
    }
    return count
}

@Composable
fun PresenterScope<MidEvent, MidEffect>.mid(): MidState {
    val leafCount = mappedScope { leaf() }
    return MidState(leafCount)
}

@Composable
fun screen(events: kotlinx.coroutines.flow.Flow<ScreenEvent>): Aria<ScreenState, ScreenEffect> =
    buildPresenter(events) {
        val midState = mappedScope { mid() }
        ScreenState(midState)
    }

private class HeadlessApplier : AbstractApplier<Unit>(Unit) {
    override fun onClear() = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
}

fun box(): String = runBlocking {
    val events = MutableSharedFlow<ScreenEvent>(extraBufferCapacity = 16)
    val clock = BroadcastFrameClock()
    val ctx = coroutineContext + clock + Job(coroutineContext[Job])
    val recomposer = Recomposer(ctx)
    val runner = CoroutineScope(ctx)
    runner.launch { recomposer.runRecomposeAndApplyChanges() }

    val state = mutableStateOf<ScreenState?>(null)
    val effects = mutableListOf<ScreenEffect>()

    val composition = Composition(HeadlessApplier(), recomposer)
    composition.setContent {
        val aria = screen(events)
        state.value = aria.state
        LaunchedEffect(aria.effectFlow) {
            aria.effectFlow.collect { effects.add(it) }
        }
    }

    suspend fun pump() {
        repeat(4) {
            delay(5)
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
        }
    }
    pump()

    events.emit(ScreenEvent.Bump)
    pump()
    events.emit(ScreenEvent.Bump)
    pump()
    events.emit(ScreenEvent.Bump)
    pump()

    val result = when {
        state.value?.mid?.leaf != 3 -> "FAIL state: ${state.value}"
        effects != listOf(
            ScreenEffect.Announce(1),
            ScreenEffect.Announce(2),
            ScreenEffect.Announce(3),
        ) -> "FAIL effects: $effects"
        else -> "OK"
    }

    composition.dispose()
    recomposer.close()
    runner.coroutineContext[Job]?.cancel()
    result
}
