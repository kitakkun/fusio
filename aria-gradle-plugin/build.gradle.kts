plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
}

gradlePlugin {
    plugins {
        create("aria") {
            id = "com.github.kitakkun.aria"
            implementationClass = "com.github.kitakkun.aria.gradle.AriaGradlePlugin"
        }
    }
}
