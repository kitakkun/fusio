// RUN_PIPELINE_TILL: FRONTEND
// FILE: mapToPropertyTypeMismatch.kt

import com.kitakkun.aria.MapTo

sealed interface ChildEvent {
    data class Toggle(val id: String) : ChildEvent
}

sealed interface ParentEvent {
    // `id` is named correctly but typed Int, not assignable to String.
    <!PROPERTY_MISMATCH!>@MapTo(ChildEvent.Toggle::class)
    data class ToggleFavorite(val id: Int) : ParentEvent<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, interfaceDeclaration, nestedClass, primaryConstructor,
propertyDeclaration, sealed */
