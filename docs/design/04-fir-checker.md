# Step 4: FIR Checker — @MapTo / @MapFrom Validation

## Module: `aria-compiler-plugin`

FIR (Frontend IR) checker that validates `@MapTo` and `@MapFrom` annotations at compile time, ensuring property name/type compatibility between mapped Event/Effect types.

## Architecture

```
AriaFirExtensionRegistrar
  └── configurePlugin()
        ├── +AriaFirCheckersExtension (registers checkers)
        └── registerDiagnosticContainers(AriaErrors)
```

## Components

### AriaErrors

Diagnostic factory definitions using Kotlin's `KtDiagnosticsContainer` DSL.

```kotlin
package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.diagnostics.*
import com.intellij.psi.PsiElement

object AriaErrors : KtDiagnosticsContainer() {
    // @MapTo target must be a sealed subtype of the child Event sealed interface
    val MAP_TO_INVALID_TARGET by error1<PsiElement, String>()

    // @MapFrom source must be a sealed subtype of the child Effect sealed interface
    val MAP_FROM_INVALID_SOURCE by error1<PsiElement, String>()

    // Property mismatch between source and target classes
    // A = source class name, B = target class name, C = detail message
    val PROPERTY_MISMATCH by error3<PsiElement, String, String, String>()

    // @MapTo/@MapFrom used on non-sealed-subtype class
    val ANNOTATION_ON_NON_SEALED_SUBTYPE by error1<PsiElement, String>()

    // Missing @MapTo mappings for child Event subtypes (exhaustiveness) — see Step 6
    val MISSING_EVENT_MAPPINGS by error2<PsiElement, String, String>()

    // Missing @MapFrom mappings for child Effect subtypes (exhaustiveness) — see Step 6
    val MISSING_EFFECT_MAPPINGS by error2<PsiElement, String, String>()
}
```

**Key API notes:**
- `KtDiagnosticsContainer` provides the `context(container: KtDiagnosticsContainer)` receiver for `error0`, `error1`, etc.
- `by error1<PsiElement, String>()` uses Kotlin property delegation — the property name becomes the diagnostic name
- Diagnostics must be registered via `registerDiagnosticContainers(AriaErrors)` in the registrar

### AriaErrorMessages

Human-readable message templates for each diagnostic. Without this, diagnostics would have no display text.

```kotlin
package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers

object AriaErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap =
        KtDiagnosticFactoryToRendererMap("Aria").also { map ->
            map.put(AriaErrors.MAP_TO_INVALID_TARGET, "Invalid @MapTo target: {0}")
            map.put(AriaErrors.MAP_FROM_INVALID_SOURCE, "Invalid @MapFrom source: {0}")
            map.put(
                AriaErrors.PROPERTY_MISMATCH,
                "Property mismatch between ''{0}'' and ''{1}'': {2}",
            )
            map.put(AriaErrors.ANNOTATION_ON_NON_SEALED_SUBTYPE, "@MapTo/@MapFrom can only be used on sealed interface subtypes: {0}")
            map.put(AriaErrors.MISSING_EVENT_MAPPINGS, "Missing @MapTo mappings for ''{0}'' subtypes: {1}")
            map.put(AriaErrors.MISSING_EFFECT_MAPPINGS, "Missing @MapFrom mappings for ''{0}'' subtypes: {1}")
        }
}
```

**Key API notes:**
- `KtDiagnosticFactoryToRendererMap` maps diagnostic factories to message templates
- `{0}`, `{1}`, `{2}` are positional placeholders for diagnostic arguments
- `''` escapes single quotes in MessageFormat syntax
- The `MAP` property is discovered by the diagnostic infrastructure via `BaseDiagnosticRendererFactory`

### AriaFirExtensionRegistrar

Registers FIR extensions using the `+` operator DSL.

```kotlin
package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class AriaFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::AriaFirCheckersExtension
        registerDiagnosticContainers(AriaErrors)
    }
}
```

**How registration works:**
- `+::AriaFirCheckersExtension` invokes the `unaryPlus()` operator on `(FirSession) -> FirAdditionalCheckersExtension`
- This is syntactic sugar for `FirAdditionalCheckersExtension.Factory { AriaFirCheckersExtension(it) }.unaryPlus()`
- `registerDiagnosticContainers` makes diagnostics available to the FIR diagnostic infrastructure

### AriaFirCheckersExtension

Provides the set of checkers to the FIR pipeline.

