# Step 5: IR Transformer — `mappedScope` Code Generation

## Module: `fusio-compiler-plugin`

IR (Intermediate Representation) transformer that replaces the `mappedScope` stub call with generated bridging code that:
1. Filters parent `eventFlow` to extract mapped child Events via `@MapTo`
2. Creates a child `PresenterScope` with the filtered event flow
3. Invokes the child Presenter
4. Forwards child Effects back to parent via `@MapFrom` mappings

## Architecture

```
FusioIrGenerationExtension
  └── generate(moduleFragment, pluginContext)
        └── MappedScopeTransformer (IrElementTransformerVoidWithContext)
              └── visitCall() — intercepts mappedScope<ChildEvent, ChildEffect, ChildState> { ... }
```

## Components

### FusioIrGenerationExtension

Entry point for IR code generation. Implements `IrGenerationExtension`.

```kotlin
package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

class FusioIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transform(MappedScopeTransformer(pluginContext), null)
    }
}
```

### MappedScopeTransformer

Core transformer that intercepts `mappedScope` calls and replaces them with generated bridging code.

```kotlin
package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class MappedScopeTransformer(
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoidWithContext() {

    companion object {
        val MAPPED_SCOPE_CALLABLE_ID = CallableId(
            FqName("com.kitakkun.fusio"),
            Name.identifier("mappedScope"),
        )
        val PRESENTER_SCOPE_CLASS_ID = ClassId.fromString("com/kitakkun/fusio/PresenterScope")
        val FUSIO_CLASS_ID = ClassId.fromString("com/kitakkun/fusio/Fusio")
    }

    override fun visitCall(expression: IrCall): IrExpression {
        // Check if this is a call to mappedScope
        if (!isMappedScopeCall(expression)) {
            return super.visitCall(expression)
        }

        return transformMappedScope(expression)
    }

    private fun isMappedScopeCall(call: IrCall): Boolean {
        val callee = call.symbol.owner
        return callee.name == Name.identifier("mappedScope")
            && callee.parent.let { /* verify it's in com.kitakkun.fusio package */ }
    }
}
```

### What `mappedScope` Generates

#### User writes:
```kotlin
// Inside buildPresenter(eventFlow) { ... }
val favoriteState = mappedScope { favorite() }
```

#### Compiler generates (conceptual):
```kotlin
// Note: This runs inside a @Composable context (within buildPresenter's lambda),
// so Compose runtime functions (remember, LaunchedEffect, DisposableEffect) are available.
// The IR transformer accesses PresenterScope.eventFlow which is `internal` —
// this is fine because IR transformers bypass Kotlin visibility checks.
val favoriteState = run {
    // 1. Create mapped event flow: filter parent events → child events
    val childEventFlow: Flow<FavoriteEvent> = this.eventFlow
        .filterIsInstance<MyScreenEvent>()
        .mapNotNull { parentEvent ->
            when (parentEvent) {
                is MyScreenEvent.ToggleFavorite -> FavoriteEvent.Toggle(id = parentEvent.id)
                // ... all @MapTo mappings
                else -> null
            }
        }

    // 2. Create child PresenterScope — MUST be wrapped in remember to survive recomposition.
    // Without remember, a new Channel is created every recomposition, losing pending Effects.
    val childScope = remember { PresenterScope<FavoriteEvent, FavoriteEffect>(childEventFlow) }

    // 3. Invoke child presenter (the lambda body)
    val childResult: Fusio<FavoriteState, FavoriteEffect> = childScope.favorite()

    // 4. Forward child effects → parent effects via @MapFrom mappings
    LaunchedEffect(Unit) {
        childResult.effectFlow.collect { childEffect ->
            val parentEffect: MyScreenEffect? = when (childEffect) {
                is FavoriteEffect.ShowMessage -> MyScreenEffect.ShowSnackbar(message = childEffect.message)
                // ... all @MapFrom mappings
                else -> null
            }
            if (parentEffect != null) {
                this@buildPresenter.emitEffect(parentEffect)
            }
        }
    }

    // 5. Register cleanup
    DisposableEffect(Unit) {
        onDispose { childScope.close() }
    }

    // 6. Return child state (unwrap Fusio<State, Effect> → State)
    childResult.state
}
```

