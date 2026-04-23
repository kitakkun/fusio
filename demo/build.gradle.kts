plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    id("com.kitakkun.fusio")
}

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
                // fusio-runtime is auto-added by the com.kitakkun.fusio Gradle
                // plugin. Declaring it explicitly via `project(...)` here
                // keeps the IDE happy and lets demo compile deterministically
                // without needing a prior publishToMavenLocal.
                implementation(project(":fusio-runtime"))
                implementation(project(":fusio-annotations"))
            }
        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
