// RUN_PIPELINE_TILL: FRONTEND
// FILE: missingEffectMappings.kt

import com.kitakkun.fusio.MapFrom

sealed interface ChildEffect {
    data class ShowMessage(val message: String) : ChildEffect
    // No @MapFrom(ChildEffect.Dismiss::class) on the parent side -> exhaustiveness error.
    data object Dismiss : ChildEffect
}

<!MISSING_EFFECT_MAPPINGS!>sealed interface ParentEffect {
    @MapFrom(ChildEffect.ShowMessage::class)
    data class ShowSnackbar(val message: String) : ParentEffect
}<!>

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, interfaceDeclaration, nestedClass, objectDeclaration,
primaryConstructor, propertyDeclaration, sealed */
