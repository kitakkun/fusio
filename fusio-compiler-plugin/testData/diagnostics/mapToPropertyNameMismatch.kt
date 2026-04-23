// RUN_PIPELINE_TILL: FRONTEND
// FILE: mapToPropertyNameMismatch.kt

import com.kitakkun.fusio.MapTo

// If fusio-annotations isn't on the classpath, MapTo below should be UNRESOLVED_REFERENCE
sealed interface ChildEvent {
    data class Toggle(val id: String) : ChildEvent
}

sealed interface ParentEvent {
    <!PROPERTY_MISMATCH!>@MapTo(ChildEvent.Toggle::class)
    data class ToggleFavorite(val itemId: String) : ParentEvent<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, interfaceDeclaration, nestedClass, primaryConstructor,
propertyDeclaration, sealed */
