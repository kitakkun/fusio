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

    // Non-JVM targets exist purely to prove that the Fusio compiler plugin
    // applies correctly to every KMP compilation — the Presenter / mappedScope
    // code in commonMain must compile on all of them. No platform-specific
    // Main entry; only jvmMain runs the headless smoke.
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
                implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                // fusio-runtime is auto-added by the Gradle plugin, but for
                // composite-build consumption we reference it explicitly so
                // the KMP umbrella module metadata is picked up.
                implementation("com.kitakkun.fusio:fusio-runtime:0.1.0-SNAPSHOT")
                implementation("com.kitakkun.fusio:fusio-annotations:0.1.0-SNAPSHOT")
            }
        }
    }
}
