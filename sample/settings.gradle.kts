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
        // Restrict mavenLocal to our own artifacts — see root settings for
        // the rationale (missing .module files for external KMP libs break
        // variant-aware resolution).
        mavenLocal {
            content {
                includeGroup("com.kitakkun.fusio")
            }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "fusio-sample"

// Use the parent project's Gradle plugin and compiler plugin via composite build
includeBuild("../")
