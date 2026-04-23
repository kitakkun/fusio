plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    id("fusio.publish")
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)

    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.jupiter.engine)
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
