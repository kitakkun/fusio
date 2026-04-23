plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    id("fusio.publish")
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
}

gradlePlugin {
    plugins {
        create("fusio") {
            id = "com.kitakkun.fusio"
            implementationClass = "com.kitakkun.fusio.gradle.FusioGradlePlugin"
        }
    }
}
