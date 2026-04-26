plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    id("fusio.publish")
}

kotlin {
    explicitApi()

    jvm()

    // `com.android.kotlin.multiplatform.library` (AGP 8.10+) replaces the
    // legacy `com.android.library` + top-level `android { }` extension with
    // a target block nested inside `kotlin { }`. Single-variant, no
    // flavors, no Android resources — a pure KMP annotation / library
    // artifact, nothing else.
    androidLibrary {
        namespace = "com.kitakkun.fusio"
        compileSdk = 36
        // 21 is the Compose Multiplatform floor; Fusio's annotations are
        // pure Kotlin and don't reach for any Android API that needs more.
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
            // No dependencies - pure Kotlin
        }
    }
}
