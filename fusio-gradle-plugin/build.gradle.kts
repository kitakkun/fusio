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

sourceSets.main {
    resources.srcDir(
        generateFusioVersionResource.map {
            layout.buildDirectory.dir("generated/resources/fusio")
        },
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
