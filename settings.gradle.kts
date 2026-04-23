pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    // Composite build that provides the shared convention plugins
    // (fusio.publish, ...). Module build files reference them via
    // `plugins { id("fusio.publish") }`.
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

rootProject.name = "fusio"

include(":fusio-annotations")
include(":fusio-runtime")
include(":fusio-compiler-plugin")
include(":fusio-compiler-compat")
include(":fusio-compiler-compat:k2320")
include(":fusio-compiler-compat:k240_beta2")
include(":fusio-gradle-plugin")
// sample is a separate composite build — see sample/settings.gradle.kts
