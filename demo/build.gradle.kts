plugins {
    kotlin("multiplatform") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("com.kitakkun.fusio")
}

group = "com.kitakkun.fusio.demo"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("com.kitakkun.fusio.demo.MainKt")
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // Compose UI primitives. `compose.desktop.currentOs` lives in
                // jvmMain because it resolves to an OS-specific Skia binding.
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                // fusio-runtime and fusio-annotations are auto-added by the
                // com.kitakkun.fusio Gradle plugin, but the composite build
                // needs the explicit GAV so IDE indexing sees the KMP
                // umbrella and picks the common variant in commonMain.
                implementation("com.kitakkun.fusio:fusio-runtime:0.1.0-SNAPSHOT")
                implementation("com.kitakkun.fusio:fusio-annotations:0.1.0-SNAPSHOT")
            }
        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
