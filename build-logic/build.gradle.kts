plugins {
    `kotlin-dsl`
}

dependencies {
    // `kotlin-dsl` already puts Gradle API and the Kotlin stdlib on the
    // classpath; we only need explicit deps if a convention plugin reaches
    // into types not available that way.
    //
    // vanniktech's plugin is consumed from `fusio.publish` via
    // `plugins { id("com.vanniktech.maven.publish") }`, so we need its
    // implementation jar on the build-logic classpath.
    implementation(libs.vanniktech.maven.publish.gradle.plugin)
}
