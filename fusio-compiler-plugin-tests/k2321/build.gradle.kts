/**
 * Primary test lane — exercises every testData case against the Kotlin 2.3.21
 * compiler + test framework, which is the default `kotlin` version in
 * libs.versions.toml.
 *
 * Structure mirrors the sibling `:fusio-compiler-plugin-tests:k240_beta2`
 * module so adding a new supported Kotlin version is a copy-paste-and-tweak
 * exercise: bump the catalog version alias, adjust the runner classes, done.
 *
 * Shared testData (box/diagnostics) lives one directory up at
 * `fusio-compiler-plugin-tests/testData/`; version-specific IR goldens live
 * beside this build file at `testData/ir/`.
 */
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
    idea
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

sourceSets {
    test {
        java.setSrcDirs(listOf("src/test/kotlin", "test-gen"))
    }
}

idea {
    module {
        generatedSourceDirs.add(layout.projectDirectory.dir("test-gen").asFile)
        testSources.from(layout.projectDirectory.dir("test-gen"))
    }
}

dependencies {
    // Pull the shaded fusio-compiler-plugin jar — at runtime the in-jar
    // CompatContextResolver sees the primary KotlinCompilerVersion and
    // ServiceLoader-picks the k2320 impl (which covers the whole 2.3.x range).
    testFixturesApi(project(":fusio-compiler-plugin"))

    testFixturesApi(libs.kotlin.compiler)
    testFixturesApi(libs.kotlin.compiler.internal.test.framework)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.reflect)
    // Compose compiler plugin is registered explicitly by our
    // FusioExtensionRegistrarConfigurator so `@Composable` in testData
    // actually gets Compose IR treatment.
    testFixturesApi(libs.kotlin.compose.compiler.plugin)
    testFixturesRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(testFixtures(project))
}

// Kotlin runtime jars testData sources compile against, pinned to the primary
// Kotlin version.
val testArtifacts: Configuration by configurations.creating
dependencies {
    testArtifacts(libs.kotlin.stdlib)
    testArtifacts(libs.kotlin.stdlib.jdk8)
    testArtifacts(libs.kotlin.reflect)
    testArtifacts(libs.kotlin.test)
    testArtifacts(libs.kotlin.script.runtime)
    testArtifacts(libs.kotlin.annotations.jvm)
    testArtifacts(project(":fusio-annotations"))
    testArtifacts(project(":fusio-runtime"))
    testArtifacts(libs.compose.runtime)
    testArtifacts(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(testArtifacts)
    workingDir = rootDir

    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    systemProperty("fusioRuntime.classpath", testArtifacts.asPath)
    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)

    project.findProperty("kotlin.test.update.test.data")?.let {
        systemProperty("kotlin.test.update.test.data", it.toString())
    }
}

val generateTests by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named("testFixturesClasses"))

    inputs.dir(rootDir.resolve("fusio-compiler-plugin-tests/testData"))
        .withPropertyName("sharedTestData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("testData"))
        .withPropertyName("laneTestData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("com.kitakkun.fusio.compiler.test.GenerateTestsKt")
    workingDir = rootDir
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = testArtifacts.files
        .find { """$jarName-\d.*""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
}
