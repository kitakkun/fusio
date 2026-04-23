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
        // Same reasoning as the root settings: mavenLocal is content-filtered
        // to our own group so external KMP libs resolve via mavenCentral
        // with their proper `.module` metadata, not a stripped POM.
        mavenLocal {
            content {
                includeGroup("com.kitakkun.fusio")
            }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "fusio-demo"

// Pull the Fusio compiler + Gradle plugin from the parent build. This is the
// same wiring a downstream user would get by applying
// `id("com.kitakkun.fusio") version "x.y.z"` once Fusio is on Maven Central —
// composite build here just bypasses the publish step during development.
includeBuild("../")
