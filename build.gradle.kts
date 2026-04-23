plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
}

// Aggregate HTML documentation. `./gradlew :dokkaGenerate` produces a
// single cross-linked site at build/dokka/html/ covering every
// library module that applies the Dokka plugin. The per-module HTML
// that ships in each artifact's javadoc jar is unchanged.
dependencies {
    dokka(project(":fusio-annotations"))
    dokka(project(":fusio-runtime"))
}

// fusio-gradle-plugin is an included build (see settings.gradle.kts'
// pluginManagement block). Included-build tasks don't fan out through the
// root's `check` by default, so wire them in manually — otherwise
// `./gradlew build` would miss the plugin's TestKit suite and let a
// regression slip past CI.
tasks.named("check") {
    dependsOn(gradle.includedBuild("fusio-gradle-plugin").task(":check"))
}

allprojects {
    group = "com.kitakkun.fusio"
    // Release workflow passes `-PVERSION_NAME=1.2.3` to produce non-SNAPSHOT
    // artifacts. Default stays SNAPSHOT for day-to-day publishToMavenLocal.
    version = providers.gradleProperty("VERSION_NAME").orNull ?: "0.1.0-SNAPSHOT"

    // In-repo GAV → project substitution so demo (and any future internal
    // consumer) resolves `com.kitakkun.fusio:fusio-compiler-plugin` and
    // `fusio-runtime` to the local subprojects without needing an intervening
    // publishToMavenLocal. The FusioGradlePlugin adds those coordinates via
    // SubpluginArtifact / commonMainImplementation by GAV because external
    // users go through Maven Central; the substitution below is what lets
    // the same plugin class also work inside this build tree.
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("com.kitakkun.fusio:fusio-compiler-plugin"))
                .using(project(":fusio-compiler-plugin"))
            substitute(module("com.kitakkun.fusio:fusio-runtime"))
                .using(project(":fusio-runtime"))
        }
    }
}

// Publishable library surface gets its public ABI dumped into `<module>/api/`
// via `./gradlew apiDump`. Regenerate intentionally; the `:apiCheck` lane
// (already wired into `check`) fails if a PR changes the public API without
// refreshing the dump. Non-publishable modules — the compiler plugin, its
// compat jars, the gradle plugin, the sample, benchmarks — are excluded
// since their surface is either internal tooling or auto-evolved.
apiValidation {
    ignoredProjects += listOf(
        "fusio-compiler-plugin",
        "fusio-compiler-plugin-k24-tests",
        "fusio-compiler-compat",
        "k2320",
        "k240_beta2",
        "benchmarks",
        "demo",
    )

    // Dump klib ABI for the non-JVM targets too. Without this, only the
    // .jar ABI is tracked and an accidental signature change in iOS /
    // Wasm / JS surfaces wouldn't break :apiCheck. Experimental API
    // but this is the point of pulling in BCV for a KMP library.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
