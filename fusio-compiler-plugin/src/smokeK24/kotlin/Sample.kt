/*
 * Smoke source compiled by the :smokeK24 Gradle task with Kotlin 2.4.0-Beta2.
 * Exercises every CompatContext helper the plugin routes through (kclassArg
 * via @MapTo / @MapFrom FIR checks; setArg + setTypeArg via the
 * mappedScope IR transformer). Compile success = k240_beta2 impl works
 * end-to-end under a real 2.4 compiler. No runtime assertions are run — if
 * we needed those we'd extend this into an execute-and-check harness.
 */
package smoke

import androidx.compose.runtime.Composable
import com.kitakkun.fusio.Fusio
import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.mappedScope
import kotlinx.coroutines.flow.Flow

// --- Child sub-presenter types ---
sealed interface ChildEvent {
    data object Ping : ChildEvent
}
sealed interface ChildEffect {
    data class Emit(val payload: String) : ChildEffect
}
data class ChildState(val count: Int)

// --- Parent screen types ---
sealed interface ParentEvent {
    @MapTo(ChildEvent.Ping::class)
    data object TriggerPing : ParentEvent
}
sealed interface ParentEffect {
    @MapFrom(ChildEffect.Emit::class)
    data class ForwardEmit(val payload: String) : ParentEffect
}
data class ParentState(val child: ChildState)

@Composable
fun PresenterScope<ChildEvent, ChildEffect>.child(): ChildState = ChildState(count = 0)

@Composable
fun parent(eventFlow: Flow<ParentEvent>): Fusio<ParentState, ParentEffect> =
    buildPresenter(eventFlow) {
        val childState = mappedScope { child() }
        ParentState(child = childState)
    }
