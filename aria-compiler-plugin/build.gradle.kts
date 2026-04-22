plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
    idea
    id("aria.publish")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
}

sourceSets {
    main {
        // Shared production sources live under src/main/kotlin; the k23-specific
        // compat shims for APIs that shift between Kotlin minors live under
        // src/main-k23/kotlin. aria-compiler-plugin-k24 mirrors this with its
        // own src/main/kotlin/compat/ directory.
        kotlin.srcDir("src/main-k23/kotlin")
    }
    test {
        java.setSrcDirs(listOf("src/test/kotlin", "test-gen"))
        resources.setSrcDirs(listOf("testData"))
    }
    testFixtures {
        java.setSrcDirs(listOf("test-fixtures"))
    }
}

// Tell IntelliJ that test-gen is a generated test source root so it renders
// with the generated-source colour and doesn't offer to refactor its contents.
idea {
    module {
        generatedSourceDirs.add(layout.projectDirectory.dir("test-gen").asFile)
        testSources.from(layout.projectDirectory.dir("test-gen"))
    }
}

dependencies {
    // Production: kotlin-compiler-embeddable has IntelliJ classes shaded under
    // `org.jetbrains.kotlin.com.intellij.*`, matching what the Kotlin Gradle
    // plugin loads our plugin jar against at user-project compile time.
    compileOnly(libs.kotlin.compiler.embeddable)

    // Tests: the internal test framework is compiled against non-embeddable
    // kotlin-compiler (i.e. references unshaded `com.intellij.*` classes), so
    // that's what goes on the test classpath. Our plugin bytecode was kept free
    // of direct IntelliJ references (we use `org.jetbrains.kotlin.psi.KtElement`
    // in AriaErrors), so it loads happily against either variant.
    testFixturesApi(libs.kotlin.compiler)
    testFixturesApi(libs.kotlin.compiler.internal.test.framework)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.reflect)
    // Compose compiler plugin — registered explicitly in AriaExtensionRegistrarConfigurator
    // so @Composable usage in testData (mappedScope round-trip) actually gets Compose
    // IR treatment.
    testFixturesApi(libs.kotlin.compose.compiler.plugin)
    testFixturesRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(testFixtures(project))
}

// Kotlin runtime jars that tests compile user snippets against.
val testArtifacts: Configuration by configurations.creating
dependencies {
    testArtifacts(libs.kotlin.stdlib)
    testArtifacts(libs.kotlin.stdlib.jdk8)
    testArtifacts(libs.kotlin.reflect)
    testArtifacts(libs.kotlin.test)
    testArtifacts(libs.kotlin.script.runtime)
    testArtifacts(libs.kotlin.annotations.jvm)
    testArtifacts(project(":aria-annotations"))
    testArtifacts(project(":aria-runtime"))
    testArtifacts(libs.compose.runtime)
    testArtifacts(libs.kotlinx.coroutines.core)
}

tasks.test {
    dependsOn(testArtifacts)
    useJUnitPlatform()
    workingDir = rootDir

    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    // Surface Aria's own jars to testData sources that import com.kitakkun.aria.*.
    systemProperty("ariaRuntime.classpath", testArtifacts.asPath)

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)

    // Forward -Pkotlin.test.update.test.data=true to the test JVM so the
    // framework auto-writes expected testData / .fir.txt files on mismatch.
    project.findProperty("kotlin.test.update.test.data")?.let {
        systemProperty("kotlin.test.update.test.data", it.toString())
    }
}

val generateTests by tasks.registering(JavaExec::class) {
    inputs.dir(layout.projectDirectory.dir("testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("com.kitakkun.aria.compiler.test.GenerateTestsKt")
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
