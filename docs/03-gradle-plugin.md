# Step 3: aria-gradle-plugin

## Module: `aria-gradle-plugin`

Gradle plugin that registers the Aria Compiler Plugin and automatically adds runtime dependencies.

## Components

### AriaGradlePlugin

Implements `KotlinCompilerPluginSupportPlugin` to integrate with the Kotlin Gradle Plugin.

```kotlin
package com.github.kitakkun.aria.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class AriaGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        // No extension needed for now
    }

    override fun getCompilerPluginId(): String = "com.github.kitakkun.aria"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            groupId = "com.github.kitakkun.aria",
            artifactId = "aria-compiler-plugin",
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
            "com.github.kitakkun.aria:aria-runtime:$VERSION",
        )
        // aria-annotations is transitively included via aria-runtime's api dependency

        return project.provider { emptyList() }
    }
}
```

### AriaCommandLineProcessor

Receives options from Gradle and passes them to the compiler plugin via `CompilerConfiguration`.

```kotlin
package com.github.kitakkun.aria.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class AriaCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "com.github.kitakkun.aria"

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption("enabled", "<true|false>", "Enable Aria compiler plugin", required = false),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            "enabled" -> configuration.put(AriaConfigurationKeys.ENABLED, value.toBoolean())
        }
    }
}
```

### AriaCompilerPluginRegistrar

Entry point for the compiler plugin. Registers both FIR and IR extensions.

```kotlin
package com.github.kitakkun.aria.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
class AriaCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.github.kitakkun.aria"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(AriaConfigurationKeys.ENABLED, true).not()) return

        // FIR extensions (analysis + checking)
        FirExtensionRegistrarAdapter.registerExtension(AriaFirExtensionRegistrar())

        // IR extensions (code generation)
        IrGenerationExtension.registerExtension(AriaIrGenerationExtension())
    }
}
```

## Service Registration

### META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
```
com.github.kitakkun.aria.compiler.AriaCompilerPluginRegistrar
```

### META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
```
com.github.kitakkun.aria.compiler.AriaCommandLineProcessor
```

### META-INF/gradle-plugins/com.github.kitakkun.aria.properties
```
implementation-class=com.github.kitakkun.aria.gradle.AriaGradlePlugin
```

## Build Configuration

```kotlin
// aria-gradle-plugin/build.gradle.kts
plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))
}

gradlePlugin {
    plugins {
        create("aria") {
            id = "com.github.kitakkun.aria"
            implementationClass = "com.github.kitakkun.aria.gradle.AriaGradlePlugin"
        }
    }
}
```

## Compose Plugin Ordering

Aria's IR transformer should run **before** the Compose compiler plugin to operate on raw `@Composable` lambdas. Plugin execution order follows `CompilerPluginRegistrar` registration order, which is controlled by Gradle's `-Xplugin` argument order.

The Aria Gradle plugin should be applied **before** the Compose plugin in the user's `build.gradle.kts`:

```kotlin
plugins {
    id("com.github.kitakkun.aria")  // Aria first
    id("org.jetbrains.compose")      // Compose second
}
```

Alternatively, investigate using `-Xcompiler-plugin-order` (available in newer Kotlin versions) for explicit ordering control.
