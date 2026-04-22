pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
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
include(":aria-gradle-plugin")
// sample is a separate composite build — see sample/settings.gradle.kts
