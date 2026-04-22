# Step 6: FIR Checker — Exhaustiveness Verification

## Module: `aria-compiler-plugin`

FIR checker that verifies `@MapTo` / `@MapFrom` annotations cover all subtypes of the child Event/Effect sealed interfaces used in `mappedScope`. This ensures no child Events are silently dropped and no child Effects are lost.

## Problem

Without exhaustiveness checking, a user could add a new subtype to a child Event sealed interface but forget to add a corresponding `@MapTo` entry in the parent. The child presenter would never receive that event, and the bug would be silent.

```kotlin
// Child Event — has 3 subtypes
sealed interface FavoriteEvent {
    data class Toggle(val id: String) : FavoriteEvent
    data class Refresh(val id: String) : FavoriteEvent
    data object ClearAll : FavoriteEvent  // NEW — added later
}

// Parent Event — only maps 2 of 3!
sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent

    @MapTo(FavoriteEvent.Refresh::class)
    data class RefreshFavorite(val id: String) : MyScreenEvent

    // Missing: no @MapTo for FavoriteEvent.ClearAll!
    // → Should be a compile error
}
```

## Architecture

This checker operates at a different level than Step 4's checkers:
- **Step 4**: Validates individual `@MapTo`/`@MapFrom` annotations (property compatibility)
- **Step 6**: Validates completeness across the entire parent sealed interface

```
AriaFirCheckersExtension
  └── declarationCheckers
        ├── AriaMapToChecker         (Step 4)
        ├── AriaMapFromChecker       (Step 4)
        └── AriaExhaustivenessChecker (Step 6) — THIS
```

## Design: Where to Check

There are two possible places to perform exhaustiveness checking:

### Option A: On the parent sealed interface (Chosen)

Check when we encounter a sealed interface whose subtypes have `@MapTo` or `@MapFrom` annotations.

**Pros:**
- All information is in one place (the parent sealed interface and its subtypes)
- Can report the error on the parent sealed interface itself
- Works even without `mappedScope` call (catches errors early)

**Cons:**
- Must infer which child sealed interface to check against
- A parent may map to multiple different child sealed interfaces

### Option B: On the `mappedScope` call site

Check at the `mappedScope<ChildEvent, ChildEffect> { ... }` call, where both parent and child types are known.

**Pros:**
- Both parent and child types are explicitly available
- Clear association between mapping and usage

**Cons:**
- Requires `FirFunctionCallChecker` (expression checker, not declaration checker)
- Error only appears at the call site, not at the missing mapping

**Decision**: Use **Option A** for Event exhaustiveness and **Option B** as a supplementary check. The primary check on the parent sealed interface catches missing mappings earliest.

## Components

### AriaExhaustivenessChecker

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
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