```kotlin
package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class AriaFirCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val classCheckers: Set<FirClassChecker> = setOf(
            AriaMapToChecker,
            AriaMapFromChecker,
            // AriaExhaustivenessChecker added in Step 6
        )
    }
}
```

**Available checker types in `DeclarationCheckers`:**
- `classCheckers: Set<FirClassChecker>` — checks `FirClass` declarations
- `regularClassCheckers: Set<FirRegularClassChecker>` — checks `FirRegularClass` (excludes anonymous classes)
- `functionCheckers: Set<FirFunctionChecker>` — checks functions
- `propertyCheckers: Set<FirPropertyChecker>` — checks properties
- All checkers inherit from `FirDeclarationChecker<D>` hierarchy

### AriaMapToChecker

Validates `@MapTo` annotations on sealed interface members.

```kotlin
package com.kitakkun.aria.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
import org.jetbrains.kotlin.fir.declarations.utils.isSealed
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.resolve.providers.getClassDeclaredMemberScope
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

object AriaMapToChecker : FirClassChecker(MppCheckerKind.Common) {
    // ClassId for com.kitakkun.aria.MapTo
    private val MAP_TO_CLASS_ID = ClassId.fromString("com/kitakkun/aria/MapTo")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return

        // Find @MapTo annotation on this class
        val mapToAnnotation = declaration.annotations.firstOrNull {
            it.annotationClassId == MAP_TO_CLASS_ID
        } ?: return

        // 1. Verify this class is a sealed subtype
        // (its parent sealed interface is verified by checking superTypes)

        // 2. Extract the target KClass from @MapTo(target = ...)
        val targetType: ConeKotlinType = mapToAnnotation.getKClassArgument(Name.identifier("target"))
            ?: run {
                reporter.reportOn(declaration.source, AriaErrors.MAP_TO_INVALID_TARGET, "Missing target argument")
                return
            }
        val targetClassId = targetType.classId ?: run {
            reporter.reportOn(declaration.source, AriaErrors.MAP_TO_INVALID_TARGET, "Target must be a class reference")
            return
        }

        // 3. Resolve target class and verify it's a sealed subtype
        val targetClass = resolveClassById(targetClassId, context)
        if (targetClass == null) {
            reporter.reportOn(declaration.source, AriaErrors.MAP_TO_INVALID_TARGET, "Cannot resolve target class: $targetClassId")
            return
        }

        // 4. Validate property compatibility
        validatePropertyCompatibility(
            source = declaration,
            target = targetClass,
            context = context,
            reporter = reporter,
        )
    }
}
```

### Property Validation Logic

Shared between `@MapTo` and `@MapFrom` checkers.

```kotlin
/**
 * Validates that all properties of [target] exist in [source] with matching names and types.
 * Direction: source properties must cover all target properties.
 */
private fun validatePropertyCompatibility(
    source: FirRegularClass,
    target: FirRegularClass,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
    val sourceProperties = source.collectConstructorProperties(context.session)
    val targetProperties = target.collectConstructorProperties(context.session)

    for ((name, targetType) in targetProperties) {
        val sourceType = sourceProperties[name]
        if (sourceType == null) {
            reporter.reportOn(
                source.source,
                AriaErrors.PROPERTY_MISMATCH,
                source.name.asString(),
                target.name.asString(),
                "Missing property '$name' required by target ${target.name}",
            )
            continue
        }
        if (!sourceType.isSubtypeOf(targetType, context.session)) {
            reporter.reportOn(
                source.source,
                AriaErrors.PROPERTY_MISMATCH,
                source.name.asString(),
                target.name.asString(),
                "Property '$name' type mismatch: expected ${targetType.renderReadable()}, got ${sourceType.renderReadable()}",
            )
        }
    }
}

/**
 * Collects primary constructor parameter properties as Map<Name, ConeKotlinType>.
 */
private fun FirRegularClass.collectConstructorProperties(session: FirSession): Map<String, ConeKotlinType> {
    val primaryConstructor = declarations
        .filterIsInstance<FirConstructor>()
        .firstOrNull { it.isPrimary }
        ?: return emptyMap()

    return primaryConstructor.valueParameters.associate { param ->
        param.name.asString() to param.returnTypeRef.coneType
    }
}
```

### AriaMapFromChecker

Mirror of `AriaMapToChecker` for `@MapFrom` annotations. Validates the reverse mapping (child Effect → parent Effect).

