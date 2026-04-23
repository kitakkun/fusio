# Step 3: fusio-gradle-plugin

## Module: `fusio-gradle-plugin`

Gradle plugin that registers the Fusio Compiler Plugin and automatically adds runtime dependencies.

## Components

### FusioGradlePlugin

Implements `KotlinCompilerPluginSupportPlugin` to integrate with the Kotlin Gradle Plugin.

```kotlin
package com.kitakkun.fusio.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class FusioGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        // No extension needed for now
    }

    override fun getCompilerPluginId(): String = "com.kitakkun.fusio"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            groupId = "com.kitakkun.fusio",
            artifactId = "fusio-compiler-plugin",
            version = VERSION,
        )

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        // Auto-add runtime dependencies
        project.dependencies.add(
            kotlinCompilation.defaultSourceSet.implementationConfigurationName,
            "com.kitakkun.fusio:fusio-runtime:$VERSION",
        )
        // fusio-annotations is transitively included via fusio-runtime's api dependency

        return project.provider { emptyList() }
    }
}
```

### FusioCommandLineProcessor

Receives options from Gradle and passes them to the compiler plugin via `CompilerConfiguration`.

```kotlin
package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class FusioCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "com.kitakkun.fusio"

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption("enabled", "<true|false>", "Enable Fusio compiler plugin", required = false),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            "enabled" -> configuration.put(FusioConfigurationKeys.ENABLED, value.toBoolean())
        }
    }
}
```

### FusioCompilerPluginRegistrar

Entry point for the compiler plugin. Registers both FIR and IR extensions.

```kotlin
package com.kitakkun.fusio.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
class FusioCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.kitakkun.fusio"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(FusioConfigurationKeys.ENABLED, true).not()) return

        // FIR extensions (analysis + checking)
        FirExtensionRegistrarAdapter.registerExtension(FusioFirExtensionRegistrar())

        // IR extensions (code generation)
        IrGenerationExtension.registerExtension(FusioIrGenerationExtension())
    }
}
```

## Service Registration

### META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
```
com.kitakkun.fusio.compiler.FusioCompilerPluginRegistrar
```

### META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
```
com.kitakkun.fusio.compiler.FusioCommandLineProcessor
```

### META-INF/gradle-plugins/com.kitakkun.fusio.properties
```
implementation-class=com.kitakkun.fusio.gradle.FusioGradlePlugin
```

## Build Configuration

```kotlin
// fusio-gradle-plugin/build.gradle.kts
plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))
}

gradlePlugin {
    plugins {
        create("fusio") {
            id = "com.kitakkun.fusio"
            implementationClass = "com.kitakkun.fusio.gradle.FusioGradlePlugin"
        }
    }
}
```

## Compose Plugin Ordering

Fusio's IR transformer should run **before** the Compose compiler plugin to operate on raw `@Composable` lambdas. Plugin execution order follows `CompilerPluginRegistrar` registration order, which is controlled by Gradle's `-Xplugin` argument order.

The Fusio Gradle plugin should be applied **before** the Compose plugin in the user's `build.gradle.kts`:

```kotlin
plugins {
    id("com.kitakkun.fusio")  // Fusio first
    id("org.jetbrains.compose")      // Compose second
}
```

Alternatively, investigate using `-Xcompiler-plugin-order` (available in newer Kotlin versions) for explicit ordering control.
