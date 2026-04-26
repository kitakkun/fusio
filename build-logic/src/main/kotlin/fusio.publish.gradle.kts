/**
 * Shared publishing config. Modules apply via `plugins { id("fusio.publish") }`.
 *
 * - Uploads to Maven Central via Sonatype's Central Portal (vanniktech plugin).
 * - Pins POM metadata (name, description, URL, licence, developer, SCM)
 *   that Maven Central requires for validation.
 * - Auto-publishes sources + javadoc jars — vanniktech includes Dokka HTML
 *   if the module applies the dokka plugin, and an empty stub otherwise.
 * - Signs all publications when both `SIGNING_KEY` (ASCII-armored) and
 *   `SIGNING_PASSWORD` are provided via gradle props or env. Local
 *   `publishToMavenLocal` skips signing.
 *
 * Credential lookup: the vanniktech plugin reads
 * `mavenCentralUsername` / `mavenCentralPassword`, surfaced by the release
 * workflow as the standard `ORG_GRADLE_PROJECT_…` env vars.
 */
plugins {
    id("com.vanniktech.maven.publish")
}

group = "com.kitakkun.fusio"
// `version` is pinned by the root build's allprojects{} block, which honors
// -PVERSION_NAME from the release workflow. Don't re-assign here — that
// would clobber the property for modules applying this convention.

val signingKey: String? = providers.gradleProperty("signingKey").orNull
    ?: System.getenv("SIGNING_KEY")
val signingPassword: String? = providers.gradleProperty("signingPassword").orNull
    ?: System.getenv("SIGNING_PASSWORD")

mavenPublishing {
    // Uploads but does *not* auto-release — the deployment sits in
    // Central Portal's "Validated" state until manually published from
    // the web UI. Switch to `publishToMavenCentral(automaticRelease = true)`
    // if the manual gate becomes friction.
    publishToMavenCentral()

    if (signingKey != null && signingPassword != null) {
        signAllPublications()
    }

    pom {
        name.set(project.name)
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

// Route SIGNING_KEY / SIGNING_PASSWORD env vars through useInMemoryPgpKeys
// so signing works on CI without a local GPG keyring.
if (signingKey != null && signingPassword != null) {
    extensions.configure<SigningExtension>("signing") {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
}
