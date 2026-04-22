pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    // Composite build that provides the shared convention plugins
    // (aria.publish, ...). Module build files reference them via
    // `plugins { id("aria.publish") }`.
    includeBuild("build-logic")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}

rootProject.name = "aria"

include(":aria-annotations")
include(":aria-runtime")
include(":aria-compiler-plugin")
include(":aria-compiler-plugin-k24")
include(":aria-compiler-compat")
include(":aria-compiler-compat:k2320")
include(":aria-compiler-compat:k240_beta2")
include(":aria-gradle-plugin")
// sample is a separate composite build — see sample/settings.gradle.kts
