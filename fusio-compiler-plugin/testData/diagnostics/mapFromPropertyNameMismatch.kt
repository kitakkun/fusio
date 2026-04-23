// RUN_PIPELINE_TILL: FRONTEND
// FILE: mapFromPropertyNameMismatch.kt

import com.kitakkun.fusio.MapFrom

sealed interface ChildEffect {
    data class ShowMessage(val message: String) : ChildEffect
}

sealed interface ParentEffect {
    // Parent subtype expects `text`, but child source only provides `message`.
    <!PROPERTY_MISMATCH!>@MapFrom(ChildEffect.ShowMessage::class)
    data class ShowSnackbar(val text: String) : ParentEffect<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, interfaceDeclaration, nestedClass, primaryConstructor,
propertyDeclaration, sealed */
