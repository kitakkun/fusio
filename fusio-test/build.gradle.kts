plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    id("fusio.publish")
    // Same rationale as fusio-runtime: skip `org.jetbrains.compose`; we only
    // need the compose-runtime artifact via the version catalog, which keeps
    // AGP 9.0.x happy.
}

kotlin {
    explicitApi()

    jvm()

    androidLibrary {
        namespace = "com.kitakkun.fusio.test"
        compileSdk = 36
        minSdk = 24
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
                // Public: users write `@Composable { presenter(events, …) }`
                // lambdas whose return type is `Presentation<S, Eff>` from
                // fusio-runtime, so both the Compose runtime and fusio-runtime
                // must be api-visible.
                api(project(":fusio-runtime"))
                api(libs.compose.runtime.multiplatform)
                // `runTest` / `TestResult` / `TestDispatcher` are part of the
                // scenario signature — callers write `@Test fun foo() =
                // testPresenter(...)` and the return type has to be resolvable.
                api(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
