# Step 1: aria-annotations

## Module: `aria-annotations`

Pure Kotlin module with zero dependencies. Defines annotations used by the compiler plugin.

## Annotations

### @MapTo

Maps a parent Event sealed subtype to a child Event type. Used on sealed interface members.

```kotlin
package com.kitakkun.aria

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MapTo(val target: KClass<*>)
```

**Usage:**
```kotlin
sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent
}
```

**Constraints (enforced by FIR Checker in Step 4):**
- `target` must be a sealed subtype of the child Event sealed interface
- Property names and types must match exactly between source and target

### @MapFrom

Maps a child Effect type back to a parent Effect sealed subtype. Used on sealed interface members.

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MapFrom(val source: KClass<*>)
```

**Usage:**
```kotlin
sealed interface MyScreenEffect {
    @MapFrom(FavoriteEffect.ShowMessage::class)
    data class ShowSnackbar(val message: String) : MyScreenEffect
}
```

**Constraints (enforced by FIR Checker in Step 4):**
- `source` must be a sealed subtype of the child Effect sealed interface
- Property names and types must match exactly between source and target

## Build Configuration

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    // iOS targets as needed
    
    sourceSets {
        commonMain {
            dependencies {
                // No dependencies - pure Kotlin
            }
        }
    }
}
```

## Implementation Notes

- `@Retention(AnnotationRetention.BINARY)` is required so the compiler plugin can read annotations across module boundaries
- No runtime dependencies - this module is as lightweight as possible
- KMP compatible from the start
