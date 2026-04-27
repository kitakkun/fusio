plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    id("fusio.publish")
    // Deliberately NOT applying `org.jetbrains.compose`. Its compose-resources
    // feature reaches into AGP's KotlinMultiplatformAndroidComponentsExtension
    // from the compose-multiplatform-gradle-plugin classloader and blows up
    // on AGP 9.0.x. We don't use compose-resources or the compose.runtime DSL
    // accessor — the runtime pulls compose artifacts via
    // libs.compose.runtime.multiplatform directly — so dropping the plugin
    // is equivalent apart from losing that incompatible hook.
}

// Emit Compose stability / skippability reports for every JVM compilation
// of fusio-runtime. The resulting `<module>-composables.txt` feeds the
// `verifyComposeMetrics` task below, which asserts the runtime's public
// @Composable surface hasn't silently lost its restartable/skippable
// status. The Compose compiler emits the same report regardless of
// target, so the JVM output is authoritative.
val composeMetricsDir = layout.buildDirectory.dir("compose-metrics")
composeCompiler {
    reportsDestination.set(composeMetricsDir)
    metricsDestination.set(composeMetricsDir)
}

// Expected metrics for every public @Composable in fusio-runtime. Keys are
// the fully-qualified function name; values are the line prefix the
// Compose compiler emits in `fusio-runtime-composables.txt`. Update this
// map intentionally when a Composable's shape changes — the verify task
// diffs against it so accidental stability / skippability regressions
// block CI instead of silently slipping into a release.
val expectedComposableMetrics = mapOf(
    "com.kitakkun.fusio.buildPresenter" to """scheme("[0, [0]]")""",
    "com.kitakkun.fusio.forwardEffects" to "restartable skippable",
    "com.kitakkun.fusio.forwardEventErrors" to "restartable skippable",
    "com.kitakkun.fusio.on" to "inline",
)

val verifyComposeMetrics by tasks.registering {
    group = "verification"
    description = "Asserts fusio-runtime's @Composable functions keep their expected Compose-compiler status."

    val composablesReport = composeMetricsDir.map { it.file("fusio-runtime-composables.txt") }
    dependsOn(tasks.named("compileKotlinJvm"))
    inputs.file(composablesReport).withPropertyName("composablesReport")
    outputs.upToDateWhen { true }

    val expected = expectedComposableMetrics
    doLast {
        val report = composablesReport.get().asFile
        require(report.exists()) { "Compose composables report missing: $report" }
        val lines = report.readLines()

        val failures = mutableListOf<String>()
        expected.forEach { (fqName, expectedPrefix) ->
            // Each Composable entry in the report is a multi-line block; the
            // first line contains the prefix (e.g. "restartable skippable")
            // and `fun <fqName>(`. Match on that header.
            val actual = lines.firstOrNull { "fun $fqName(" in it }
            when {
                actual == null ->
                    failures += "$fqName: no entry in $report"
                !actual.trimStart().startsWith(expectedPrefix) ->
                    failures += "$fqName: expected prefix '$expectedPrefix' but report line was: $actual"
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Compose metrics regression(s):")
                    failures.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("If this change is intentional, update expectedComposableMetrics in fusio-runtime/build.gradle.kts.")
                },
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyComposeMetrics) }

kotlin {
    explicitApi()

    jvm()

    // See fusio-annotations for the Android target rationale. Runtime
    // consumers on Android get the same `androidx.compose.runtime:runtime`
    // jar as JVM, just compiled against the Android bootclasspath.
    android {
        namespace = "com.kitakkun.fusio.runtime"
        compileSdk = 36
        // Matches fusio-annotations' floor (Compose Multiplatform-supported 21).
        minSdk = 21
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    js(IR) {
        browser()
        nodejs()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":fusio-annotations"))
                implementation(libs.compose.runtime.multiplatform)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