```kotlin
object AriaMapFromChecker : FirClassChecker(MppCheckerKind.Common) {
    private val MAP_FROM_CLASS_ID = ClassId.fromString("com/kitakkun/aria/MapFrom")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return

        val mapFromAnnotation = declaration.annotations.firstOrNull {
            it.annotationClassId == MAP_FROM_CLASS_ID
        } ?: return

        // Extract source KClass from @MapFrom(source = ...)
        val sourceType = mapFromAnnotation.getKClassArgument(Name.identifier("source"))
            ?: run {
                reporter.reportOn(declaration.source, AriaErrors.MAP_FROM_INVALID_SOURCE, "Missing source argument")
                return
            }
        val sourceClassId = sourceType.classId ?: run {
            reporter.reportOn(declaration.source, AriaErrors.MAP_FROM_INVALID_SOURCE, "Source must be a class reference")
            return
        }

        val sourceClass = resolveClassById(sourceClassId, context)
        if (sourceClass == null) {
            reporter.reportOn(declaration.source, AriaErrors.MAP_FROM_INVALID_SOURCE, "Cannot resolve source class: $sourceClassId")
            return
        }

        // For @MapFrom, property validation direction is reversed:
        // source (child Effect subtype) properties must cover target (parent Effect subtype) properties
        validatePropertyCompatibility(
            source = sourceClass,  // child Effect subtype (has the data)
            target = declaration,  // parent Effect subtype (needs the data)
            context = context,
            reporter = reporter,
        )
    }
}
```

## Key Kotlin Compiler APIs Used

### Annotation Access
```kotlin
// Get annotation by ClassId
val annotation = firClass.annotations.firstOrNull {
    it.annotationClassId == MY_ANNOTATION_CLASS_ID
}

// Extract KClass argument from annotation
// Uses: org.jetbrains.kotlin.fir.expressions.getKClassArgument
val targetType: ConeKotlinType? = annotation.getKClassArgument(Name.identifier("target"))
val classId: ClassId? = targetType?.classId
```

### Sealed Class Introspection
```kotlin
// Get all direct sealed subtypes of a sealed class/interface
// Uses: org.jetbrains.kotlin.fir.declarations.getSealedClassInheritors
val inheritorIds: List<ClassId> = firRegularClass.getSealedClassInheritors(session)
```

### Diagnostic Reporting
```kotlin
// Report diagnostic on a source element
// context(context: CheckerContext, reporter: DiagnosticReporter)
reporter.reportOn(declaration.source, AriaErrors.PROPERTY_MISMATCH, arg1, arg2, arg3)
```

### Class Resolution
```kotlin
// Resolve a ClassId to FirRegularClass
fun resolveClassById(classId: ClassId, context: CheckerContext): FirRegularClass? {
    val symbol = context.session.symbolProvider.getClassLikeSymbolByClassId(classId)
    return (symbol?.fir as? FirRegularClass)
}
```

## Checker Flow Summary

```
User code:
  @MapTo(FavoriteEvent.Toggle::class)
  data class ToggleFavorite(val id: String) : MyScreenEvent

AriaMapToChecker.check(ToggleFavorite):
  1. Find @MapTo annotation → present
  2. Extract target = FavoriteEvent.Toggle
  3. Resolve FavoriteEvent.Toggle → FirRegularClass
  4. Verify FavoriteEvent.Toggle is sealed subtype of FavoriteEvent
  5. Compare properties:
     - ToggleFavorite has: id: String ✓
     - FavoriteEvent.Toggle has: id: String ✓
     → Match! No diagnostic.

Error case:
  @MapTo(FavoriteEvent.Toggle::class)
  data class ToggleFavorite(val itemId: String) : MyScreenEvent  // wrong name!

  → PROPERTY_MISMATCH: "Missing property 'id' required by target Toggle"
```

## Build Dependencies

```kotlin
// aria-compiler-plugin/build.gradle.kts
dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
}
```

## Open Questions

1. **Cross-module sealed class resolution**: When the child Event sealed interface is in a different module, `getSealedClassInheritors()` may not return all inheritors if the module isn't fully resolved yet. Need to verify FIR resolution order guarantees.

2. **Data class vs regular class**: Should we require `@MapTo`/`@MapFrom` targets to be `data class`? This would guarantee the presence of `componentN()` functions and simplify property extraction. Currently we only check primary constructor parameters.

3. **Subtype vs exact match for properties**: Should property types allow subtype relationships (e.g., `List<String>` mapping to `Collection<String>`) or require exact type match? Current plan uses `isSubtypeOf` for flexibility.
