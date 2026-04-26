// RUN_PIPELINE_TILL: FRONTEND
// FILE: eventHandlerCoveredViaFuse.kt

import androidx.compose.runtime.Composable
import com.kitakkun.fusio.MapTo
import com.kitakkun.fusio.MapFrom
import com.kitakkun.fusio.Presentation
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

sealed interface ParentEvent {
    @MapTo(ChildEvent.Toggle::class)
    data class Flip(val id: String) : ParentEvent

    // Direct on<> handler — covered the conventional way.
    data object Local : ParentEvent
}
sealed interface ParentEffect {
    @MapFrom(ChildEffect.Reply::class)
    data class Echo(val message: String) : ParentEffect
}

data class ChildState(val toggled: Boolean)
data class ScreenState(val child: ChildState)

@Composable
fun PresenterScope<ChildEvent, ChildEffect>.child(): ChildState {
    on<ChildEvent.Toggle> { }
    return ChildState(false)
}

// `Flip` is fuse-routed via @MapTo to ChildEvent.Toggle; `Local` is handled
// directly. Both subtypes covered — no diagnostic should fire.
@Composable
fun screenPresenter(): Presentation<ParentEvent, ParentEffect, ScreenState> = buildPresenter {
    val childState = fuse { child() }
    on<ParentEvent.Local> { }
    ScreenState(childState)
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, funWithExtensionReceiver, functionDeclaration,
interfaceDeclaration, lambdaLiteral, localProperty, nestedClass, objectDeclaration, primaryConstructor,
propertyDeclaration, sealed */
