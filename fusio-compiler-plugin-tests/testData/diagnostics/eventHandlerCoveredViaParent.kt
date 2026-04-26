// RUN_PIPELINE_TILL: FRONTEND
// FILE: eventHandlerCoveredViaParent.kt

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

// `on<MyEvent>` covers every subtype via the supertype walk. No diagnostic.
@Composable
fun screenPresenter(): Presentation<MyState, MyEffect, MyEvent> = buildPresenter {
    on<MyEvent> { }
    MyState()
}

/* GENERATED_FIR_TAGS: classDeclaration, data, functionDeclaration, integerLiteral, interfaceDeclaration, lambdaLiteral,
nestedClass, objectDeclaration, primaryConstructor, propertyDeclaration, sealed */