### IR Construction Details

The transformer must build the above code as IR nodes. Key operations:

#### 1. Resolving Runtime Symbols

```kotlin
// Use DeclarationFinder API (IC-compatible, preferred over deprecated referenceClass)
private fun resolveSymbols(pluginContext: IrPluginContext, fromFile: IrFile) {
    val finder = pluginContext.finderForBuiltins()
    val sourceFinder = pluginContext.finderForSource(fromFile)

    // Runtime classes
    val presenterScopeClass = finder.findClass(PRESENTER_SCOPE_CLASS_ID)!!
    val fusioClass = finder.findClass(FUSIO_CLASS_ID)!!

    // Runtime functions
    val emitEffectFn = finder.findFunctions(
        CallableId(PRESENTER_SCOPE_CLASS_ID, Name.identifier("emitEffect"))
    ).single()

    // Compose runtime
    val launchedEffectFn = finder.findFunctions(
        CallableId(FqName("androidx.compose.runtime"), Name.identifier("LaunchedEffect"))
    ).first { it.owner.valueParameters.size == 2 } // (key, block)

    val disposableEffectFn = finder.findFunctions(
        CallableId(FqName("androidx.compose.runtime"), Name.identifier("DisposableEffect"))
    ).first()
}
```

#### 2. Building the Event Mapping `when` Expression

```kotlin
/**
 * Generates IR for the event mapping when-expression.
 *
 * The @MapTo annotations on the parent sealed subtypes define the mapping.
 * For each @MapTo(ChildEvent.Foo::class) on ParentEvent.Bar:
 *   is ParentEvent.Bar -> ChildEvent.Foo(prop1 = it.prop1, prop2 = it.prop2)
 */
private fun buildEventMappingWhen(
    builder: DeclarationIrBuilder,
    parentEventType: IrType,      // MyScreenEvent
    childEventType: IrType,       // FavoriteEvent
    mappings: List<EventMapping>,  // collected from @MapTo annotations
): IrExpression {
    // Build IrWhen with branches for each mapping
    return builder.irWhen(childEventType.makeNullable(), mappings.map { mapping ->
        builder.irBranch(
            condition = builder.irIs(/* value */, mapping.parentSubtype),
            result = buildConstructorCall(mapping.childSubtype, mapping.propertyMappings),
        )
    } + builder.irElseBranch(builder.irNull()))
}
```

#### 3. Collecting Mappings from FIR Metadata

At IR stage, annotation information is available on IR declarations:

```kotlin
/**
 * Collects @MapTo mappings by scanning the parent Event sealed interface subtypes.
 */
private fun collectEventMappings(
    parentEventClass: IrClass,
    childEventClass: IrClass,
): List<EventMapping> {
    return parentEventClass.sealedSubclasses.mapNotNull { subclass ->
        val mapToAnnotation = subclass.owner.annotations.firstOrNull {
            it.isAnnotation(MAP_TO_CLASS_ID)
        } ?: return@mapNotNull null

        val targetClassId = mapToAnnotation.getClassIdArgument(0)
            ?: return@mapNotNull null

        // Verify target is a subtype of childEventClass
        EventMapping(
            parentSubtype = subclass,
            childSubtype = targetClassId,
            propertyMappings = collectPropertyMappings(subclass.owner, targetClassId),
        )
    }
}

data class EventMapping(
    val parentSubtype: IrClassSymbol,
    val childSubtype: ClassId,
    val propertyMappings: List<PropertyMapping>,
)

data class PropertyMapping(
    val name: String,
    // source and target properties have the same name by FIR validation
)
```

#### 4. Effect Forwarding via LaunchedEffect

```kotlin
/**
 * Generates:
 * LaunchedEffect(Unit) {
 *     childResult.effectFlow.collect { childEffect ->
 *         when (childEffect) {
 *             is ChildEffect.X -> parentScope.emitEffect(ParentEffect.Y(...))
 *         }
 *     }
 * }
 */
private fun buildEffectForwarding(
    builder: DeclarationIrBuilder,
    childResultVar: IrVariable,
    parentScopeVar: IrVariable,
    effectMappings: List<EffectMapping>,
): IrExpression {
    // Similar structure to event mapping but in reverse direction
    // Uses @MapFrom annotations on parent Effect sealed subtypes
}
```

