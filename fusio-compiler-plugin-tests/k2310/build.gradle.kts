/**
 * Kotlin 2.3.10 test lane.
 *
 * KNOWN-FAILING: same compat gap as the k230 lane — plugin references
 * `DeclarationFinder` / `finderForBuiltins()`, added in 2.3.20. The lane
 * stays registered (opted out of `check`) to make the gap visible.
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
    testFixturesApi(project(":fusio-compiler-plugin"))

    testFixturesApi(libs.kotlin.compiler.k2310)
    testFixturesApi(libs.kotlin.compiler.internal.test.framework.k2310)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.reflect)
    testFixturesApi(libs.kotlin.compose.compiler.plugin.k2310)
    testFixturesRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(testFixtures(project))
}

val testArtifacts: Configuration by configurations.creating
dependencies {
    testArtifacts("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.k2310.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.k2310.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.k2310.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-test:${libs.versions.kotlin.k2310.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-script-runtime:${libs.versions.kotlin.k2310.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-annotations-jvm:${libs.versions.kotlin.k2310.get()}")
    testArtifacts(project(":fusio-annotations"))
    testArtifacts(project(":fusio-runtime"))
    testArtifacts(libs.compose.runtime)
    testArtifacts(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(testArtifacts)
    workingDir = rootDir
    // Known-failing lane — see module KDoc.
    ignoreFailures = true

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
