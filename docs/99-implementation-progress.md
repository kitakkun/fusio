# Implementation Progress

Last updated: 2026-04-23

## 🎉 MILESTONE: mappedScope IR transformation works end-to-end!

Sample now compiles successfully with `mappedScope { favorite() }`. The IR transformer
rewrites the call into block IR that creates a child PresenterScope, invokes the lambda
via `FunctionN.invoke()`, and extracts `.state` from the returned Aria.

### Key lessons learned

1. **Plugin ordering is essential**: Compose plugin injects `$composer`/`$changed` params
   into @Composable lambdas. Aria must run BEFORE Compose. Use:
   ```
   -Xcompiler-plugin-order=com.github.kitakkun.aria>androidx.compose.compiler.plugins.kotlin
   ```
   Format is `pluginA>pluginB` (A runs before B), NOT comma-separated.

2. **Lambda invocation**: Call lambdas via `Function1.invoke()` with the lambda as
   dispatch receiver. Direct `irCall(lambdaFunction.symbol)` triggers JVM codegen
   crashes because JVM backend doesn't know how to invoke raw lambda bodies.

3. **IR API migration (Kotlin 2.3.20)**:
   - `call.arguments[i]` replaces `call.getValueArgument(i)` / `extensionReceiver`
   - `call.typeArguments[i]` replaces `call.getTypeArgument(i)`
   - Need `@OptIn(UnsafeDuringIrConstructionAPI::class)`


## Module Status

| Module | Status | Notes |
|--------|--------|-------|
| aria-annotations | DONE | @MapTo, @MapFrom — builds successfully |
| aria-runtime | DONE | Aria, PresenterScope, buildPresenter, on<>, mappedScope stub — builds successfully |
| aria-compiler-plugin | DONE | FIR checkers + IR transformer (stub) — builds successfully |
| aria-gradle-plugin | DONE | KotlinCompilerPluginSupportPlugin — builds successfully |
| sample | DONE | Composite build, compiles with FIR checker + annotations — builds successfully |

## Project Structure

```
aria/
├── gradle/libs.versions.toml      (Kotlin 2.3.20, Compose 1.10.3, Coroutines 1.10.2)
├── settings.gradle.kts             (4 modules, sample is separate composite build)
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradle/               (Gradle 8.14 wrapper)
├── aria-annotations/               ✅ builds
│   └── src/commonMain/kotlin/com/github/kitakkun/aria/
│       ├── MapTo.kt
│       └── MapFrom.kt
├── aria-runtime/                   ✅ builds
│   └── src/commonMain/kotlin/com/github/kitakkun/aria/
│       ├── Aria.kt                 (data class Aria<State, Effect>)
│       ├── PresenterScope.kt       (eventFlow, emitEffect, internalEffectFlow)
│       ├── BuildPresenter.kt       (@Composable buildPresenter)
│       ├── On.kt                   (inline reified on<E>)
│       └── MappedScope.kt          (stub, throws error if plugin not applied)
├── aria-compiler-plugin/           🔧 fixing build errors
│   └── src/main/kotlin/com/github/kitakkun/aria/compiler/
│       ├── AriaClassIds.kt         (ClassId/CallableId constants)
│       ├── AriaConfigurationKeys.kt
│       ├── AriaCommandLineProcessor.kt
│       ├── AriaCompilerPluginRegistrar.kt
│       ├── AriaFirExtensionRegistrar.kt
│       ├── AriaFirCheckersExtension.kt
│       ├── AriaErrors.kt           (KtDiagnosticsContainer)
│       ├── AriaFirCheckerUtils.kt  (resolveClassById, collectConstructorProperties, findSealedParent)
│       ├── AriaMapToChecker.kt
│       ├── AriaMapFromChecker.kt
│       ├── AriaExhaustivenessChecker.kt
│       ├── AriaIrGenerationExtension.kt
│       └── MappedScopeTransformer.kt
├── aria-gradle-plugin/             (build.gradle.kts only)
└── sample/                         (build.gradle.kts only)
```

## Compiler Plugin Build Errors (Being Fixed)

### 1. PsiElement import — FIXED
`kotlin-compiler-embeddable` repackages IntelliJ classes:
```
✗ import com.intellij.psi.PsiElement
✓ import org.jetbrains.kotlin.com.intellij.psi.PsiElement
```

### 2. Context parameters — FIXED
The compiler plugin module needs `-Xcontext-parameters` because FIR checker `check()` method uses context parameters:
```kotlin
// build.gradle.kts
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
```

### 3. FirAnnotation API — NEEDS FIX
There is NO `annotationClassId` property on `FirAnnotation`. Correct APIs:
```kotlin
// Match annotation by ClassId:
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
val annotation: FirAnnotation? = declaration.getAnnotationByClassId(AriaClassIds.MAP_TO, session)

// Get ClassId from annotation:
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
val classId: ClassId? = annotation.toAnnotationClassId(session)

// Get KClass argument:
import org.jetbrains.kotlin.fir.declarations.getKClassArgument
val targetType: ConeKotlinType? = annotation.getKClassArgument(Name.identifier("target"))
```