### Type Parameter Extraction

`mappedScope` has 3 type parameters: `<ChildEvent, ChildEffect, ChildState>`. At call site, all 3 are typically inferred from the lambda return type (`Fusio<ChildState, ChildEffect>`), so users just write `mappedScope { favorite() }`.

At the IR level:
```kotlin
// IrCall has typeArguments — all 3 are resolved by type inference at this stage
val childEventType = expression.getTypeArgument(0)!!   // ChildEvent
val childEffectType = expression.getTypeArgument(1)!!   // ChildEffect
val childStateType = expression.getTypeArgument(2)!!    // ChildState (used for return type)

// The parent types: mappedScope is declared on PresenterScope<*, *> (star projections),
// so the extension receiver type doesn't carry parent type args directly.
// Instead, we resolve parent types from the enclosing buildPresenter context:
// - Walk up the IR scope stack to find the buildPresenter call
// - Extract Event/Effect type arguments from buildPresenter<Event, Effect, UiState>
// - Alternatively, find the actual PresenterScope variable in scope and read its concrete type
val parentEventType = resolveParentEventType(expression)   // MyScreenEvent
val parentEffectType = resolveParentEffectType(expression)  // MyScreenEffect
```

### Compose Plugin Ordering

The IR transformer MUST run **before** the Compose compiler plugin. This is critical because:

1. Fusio generates `@Composable` lambda bodies (`LaunchedEffect`, `DisposableEffect`, `remember`)
2. The Compose plugin must see these generated calls to apply its own transformations (slot table, restartability, etc.)
3. If Compose runs first, the generated code would not get Compose IR treatment

**Ordering mechanism:**
- Plugin execution order follows `-Xplugin` argument order in the Kotlin compiler
- The Fusio Gradle plugin should be applied before the Compose plugin
- Alternatively, use `-Xcompiler-plugin-order=com.kitakkun.fusio,org.jetbrains.compose.compiler` (Kotlin 2.1+)

## Key Kotlin Compiler IR APIs Used

### IrGenerationExtension
```kotlin
interface IrGenerationExtension {
    fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext)
}
```

### IrPluginContext (K2 / IC-compatible API)
```kotlin
interface IrPluginContext : IrGeneratorContext {
    // Preferred: IC-compatible declaration finders
    fun finderForBuiltins(): DeclarationFinder
    fun finderForSource(fromFile: IrFile): DeclarationFinder
}

interface DeclarationFinder {
    fun findClass(classId: ClassId): IrClassSymbol?
    fun findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol>
    fun findProperties(callableId: CallableId): Collection<IrPropertySymbol>
    fun findConstructors(classId: ClassId): Collection<IrConstructorSymbol>
}
```

**Important**: The old `referenceClass()`, `referenceFunctions()` etc. on `IrPluginContext` are deprecated. Use `finderForBuiltins()` / `finderForSource(fromFile)` for IC (Incremental Compilation) compatibility.

### IrElementTransformerVoidWithContext
```kotlin
abstract class IrElementTransformerVoidWithContext : IrElementTransformerVoid() {
    // Maintains a scope stack for tracking visited declarations
    // Override visitCallNew, visitFunctionNew, etc. for scoped transformations
}
```

### DeclarationIrBuilder
```kotlin
// Used to construct IR nodes within a declaration scope
val builder = DeclarationIrBuilder(pluginContext, symbol)
builder.irCall(functionSymbol)
builder.irGet(variable)
builder.irWhen(type, branches)
builder.irBranch(condition, result)
builder.irIs(value, type)
builder.irNull()
builder.irReturn(value)
builder.irBlock { /* multiple statements */ }
builder.irTemporary(value, nameHint = "childScope")
```

### Lambda Construction in IR

`LaunchedEffect`, `DisposableEffect`, and `remember` all take lambda parameters. In IR, lambdas must be manually constructed:

