// RUN_PIPELINE_TILL: FRONTEND
// FILE: eventHandlerCovered.kt

import androidx.compose.runtime.Composable
import com.kitakkun.fusio.Presentation
import com.kitakkun.fusio.buildPresenter
import com.kitakkun.fusio.on

sealed interface MyEvent {
    data object Foo : MyEvent
    data object Bar : MyEvent
}

sealed interface MyEffect

data class MyState(val n: Int = 0)

// Both subtypes have handlers — no diagnostic should fire.
@Composable
fun screenPresenter(): Presentation<MyEvent, MyEffect, MyState> = buildPresenter {
    on<MyEvent.Foo> { }
    on<MyEvent.Bar> { }
    MyState()
}

/* GENERATED_FIR_TAGS: classDeclaration, data, functionDeclaration, integerLiteral, interfaceDeclaration, lambdaLiteral,
nestedClass, objectDeclaration, primaryConstructor, propertyDeclaration, sealed */
