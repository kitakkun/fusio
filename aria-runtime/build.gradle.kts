plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":aria-annotations"))
                implementation(compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
