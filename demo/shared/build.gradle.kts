import com.kitakkun.fusio.gradle.EventHandlerExhaustiveSeverity

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    id("com.kitakkun.fusio")
}

kotlin {
    jvm()

    // KMP-aware Android library plugin (AGP 9). The accompanying `android { }`
    // block (rather than the now-deprecated `androidLibrary { }`) is the
    // current API.
    android {
        namespace = "com.kitakkun.fusio.demo.shared"
        compileSdk = 36
        // Bumped above fusio-runtime / fusio-annotations' floor (21) because
        // androidx.compose.animation:animation-core-android:1.10.5 declares
        // minSdk=23. The library modules themselves can stay at 21 — only
        // the demo Android app actually consumes Compose UI on Android.
        minSdk = 23
    }

    sourceSets {
        commonMain {
            dependencies {
                // Compose UI primitives. `compose.desktop.currentOs` lives in
                // demo:desktop because it resolves to an OS-specific Skia binding.
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                // fusio-runtime is auto-added by the com.kitakkun.fusio Gradle
                // plugin. Declaring it explicitly via `project(...)` here
                // keeps the IDE happy and lets demo compile deterministically
                // without needing a prior publishToMavenLocal.
                implementation(project(":fusio-runtime"))
                implementation(project(":fusio-annotations"))
            }
        }
        // Tests live on jvmTest only — covering presenter logic on JVM is
        // enough to prove the fusio-test harness drives real-world code
        // regardless of platform target.
        jvmTest {
            dependencies {
                implementation(project(":fusio-test"))
                implementation(libs.kotlin.test)
            }
        }
    }
}

fusio {
    eventHandlerExhaustiveSeverity = EventHandlerExhaustiveSeverity.ERROR
}