object AriaExhaustivenessChecker : FirClassChecker(MppCheckerKind.Common) {
    private val MAP_TO_CLASS_ID = ClassId.fromString("com/kitakkun/aria/MapTo")
    private val MAP_FROM_CLASS_ID = ClassId.fromString("com/kitakkun/aria/MapFrom")

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration !is FirRegularClass) return
        if (!declaration.isSealed) return

        // Collect all @MapTo targets from subtypes
        checkEventExhaustiveness(declaration, context, reporter)

        // Collect all @MapFrom sources from subtypes
        checkEffectExhaustiveness(declaration, context, reporter)
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkEventExhaustiveness(
        sealedClass: FirRegularClass,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        val inheritorIds = sealedClass.getSealedClassInheritors(context.session)
        val inheritors = inheritorIds.mapNotNull { resolveClassById(it, context) }

        // Collect @MapTo targets grouped by child sealed interface
        val mapToTargets = mutableMapOf<ClassId, MutableSet<ClassId>>()

        for (inheritor in inheritors) {
            val mapToAnnotation = inheritor.annotations.firstOrNull {
                it.annotationClassId == MAP_TO_CLASS_ID
            } ?: continue

            val targetType = mapToAnnotation.getKClassArgument(Name.identifier("target")) ?: continue
            val targetClassId = targetType.classId ?: continue

            // Find the parent sealed interface of the target
            val targetClass = resolveClassById(targetClassId, context) ?: continue
            val childSealedParent = findSealedParent(targetClass, context) ?: continue

            mapToTargets
                .getOrPut(childSealedParent.classId) { mutableSetOf() }
                .add(targetClassId)
        }

        // For each child sealed interface, check all subtypes are covered
        for ((childSealedId, coveredSubtypes) in mapToTargets) {
            val childSealed = resolveClassById(childSealedId, context) ?: continue
            val allChildSubtypes = childSealed.getSealedClassInheritors(context.session).toSet()

            val missingSubtypes = allChildSubtypes - coveredSubtypes
            if (missingSubtypes.isNotEmpty()) {
                val missingNames = missingSubtypes.joinToString(", ") { it.shortClassName.asString() }
                reporter.reportOn(
                    sealedClass.source,
                    AriaErrors.MISSING_EVENT_MAPPINGS,
                    childSealed.name.asString(),
                    missingNames,
                )
            }
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkEffectExhaustiveness(
        sealedClass: FirRegularClass,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        val inheritorIds = sealedClass.getSealedClassInheritors(context.session)
        val inheritors = inheritorIds.mapNotNull { resolveClassById(it, context) }

        // Collect @MapFrom sources grouped by child sealed interface
        val mapFromSources = mutableMapOf<ClassId, MutableSet<ClassId>>()

        for (inheritor in inheritors) {
            val mapFromAnnotation = inheritor.annotations.firstOrNull {
                it.annotationClassId == MAP_FROM_CLASS_ID
            } ?: continue

            val sourceType = mapFromAnnotation.getKClassArgument(Name.identifier("source")) ?: continue
            val sourceClassId = sourceType.classId ?: continue

            val sourceClass = resolveClassById(sourceClassId, context) ?: continue
            val childSealedParent = findSealedParent(sourceClass, context) ?: continue

            mapFromSources
                .getOrPut(childSealedParent.classId) { mutableSetOf() }
                .add(sourceClassId)
        }

        // For each child sealed interface, check all subtypes are covered
        for ((childSealedId, coveredSubtypes) in mapFromSources) {
            val childSealed = resolveClassById(childSealedId, context) ?: continue
            val allChildSubtypes = childSealed.getSealedClassInheritors(context.session).toSet()

            val missingSubtypes = allChildSubtypes - coveredSubtypes
            if (missingSubtypes.isNotEmpty()) {
                val missingNames = missingSubtypes.joinToString(", ") { it.shortClassName.asString() }
                reporter.reportOn(
                    sealedClass.source,
                    AriaErrors.MISSING_EFFECT_MAPPINGS,
                    childSealed.name.asString(),
                    missingNames,
                )
            }
        }
    }

    /**
     * Finds the direct sealed parent interface of a class.
     */
    private fun findSealedParent(
        firClass: FirRegularClass,
        context: CheckerContext,
    ): FirRegularClass? {
        for (superTypeRef in firClass.superTypeRefs) {
            val superClassId = superTypeRef.coneType.classId ?: continue
            val superClass = resolveClassById(superClassId, context) ?: continue
            if (superClass.isSealed) return superClass
        }
        return null
    }
}
```

## Exhaustiveness Rules

### Event Mapping (`@MapTo`) Exhaustiveness

For a parent sealed interface `P` that maps to child sealed interface `C`:

```
∀ subtype c ∈ C.sealedSubtypes:
  ∃ subtype p ∈ P.sealedSubtypes:
    p has @MapTo(c::class)
```

**In plain words**: Every subtype of the child Event must have at least one `@MapTo` mapping from a parent Event subtype.

### Effect Mapping (`@MapFrom`) Exhaustiveness

For a parent sealed interface `P` that maps from child sealed interface `C`:

```
∀ subtype c ∈ C.sealedSubtypes:
  ∃ subtype p ∈ P.sealedSubtypes:
    p has @MapFrom(c::class)
```

**In plain words**: Every subtype of the child Effect must have at least one `@MapFrom` mapping to a parent Effect subtype.

## Error Messages

```
// Event exhaustiveness
error: Parent sealed interface 'MyScreenEvent' is missing @MapTo mappings for 
FavoriteEvent subtypes: ClearAll
  → Add a subtype with @MapTo(FavoriteEvent.ClearAll::class) to MyScreenEvent

// Effect exhaustiveness
error: Parent sealed interface 'MyScreenEffect' is missing @MapFrom mappings for 
FavoriteEffect subtypes: ShowError
  → Add a subtype with @MapFrom(FavoriteEffect.ShowError::class) to MyScreenEffect
```

## Edge Cases

### 1. Partial Mapping (Intentional)

Sometimes a parent intentionally doesn't forward all child events. For example, the parent may handle some child events internally.

**Solution**: Provide an opt-out mechanism:

```kotlin
sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFavorite(val id: String) : MyScreenEvent

    // Explicitly suppress exhaustiveness for FavoriteEvent.ClearAll
    // Option A: @Suppress annotation
    // Option B: @IgnoreMapping(FavoriteEvent.ClearAll::class)
}
```

**Decision**: Defer this to v2. For v1, require all mappings and let users add no-op mappings if needed. This is safer — silent drops are worse than verbose mappings.

### 2. Multiple Child Sealed Interfaces

A parent sealed interface may map to subtypes of different child sealed interfaces:

```kotlin
sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.Toggle::class)
    data class ToggleFav(val id: String) : MyScreenEvent

    @MapTo(SearchEvent.Query::class)
    data class Search(val query: String) : MyScreenEvent
}
```

The checker handles this by grouping `@MapTo` targets by their parent sealed interface and checking each group independently.

### 3. Sealed Interface in Different Module

When the child sealed interface is in a different module:
- `getSealedClassInheritors(session)` should work across modules if the dependency is on the compilation classpath
- The FIR resolver loads sealed inheritors from metadata (`.class` files) for library modules
- **Risk**: If a new subtype is added to the child module but the parent module isn't recompiled, the check won't run. This is inherent to separate compilation — IC should invalidate the parent module when the child module changes.

### 4. `object` Subtypes (No Properties)

```kotlin
sealed interface FavoriteEvent {
    data object ClearAll : FavoriteEvent
}

