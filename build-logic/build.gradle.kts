plugins {
    `kotlin-dsl`
}

dependencies {
    // `kotlin-dsl` already puts Gradle API and the Kotlin stdlib on the
    // classpath; we only need explicit deps if a convention plugin reaches
    // into types not available that way.
}
