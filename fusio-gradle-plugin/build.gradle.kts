plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    id("fusio.publish")
}

// fusio-gradle-plugin lives as a standalone included build so downstream
// consumers can apply `id("com.kitakkun.fusio")` via pluginManagement without
// publishing to mavenLocal first. That means we set group / version here
// instead of inheriting from the root's allprojects{} block.
group = "com.kitakkun.fusio"
version = providers.gradleProperty("VERSION_NAME").orNull ?: "0.1.0-SNAPSHOT"

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
    // compileOnly because the consumer always has KGP on its buildscript
    // classpath; we just need the symbols (e.g. `kotlinToolingVersion`)
    // visible at compile time without bundling them into the plugin jar.
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

// Bake our own version into a resource the runtime plugin class reads when
// it asks KGP to resolve the Fusio compiler-plugin / runtime artifacts.
// Without this KGP defaults to the Kotlin version (e.g. 2.3.20), which
// doesn't exist on the Fusio GAV.
val generateFusioVersionResource by tasks.registering {
    val versionOut = layout.buildDirectory.file(
        "generated/resources/fusio/com/kitakkun/fusio/gradle/version.properties",
    )
    val pluginVersion = project.version.toString()
    outputs.file(versionOut)
    doLast {
        val file = versionOut.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version=$pluginVersion\n")
    }
}

// Copy `fusio-compiler-compat/supported-kotlin-versions.txt` into the
// plugin jar so `FusioGradlePlugin` can read it at apply time and warn the
// consumer if their Kotlin version sits outside Fusio's tested range.
// `compiler-compat/` keeps the file as the single source of truth — adding
// support for a new Kotlin patch updates this file plus a CompatContextImpl,
// not the gradle plugin source.
//
// fusio-gradle-plugin is an included build, so `rootProject` here points at
// the plugin itself rather than the umbrella repo. Reach the sibling
// fusio-compiler-compat directory via a relative file().
val supportedKotlinVersionsSource = file("../fusio-compiler-compat/supported-kotlin-versions.txt")
val copySupportedKotlinVersions by tasks.registering(Copy::class) {
    from(supportedKotlinVersionsSource)
    into(layout.buildDirectory.dir("generated/resources/fusio/com/kitakkun/fusio/gradle"))
}

// Single shared src dir for everything baked into the plugin's resources.
// Both generators (version.properties + supported-kotlin-versions.txt)
// write into here; `builtBy` wires the task dependency so every consumer of
// the source set (processResources, sourcesJar from the publish plugin,
// etc.) picks them up without an explicit per-task dependsOn.
sourceSets.main {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/resources/fusio")).builtBy(
            generateFusioVersionResource,
            copySupportedKotlinVersions,
        ),
    )
}

gradlePlugin {
    plugins {
        create("fusio") {
            id = "com.kitakkun.fusio"
            implementationClass = "com.kitakkun.fusio.gradle.FusioGradlePlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // TestKit spins up a fresh Gradle daemon with the plugin jar on its
    // classpath; isolate it under build/ so it doesn't reuse the host's
    // ~/.gradle state and leak cross-test flakiness.
    systemProperty("test.gradleUserHome", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
}
