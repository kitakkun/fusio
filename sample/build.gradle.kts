plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("com.github.kitakkun.aria")
}

group = "com.github.kitakkun.aria.sample"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(compose.runtime)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                // aria-runtime is auto-added by the Gradle plugin,
                // but for composite build we reference it explicitly
                implementation("com.github.kitakkun.aria:aria-runtime:0.1.0-SNAPSHOT")
                implementation("com.github.kitakkun.aria:aria-annotations:0.1.0-SNAPSHOT")
            }
        }
    }
}
