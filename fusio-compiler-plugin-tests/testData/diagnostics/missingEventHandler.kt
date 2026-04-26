// RUN_PIPELINE_TILL: FRONTEND
// FILE: missingEventHandler.kt

import androidx.compose.runtime.Composable
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.PresenterScope
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.on

sealed interface MyEvent {
    data object Foo : MyEvent
    data object Bar : MyEvent
}

sealed interface MyEffect

data class MyState(val n: Int = 0)

// `on<MyEvent.Foo>` is the only handler — `Bar` is uncovered, expect a warning.
@Composable
fun screenPresenter(): Presentation<MyState, MyEvent, MyEffect> = <!MISSING_EVENT_HANDLER_WARNING!>buildPresenter<!> {
    on<MyEvent.Foo> { }
    MyState()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, interfaceDeclaration, nestedClass, objectDeclaration,
primaryConstructor, propertyDeclaration, sealed */
