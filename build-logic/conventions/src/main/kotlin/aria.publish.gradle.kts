/**
 * Shared publishing config.
 *
 * Each module that needs to be published applies this convention via
 * `plugins { id("aria.publish") }`. Responsibilities here:
 *
 * - Apply the `maven-publish` plugin.
 * - Pin group and version so every artifact matches (allprojects in root
 *   already sets these, but we mirror here so the convention is
 *   self-contained — future switch to Central publishing can change one
 *   file).
 * - For Kotlin Multiplatform targets, the multiplatform plugin creates
 *   one publication per target automatically, so we don't need to add
 *   publications by hand. For JVM-only projects (compiler plugin), we
 *   register the default Java component.
 * - Default target repo is mavenLocal so `:publishToMavenLocal` seeds
 *   the composite-build sample and any developer's local cache.
 *   A maven-central stanza can be slotted in when we start releasing.
 */
plugins {
    `maven-publish`
}

group = "com.kitakkun.aria"
version = "0.1.0-SNAPSHOT"

publishing {
    repositories {
        mavenLocal()
    }

    // Register a publication for Gradle's standard `java` / `javaPlatform`
    // components when this isn't a Kotlin multiplatform project. The KMP
    // plugin auto-registers target-specific publications that are
    // sufficient on their own.
    afterEvaluate {
        val isKmp = plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
        if (!isKmp && publications.isEmpty()) {
            val javaComponent = components.findByName("java")
            if (javaComponent != null) {
                publications.create<MavenPublication>("maven") {
                    from(javaComponent)
                }
            }
        }
    }
}
