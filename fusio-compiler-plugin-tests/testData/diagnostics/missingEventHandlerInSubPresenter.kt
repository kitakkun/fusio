// RUN_PIPELINE_TILL: FRONTEND
// FILE: missingEventHandlerInSubPresenter.kt

import androidx.compose.runtime.Composable
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.on

sealed interface SubEvent {
    data object Foo : SubEvent
    data object Bar : SubEvent
}

sealed interface SubEffect

data class SubState(val n: Int = 0)

// Sub-presenter: extension function on PresenterScope. `Bar` isn't handled
// by any `on<>`, expect a warning at the function name.
@Composable
fun PresenterScope<SubEvent, SubEffect>.<!MISSING_EVENT_HANDLER_WARNING!>subPresenter<!>(): SubState {
    on<SubEvent.Foo> { }
    return SubState()
}

/* GENERATED_FIR_TAGS: classDeclaration, data, funWithExtensionReceiver, functionDeclaration, integerLiteral,
interfaceDeclaration, lambdaLiteral, nestedClass, objectDeclaration, primaryConstructor, propertyDeclaration, sealed */
