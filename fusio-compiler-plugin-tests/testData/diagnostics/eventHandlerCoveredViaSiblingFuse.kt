// RUN_PIPELINE_TILL: FRONTEND
// FILE: eventHandlerCoveredViaSiblingFuse.kt

import androidx.compose.runtime.Composable
import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.MapTo
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.fuse
import com.kitakkun.fusio.on

// Mirrors the demo's MyScreen / TaskList / Filter shape: every parent
// event subtype is `@MapTo`-routed into one of two sibling sub-presenters
// fused side by side. Regression coverage for an IDE-side false-positive
// where the checker would miss fuse routing when fuse's type arguments
// are inferred (the demo never writes `fuse<...>` explicitly).

sealed interface AEvent {
    data class Add(val title: String) : AEvent
    data class Toggle(val id: Long) : AEvent
    data class Remove(val id: Long) : AEvent
}
sealed interface AEffect {
    data class Added(val title: String) : AEffect
}
data class AState(val n: Int)

sealed interface BEvent {
    data class Select(val tag: String) : BEvent
}
sealed interface BEffect {
    data class Changed(val tag: String) : BEffect
}
data class BState(val tag: String)

sealed interface ParentEvent {
    @MapTo(AEvent.Add::class)
    data class AddTask(val title: String) : ParentEvent

    @MapTo(AEvent.Toggle::class)
    data class ToggleTask(val id: Long) : ParentEvent

    @MapTo(AEvent.Remove::class)
    data class RemoveTask(val id: Long) : ParentEvent

    @MapTo(BEvent.Select::class)
    data class SelectFilter(val tag: String) : ParentEvent
}
sealed interface ParentEffect {
    @MapFrom(AEffect.Added::class)
    data class ShowAdded(val title: String) : ParentEffect

    @MapFrom(BEffect.Changed::class)
    data class ShowChanged(val tag: String) : ParentEffect
}

data class ScreenState(val a: AState, val b: BState)

@Composable
fun PresenterScope<AEvent, AEffect>.aChild(): AState {
    on<AEvent.Add> { }
    on<AEvent.Toggle> { }
    on<AEvent.Remove> { }
    return AState(0)
}

@Composable
fun PresenterScope<BEvent, BEffect>.bChild(): BState {
    on<BEvent.Select> { }
    return BState("")
}

// All four ParentEvent subtypes are fuse-routed (AddTask/ToggleTask/RemoveTask
// to A, SelectFilter to B). No warning should fire on `buildPresenter`.
@Composable
fun screenPresenter(): Presentation<ScreenState, ParentEvent, ParentEffect> = buildPresenter {
    val a = fuse { aChild() }
    val b = fuse { bChild() }
    ScreenState(a, b)
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, funWithExtensionReceiver, functionDeclaration,
interfaceDeclaration, lambdaLiteral, localProperty, nestedClass, primaryConstructor, propertyDeclaration, sealed */
