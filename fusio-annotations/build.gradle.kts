plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("fusio.publish")
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            // No dependencies - pure Kotlin
        }
    }
}
