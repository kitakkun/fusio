import kotlinx.benchmark.gradle.JvmBenchmarkTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.kotlin.allopen)
}

// kotlinx-benchmark's JVM backend is JMH, and JMH requires @State-annotated
// classes to be open so it can subclass them for the generated bench drivers.
// Kotlin classes are final by default; allopen synthesizes the `open` modifier
// for every class annotated with @State (mapping it through to JMH).
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    implementation(libs.kotlinx.benchmark.runtime)
    implementation(project(":fusio-runtime"))
    implementation(libs.kotlinx.coroutines.core)
}

benchmark {
    targets {
        register("main") {
            // JVM backend — JMH-driven, measures ops/sec under steady-state.
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
    }
}
