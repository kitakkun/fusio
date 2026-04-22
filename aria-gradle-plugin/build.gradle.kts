plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    id("aria.publish")
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
}

gradlePlugin {
    plugins {
        create("aria") {
            id = "com.kitakkun.aria"
            implementationClass = "com.kitakkun.aria.gradle.AriaGradlePlugin"
        }
    }
}
