/**
 * Parallel test lane that re-runs every fusio-compiler-plugin testData case
 * against the Kotlin 2.4.0-Beta2 compiler + test framework, instead of the
 * primary 2.3.21 lane that `:fusio-compiler-plugin:test` covers.
 *
 * Why a separate module at all: `kotlin-compiler-internal-test-framework`
 * split `TestConfigurationBuilder` into `NonGroupingPhaseTestConfigurationBuilder`
 * + `GroupingPhaseTestConfigurationBuilder` between 2.3 and 2.4. Our
 * abstract test runners override `configure(builder: …)` on a
 * version-specific type, so one set of fixtures can't target both lanes;
 * isolating them into their own module (with its own classpath pinned to
 * 2.4 artifacts) is cleaner than trying to share a test source set.
 *
 * testData is shared verbatim with `:fusio-compiler-plugin`'s testData/
 * directory — `generateTestsK24` below writes its test-gen alongside the
 * k24-specific runners.
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
    // CompatContextResolver sees the 2.4 KotlinCompilerVersion and
    // ServiceLoader-picks the k240_beta2 impl.
    testFixturesApi(project(":fusio-compiler-plugin"))

    // Compile against the 2.4 test framework and 2.4 compiler symbols. The
    // k24 variants resolve to kotlin-compiler-internal-test-framework and
    // kotlin-compiler at 2.4.0-Beta2 via libs.versions.toml.
    testFixturesApi(libs.kotlin.compiler.k24)
    testFixturesApi(libs.kotlin.compiler.internal.test.framework.k24)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.reflect)
    // Compose compiler plugin is registered explicitly by our
    // FusioK24ExtensionRegistrarConfigurator so `@Composable` in testData
    // actually gets Compose IR treatment under the 2.4 compiler.
    testFixturesApi(libs.kotlin.compose.compiler.plugin.k24)
    testFixturesRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(testFixtures(project))
}

// Kotlin runtime jars testData sources compile against at 2.4 versions.
val testArtifacts: Configuration by configurations.creating
dependencies {
    testArtifacts("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.k24.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.k24.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.k24.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-test:${libs.versions.kotlin.k24.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-script-runtime:${libs.versions.kotlin.k24.get()}")
    testArtifacts("org.jetbrains.kotlin:kotlin-annotations-jvm:${libs.versions.kotlin.k24.get()}")
    testArtifacts(project(":fusio-annotations"))
    testArtifacts(project(":fusio-runtime"))
    testArtifacts(libs.compose.runtime)
    testArtifacts(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    // testData lives in the sibling :fusio-compiler-plugin module; resolve
    // every "compile against this jar" library by looking it up on the
    // passed testArtifacts configuration. Mirrors the primary lane's setup.
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

val generateTestsK24 by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named("testFixturesClasses"))

    inputs.dir(rootDir.resolve("fusio-compiler-plugin/testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("com.kitakkun.fusio.compiler.test.k24.GenerateTestsK24Kt")
    workingDir = rootDir
}

tasks.compileTestKotlin {
    dependsOn(generateTestsK24)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = testArtifacts.files
        .find { """$jarName-\d.*""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
}
