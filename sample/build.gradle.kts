plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("com.kitakkun.fusio")
}

group = "com.kitakkun.fusio.sample"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("com.kitakkun.fusio.sample.MainKt")
            }
        }
    }

    sourceSets {
        jvmMain {
            dependencies {
                implementation(compose.runtime)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                // fusio-runtime is auto-added by the Gradle plugin,
                // but for composite build we reference it explicitly
                implementation("com.kitakkun.fusio:fusio-runtime:0.1.0-SNAPSHOT")
                implementation("com.kitakkun.fusio:fusio-annotations:0.1.0-SNAPSHOT")
            }
        }
    }
}