```kotlin
// 1. Create the lambda function
val lambdaFun = pluginContext.irBuiltIns.irFactory.buildFun {
    name = Name.special("<anonymous>")
    returnType = pluginContext.irBuiltIns.unitType
    origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
}.apply {
    parent = currentDeclarationParent
    body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
        // lambda body statements
    }
}

// 2. Wrap as IrFunctionExpression
val lambda = IrFunctionExpressionImpl(
    startOffset, endOffset,
    type = functionType,  // e.g., Function0<Unit> or suspend Function1<CoroutineScope, Unit>
    function = lambdaFun,
    origin = IrStatementOrigin.LAMBDA,
)
```

This is required for every generated `LaunchedEffect { ... }`, `DisposableEffect { onDispose { ... } }`, and `remember { ... }` call in the `mappedScope` output.

## Transformation Summary

```
Input IR:
  CALL mappedScope<FavoriteEvent, FavoriteEffect>
    LAMBDA: PresenterScope<FavoriteEvent, FavoriteEffect>.() -> Fusio<FavoriteState, FavoriteEffect>
      CALL favorite()

Output IR:
  BLOCK {
    // 1. val childEventFlow = parentScope.eventFlow.mapNotNull { when(it) { ... } }
    // 2. val childScope = remember { PresenterScope(childEventFlow) }
    // 3. val childResult = childScope.block()  // invoke lambda
    // 4. LaunchedEffect(Unit) { childResult.effectFlow.collect { when(it) { ... } } }
    // 5. DisposableEffect(Unit) { onDispose { childScope.close() } }
    // 6. childResult.state  // return value
  }
```

## Recomposition Stability

The generated code must be stable across recompositions:

1. **`childEventFlow`**: Created from `this.eventFlow.mapNotNull { ... }`. This is a cold Flow — it's fine to recreate on each recomposition since `collect` inside `LaunchedEffect(Unit)` only runs once.

2. **`childScope`**: Wrapped in `remember { }` — survives recomposition. The Channel inside PresenterScope persists.

3. **`childResult`**: The lambda `childScope.favorite()` re-executes on each recomposition (it's `@Composable`). This produces a new `Fusio` instance each time, but the `state` inside is managed by Compose's `remember`/`mutableStateOf` in the child presenter. The `effectFlow` reference from the remembered child scope is stable.

4. **`LaunchedEffect(Unit)`**: Runs once and collects effects for the lifetime of the composition. Since the child scope is `remember`ed, the effect flow reference is stable.

5. **`DisposableEffect(Unit)`**: Registers cleanup once. Closes the child scope's Channel when the composition is destroyed.

## Open Questions

1. **Nested `mappedScope` support**: When a child presenter itself uses `mappedScope`, the transformer must handle recursive transformation. The current design naturally supports this since each `mappedScope` call is transformed independently — the child's `eventFlow` becomes the parent for the grandchild. Need to verify the transformer visits nested calls correctly.

2. **~~`remember` for PresenterScope~~** (RESOLVED): The generated child `PresenterScope` MUST be wrapped in `remember { }` to survive recomposition. Without it, every recomposition creates a new Channel, losing pending Effects. This is now reflected in the generated code above.

3. **Incremental compilation safety**: Using `finderForBuiltins()` vs `finderForSource(fromFile)` — Fusio's runtime types (`PresenterScope`, `Fusio`) are library types, so `finderForBuiltins()` is correct. But for resolving user's Event/Effect sealed classes, we need `finderForSource(fromFile)` to properly track IC dependencies.

4. **Error recovery**: If annotation resolution fails at IR stage (shouldn't happen if FIR checker ran), should the transformer leave the `mappedScope` stub call in place (will throw at runtime with a clear message) or emit a compiler error?

5. **Parent type resolution from star-projected receiver**: `mappedScope` is declared on `PresenterScope<*, *>`, so the extension receiver doesn't carry parent type arguments. The transformer must resolve parent Event/Effect types from context. Options:
   - (a) Walk the IR scope stack to find the enclosing `buildPresenter` call and extract its type arguments
   - (b) Find the `PresenterScope` local variable (from `remember { PresenterScope<E, Eff>(eventFlow) }`) and read its concrete type
   - (c) Change the API: make `mappedScope` generic on parent types too, e.g., `fun <PE, PEff, CE, CEff, CS> PresenterScope<PE, PEff>.mappedScope(...)` — more verbose but type-safe at IR level
   - Current plan: option (a), since `buildPresenter` establishes the scope and its type arguments are always resolved by FIR.
