plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("aria.publish")
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            // No dependencies - pure Kotlin
        }
    }
}
