/**
 * Shared publishing config.
 *
 * Each module that needs to be published applies this convention via
 * `plugins { id("fusio.publish") }`. Responsibilities here:
 *
 * - Apply `maven-publish` + `signing`.
 * - Pin group and version so every artifact matches (allprojects in root
 *   already sets these, but we mirror here so the convention is
 *   self-contained).
 * - Populate POM metadata Sonatype requires for Maven Central validation
 *   (name, description, url, licenses, developers, scm).
 * - Ensure every publication carries a javadoc jar. Sonatype rejects
 *   publications missing one. KMP publications get a shared empty
 *   javadoc jar since dokka isn't wired up yet; consumers who need API
 *   docs can read the sources.jar the KMP plugin already ships.
 * - Target repos:
 *     - `mavenLocal` always — seeds `:publishToMavenLocal` for the
 *       composite-build sample and any developer's local cache.
 *     - `sonatype` only when credentials are present (gradle properties
 *       `sonatypeUsername` / `sonatypePassword` or env
 *       `SONATYPE_USERNAME` / `SONATYPE_PASSWORD`). Absent creds means
 *       the task is registered but fails fast if invoked — no surprises
 *       during local builds.
 * - Signing activates only when `signingKey` (ASCII-armored) +
 *   `signingPassword` are provided via gradle props or `SIGNING_KEY` /
 *   `SIGNING_PASSWORD` env vars. Release publishing to Central must go
 *   through a signed pipeline; local `publishToMavenLocal` skips it.
 */
plugins {
    `maven-publish`
    signing
}

group = "com.kitakkun.fusio"
version = "0.1.0-SNAPSHOT"

val sonatypeUsername: String? = providers.gradleProperty("sonatypeUsername").orNull
    ?: System.getenv("SONATYPE_USERNAME")
val sonatypePassword: String? = providers.gradleProperty("sonatypePassword").orNull
    ?: System.getenv("SONATYPE_PASSWORD")

val signingKey: String? = providers.gradleProperty("signingKey").orNull
    ?: System.getenv("SIGNING_KEY")
val signingPassword: String? = providers.gradleProperty("signingPassword").orNull
    ?: System.getenv("SIGNING_PASSWORD")

// A single empty javadoc jar shared across every publication in this project.
// Dokka integration can replace this later without changing the publication
// wiring — the task name stays stable.
val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    // No from(...) — Sonatype only checks for presence, not contents.
}

publishing {
    repositories {
        mavenLocal()

        maven {
            name = "sonatype"
            val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                // Nullable on purpose — missing creds make the publish task
                // fail at execution time rather than configuration time, so
                // local dev builds don't trip on them.
                username = sonatypeUsername
                password = sonatypePassword
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)

        pom {
            name.set("${project.name} (${this@configureEach.name})")
            description.set(
                "Fusio: Kotlin compiler plugin and runtime for decomposing fat Composable presenters " +
                    "into reusable sub-presenters with compile-time event/effect bridging.",
            )
            url.set("https://github.com/kitakkun/fusio")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("kitakkun")
                    name.set("kitakkun")
                    url.set("https://github.com/kitakkun")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/kitakkun/fusio.git")
                developerConnection.set("scm:git:ssh://github.com:kitakkun/fusio.git")
                url.set("https://github.com/kitakkun/fusio")
            }
        }
    }

    // For JVM-only modules the KMP plugin isn't present, so we still need to
    // register a default `maven` publication from the `java` component.
    // Handled in afterEvaluate because `components["java"]` only exists once
    // the Java plugin has been applied by the consuming build script.
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

// Signing — only active when a key is provided. useInMemoryPgpKeys keeps us
// independent of the user's local keyring, which matters for CI. The
// signing plugin is always applied so `signArchives` etc. exist, but it
// no-ops when no key is set.
if (signingKey != null && signingPassword != null) {
    signing {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}

// KMP publication side-quest: target-specific publications run before
// signing tasks by default, which Gradle flags as implicit task ordering.
// Pin the order so every sign-<target>-publication runs before every
// publish-<target>-publication and the KMP variants don't race.
tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)
}
