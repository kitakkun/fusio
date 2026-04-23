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
import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.fuse
import com.kitakkun.fusio.on

// Three-level hierarchy: Screen -> Mid -> Leaf. Events propagate inward via two
// @MapTo hops; effects propagate outward via two @MapFrom hops.

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
    val leafCount = fuse { leaf() }
    return MidState(leafCount)
}

@Composable
fun screen(events: kotlinx.coroutines.flow.Flow<ScreenEvent>): Presentation<ScreenState, ScreenEffect> =
    buildPresenter(events) {
        val midState = fuse { mid() }
        ScreenState(midState)
    }

fun box(): String {
    var result = "uninitialised"
    runHeadless<ScreenEvent, ScreenState, ScreenEffect>(::screen) {
        emit(ScreenEvent.Bump)
        emit(ScreenEvent.Bump)
        emit(ScreenEvent.Bump)
        result = when {
            state?.mid?.leaf != 3 -> "FAIL state: $state"
            effects != listOf(
                ScreenEffect.Announce(1),
                ScreenEffect.Announce(2),
                ScreenEffect.Announce(3),
            ) -> "FAIL effects: $effects"
            else -> "OK"
        }
    }
    return result
}
