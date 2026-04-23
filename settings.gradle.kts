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
    // fusio-gradle-plugin is an included build so downstream consumers
    // (demo/) can apply `id("com.kitakkun.fusio")` straight from
    // pluginManagement without publishing to mavenLocal first. The
    // plugin module stays on disk in the same repo but is evaluated
    // as its own Gradle build rather than as a subproject.
    includeBuild("fusio-gradle-plugin")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        // Constrain mavenLocal to our own artifacts only. Maven publications
        // of external KMP libraries (kotlinx-coroutines-core, etc.) that land
        // in ~/.m2 via other projects lack the Gradle Module Metadata (.module
        // file), so serving them from mavenLocal makes Gradle fall back to
        // POM resolution and pick the JVM-only artifact even for common /
        // native compilations. Restricting mavenLocal to com.kitakkun.fusio
        // keeps `:publishToMavenLocal` → sample composite-build working while
        // letting all third-party deps come from mavenCentral with their
        // proper KMP variants intact.
        mavenLocal {
            content {
                includeGroup("com.kitakkun.fusio")
            }
        }
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
include(":benchmarks")
include(":demo")
// fusio-gradle-plugin is registered as a pluginManagement includeBuild above.
