plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    id("fusio.publish")
}

kotlin {
    explicitApi()

    jvm()

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
