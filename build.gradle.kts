plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // AGP plugins co-located here so KGP and AGP load on the same root
    // classpath. Without this, applying `com.android.application` from a
    // subproject after `kotlin-multiplatform` has already loaded fails to
    // resolve `KotlinAndroidTarget` because the two plugin classloaders
    // disagree on whether the (AGP-9-removed) `BaseVariant` type is reachable.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    // Hoisted to the root with `apply false` so the plugin is registered in a
    // single ClassLoaderScope. Otherwise sibling subprojects (e.g.
    // `:fusio-annotations` and `:fusio-compiler-plugin`) each load vanniktech
    // in their own scope, and Gradle rejects the shared
    // `MavenCentralBuildService` provider with
    //   "Cannot set the value of task '...:prepareMavenCentralPublishing'
    //    property 'buildService' ... loaded with ... project-fusio-compiler-plugin
    //    ... using a provider of type ... loaded with ... project-fusio-annotations".
    // The `fusio.publish` convention plugin still applies the actual
    // plugin per-module.
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    alias(libs.plugins.spotless)
}

// Apply spotless to every subproject (and the root) so a single
// `./gradlew spotlessCheck` / `spotlessApply` covers the umbrella build.
// Included builds (fusio-gradle-plugin, build-logic) apply the plugin
// independently — `allprojects` only iterates the umbrella.
val ktlintVersion = libs.versions.ktlint.get()
allprojects {
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            // testData/ holds intentionally-formatted compiler-plugin fixtures;
            // generated/ and build/ are tool output.
            targetExclude(
                "**/build/**",
                "**/generated/**",
                "**/testData/**",
            )
            ktlint(ktlintVersion)
        }
        kotlinGradle {
            target("*.gradle.kts", "**/*.gradle.kts")
            targetExclude("**/build/**")
            ktlint(ktlintVersion)
        }
    }
}

// Aggregate HTML documentation. `./gradlew :dokkaGenerate` produces a
// single cross-linked site at build/dokka/html/ covering every
// library module that applies the Dokka plugin. The per-module HTML
// that ships in each artifact's javadoc jar is unchanged.
dependencies {
    dokka(project(":fusio-annotations"))
    dokka(project(":fusio-runtime"))
    dokka(project(":fusio-test"))
}

// fusio-gradle-plugin is an included build (see settings.gradle.kts'
// pluginManagement block). Included-build tasks don't fan out through the
// root's `check` by default, so wire them in manually — otherwise
// `./gradlew build` would miss the plugin's TestKit suite and let a
// regression slip past CI.
tasks.named("check") {
    dependsOn(gradle.includedBuild("fusio-gradle-plugin").task(":check"))
}

// Same fan-out for `publishToMavenLocal`. Without this, the umbrella build
// publishes everything except `fusio-gradle-plugin`, leaving consumers'
// mavenLocal with a stale (or missing) plugin jar — which silently sends
// KGP back to its default behaviour of resolving the compiler plugin GAV
// at the consumer's Kotlin version (`com.kitakkun.fusio:fusio-compiler-plugin:2.x.y`),
// which doesn't exist on Maven Central.
//
// The root project doesn't apply maven-publish itself, so attach the
// dependency to every subproject's `publishToMavenLocal` instead. Gradle
// dedups the included-build task so it only runs once even though N
// subprojects declare the dependency.
subprojects {
    plugins.withId("maven-publish") {
        tasks.named("publishToMavenLocal") {
            dependsOn(rootProject.gradle.includedBuild("fusio-gradle-plugin").task(":publishToMavenLocal"))
        }
    }
    // Same fan-out for the Central Portal upload. The release workflow
    // invokes `./gradlew publishToMavenCentral`, which would otherwise
    // skip the included build entirely and leave Maven Central without
    // the `com.kitakkun.fusio` Gradle plugin — consumers then fail to
    // resolve `id("com.kitakkun.fusio")` from pluginManagement.
    plugins.withId("com.vanniktech.maven.publish") {
        tasks.matching { it.name == "publishToMavenCentral" }.configureEach {
            dependsOn(rootProject.gradle.includedBuild("fusio-gradle-plugin").task(":publishToMavenCentral"))
        }
    }
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
// compat jars, the per-patch test lanes, benchmarks, demo — are excluded
// since their surface is either internal tooling or auto-evolved.
apiValidation {
    ignoredProjects += listOf(
        "fusio-compiler-plugin",
        // Test lanes (per-Kotlin-patch). Names match the overrides in
        // settings.gradle.kts — the k2320 / k240_beta2 test lanes are
        // `tests-k2320` / `tests-k240_beta2` to avoid clashing with the
        // compat impl modules of the same directory name.
        "k230",
        "k2310",
        "tests-k2320",
        "k2321",
        "tests-k240_beta2",
        "fusio-compiler-compat",
        "k230",
        "k2320",
        "k240_beta2",
        "benchmarks",
        // demo's three subprojects (`:demo:shared`, `:demo:android`,
        // `:demo:desktop`) — Gradle reports them by simple project name.
        // The shared module holds the demo's presenter graph; the others
        // are platform shells. None ships a library API.
        "shared",
        "android",
        "desktop",
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
