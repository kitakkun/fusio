pluginManagement {
    // Pull `fusio.publish` (and any future convention plugin) in from the
    // root's build-logic. Doing it here means fusio-gradle-plugin can live
    // as an included build of the root while still sharing the publish
    // convention with the library modules.
    includeBuild("../build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    // Share the version catalog with the root build so kotlin / junit /
    // testkit versions stay in lockstep. Not published — purely a
    // developer-ergonomics share.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "fusio-gradle-plugin"