### 4. reportOn signature — NEEDS FIX
In Kotlin 2.3.20, `reportOn` with `context(DiagnosticContext)` uses context parameters.
The checker's `check()` method already provides `context: CheckerContext` as a context parameter.
For helper methods that don't have context parameters, pass `context` explicitly:
```kotlin
// Inside check() — context parameter is available implicitly
reporter.reportOn(source, AriaErrors.SOME_ERROR, arg1)

// In standalone helper — pass context explicitly  
reporter.reportOn(source, AriaErrors.SOME_ERROR, arg1, context)
```

### 5. IR Transformer — finderForBuiltins()
`IrPluginContext.finderForBuiltins()` is the IC-compatible API for resolving symbols.
The deprecated `referenceClass()` / `referenceFunctions()` still work but should be avoided.

## Key Design Decisions Made During Implementation

1. **KMP structure**: aria-annotations and aria-runtime use `kotlin("multiplatform")` with JVM target. aria-compiler-plugin uses `kotlin("jvm")` since it only runs in the compiler.

2. **`@PublishedApi internal`**: `PresenterScope.eventFlow` is `@PublishedApi internal` because the public inline function `on<E>` accesses it.

3. **Simplified IR Transformer (v1)**: The initial `MappedScopeTransformer` passes the parent's `eventFlow` directly to the child `PresenterScope` without `@MapTo` event mapping. Full event/effect bridging with `when` expressions will be added incrementally.

4. **Sample as composite build**: Since the Aria Gradle plugin isn't published to any repository, the sample module will use Gradle composite builds (`includeBuild`) to reference the local plugin.

## Remaining Build Errors (as of latest build attempt)

### AriaErrors: abstract member not implemented
`KtDiagnosticsContainer` may have an abstract member. Check if `getRendererFactory()` needs implementing or if the class hierarchy changed in 2.3.20.

### getAnnotationByClassId: missing session parameter
The `getAnnotationByClassId` extension on `FirAnnotationContainer` needs `session` but some call sites may resolve to a different overload. Always pass `(classId, session)`.

### AriaFirCheckerUtils: SymbolInternals opt-in needed
`symbol.fir` requires `@OptIn(SymbolInternals::class)`. The `declarations` property also needs `@OptIn(DirectDeclarationsAccess::class)` — use `processAllDeclarations` instead.

### MappedScopeTransformer: deprecated API
`irBlock`, `irTemporary`, `irCall` etc. on `DeclarationIrBuilder` show "This compiler API is deprecated and will be removed soon" in Kotlin 2.3.20. Need to find the replacement builder API or suppress with `@Suppress("DEPRECATION")` temporarily.

## IR Transformer Progress

### Basic Transformation — COMPILES but CRASHES at bytecode gen

`MappedScopeTransformer.transformMappedScope()` currently generates:
```
{
  val parentScope = <extensionReceiver>
  val childEventFlow = parentScope.eventFlow  // TODO: add @MapTo mapping
  val childScope = PresenterScope<CE, CEff>(childEventFlow)
  val childResult = <lambda>.invoke(childScope)
  childResult.state
}
```

The code uses the new IR API (Kotlin 2.3.20):
- `call.typeArguments[i]` instead of `getTypeArgument(i)`
- `call.arguments[i]` instead of `getValueArgument(i)` / `extensionReceiver`
- Requires `@OptIn(UnsafeDuringIrConstructionAPI::class)` for `dispatchReceiver`-like access

### BLOCKING ISSUE: Lambda invocation

Current implementation calls `irCall(lambdaFunction.symbol)` directly, which fails at JVM bytecode generation:
```
Unhandled intrinsic in ExpressionCodegen: FUN LOCAL_FUNCTION_FOR_LAMBDA
  ($this$mappedScope:..., $composer:Composer?, $changed:Int)
  returnType:Aria<...>
```

**Root cause**: The Compose compiler plugin runs BEFORE our IR transformer and has already injected `$composer`/`$changed` parameters into the lambda. Calling the raw function symbol bypasses the JVM lambda invocation mechanism.

### Solutions to explore next session

1. **Invoke via `FunctionN.invoke()`**: Instead of `irCall(lambdaFunction.symbol)`, construct `irCall(kotlin.Function1.invoke)` with the lambda expression as the receiver. This goes through the standard lambda invocation path.

2. **Plugin ordering**: Make Aria's IR transformer run BEFORE Compose's. Options:
   - `-Xcompiler-plugin-order=com.github.kitakkun.aria,androidx.compose.compiler.plugins.kotlin`
   - Register IR extension with higher priority (check if Kotlin API supports this)
   - Structure Gradle plugin application order in `applyToCompilation`

3. **Use reference to extension function instead of inlining**: Change `mappedScope` runtime to be a regular function that the transformer calls, passing the lambda as a normal argument.

### Key files for next session

- `/Users/kitakkun/Documents/GitHub/aria/aria-compiler-plugin/src/main/kotlin/com/github/kitakkun/aria/compiler/MappedScopeTransformer.kt` — current impl
- Error reproduction: `cd sample && ../gradlew compileKotlinJvm --stacktrace`
- FIR checkers all build; only IR Transformer has this crash