sealed interface MyScreenEvent {
    @MapTo(FavoriteEvent.ClearAll::class)
    data object ClearFavorites : MyScreenEvent
}
```

No property matching needed — the mapping is purely type-based. The FIR checker from Step 4 already handles this (empty property list matches empty property list).

## Registration

Already registered in `AriaFirCheckersExtension`:

```kotlin
class AriaFirCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val classCheckers: Set<FirClassChecker> = setOf(
            AriaMapToChecker,         // Step 4
            AriaMapFromChecker,       // Step 4
            AriaExhaustivenessChecker, // Step 6
        )
    }
}
```

## Open Questions

1. **Opt-out mechanism**: Should we support `@IgnoreMapping` or `@Suppress("MISSING_EVENT_MAPPINGS")` for intentional partial mappings? Current plan is to defer to v2 and require full exhaustiveness in v1.

2. **Warning vs Error**: Should missing mappings be errors (blocking) or warnings (advisory)? Errors are safer but may frustrate users during incremental development. Consider making it configurable via the Gradle plugin option: `aria { exhaustivenessLevel = "error" | "warning" }`.

3. **Performance**: For large sealed hierarchies, `getSealedClassInheritors` + `resolveClassById` for each inheritor could be expensive. Profile in real-world usage and cache if needed.

4. **Transitive exhaustiveness**: If child C maps to grandchild G, should the parent P also be checked for G's subtypes? Current plan: no — each level only checks its direct child mappings. Transitive correctness follows from local correctness at each level.

5. **False positive on unrelated sealed interfaces**: The exhaustiveness checker runs on ALL sealed interfaces. If a sealed interface happens to have subtypes with `@MapTo` but is never used in `mappedScope`, the checker still fires. This is actually desirable (catches errors early), but may confuse users who add `@MapTo` annotations experimentally. Consider: the checker only fires when at least one `@MapTo` or `@MapFrom` annotation exists on a subtype, so it's an intentional opt-in by the user.
