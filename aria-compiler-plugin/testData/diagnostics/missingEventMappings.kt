// RUN_PIPELINE_TILL: FRONTEND
// FILE: missingEventMappings.kt

import com.kitakkun.aria.MapTo

sealed interface ChildEvent {
    data class Toggle(val id: String) : ChildEvent
    // No @MapTo(ChildEvent.Refresh::class) on the parent side -> exhaustiveness error.
    data object Refresh : ChildEvent
}

<!MISSING_EVENT_MAPPINGS!>sealed interface ParentEvent {
    @MapTo(ChildEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : ParentEvent
}<!>

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, interfaceDeclaration, nestedClass, objectDeclaration,
primaryConstructor, propertyDeclaration, sealed */
