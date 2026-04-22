import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
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

// Dedicated non-transitive configuration wired into shadowJar: only the
// compat jars themselves go into the shaded artifact. Transitive kotlin-stdlib
// and friends would otherwise bloat the jar with ~1k irrelevant classes.
val shaded: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    // Main plugin code compiles against CompatContext / CompatContextResolver
    // from the host module. At runtime the shaded jar provides both — hence
    // compileOnly, not implementation.
    compileOnly(project(":aria-compiler-compat"))

    // These three jars get bundled into shadowJar. The k** subprojects
    // register themselves via META-INF/services and are only referenced by
    // ServiceLoader, so compileOnly isn't needed for them — only shaded.
    shaded(project(":aria-compiler-compat"))
    shaded(project(":aria-compiler-compat:k2320"))
    shaded(project(":aria-compiler-compat:k240_beta2"))

    // Production: kotlin-compiler-embeddable has IntelliJ classes shaded under
    // `org.jetbrains.kotlin.com.intellij.*`, matching what the Kotlin Gradle
    // plugin loads our plugin jar against at user-project compile time.
    compileOnly(libs.kotlin.compiler.embeddable)

    // Tests run the compiler in-process, so CompatContext types and at least
    // one k** impl need to be on the test classpath directly.
    testImplementation(project(":aria-compiler-compat"))
    testRuntimeOnly(project(":aria-compiler-compat:k2320"))

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
    // testFixtures.runtimeClasspath transitively references the project's
    // primary jar, which is now the shadowJar output. Declare the dep
    // explicitly so Gradle can schedule the tasks correctly.
    dependsOn(tasks.named("shadowJar"))

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

// --- Shading ---
// End users install a single `com.kitakkun.aria:aria-compiler-plugin` jar;
// every aria-compiler-compat subproject's classes live inside it. ServiceLoader
// at compiler runtime picks the matching k** impl via the merged
// META-INF/services entries.
//
// We replace the ordinary `jar` with the shaded one by clearing shadowJar's
// classifier and routing Maven publishing through the shaded artifact. Kotlin's
// own plugin-selection logic only cares about the single GAV, so this is
// transparent to downstream users.

tasks.named<ShadowJar>("shadowJar") {
    // Take the primary artifact slot so end users resolve the shaded jar by
    // its plain GAV, not via a classifier.
    archiveClassifier.set("")
    // Only the compat jars go in (via the dedicated `shaded` configuration);
    // stdlib and any other transitive runtime deps are already present in
    // kotlin-compiler-embeddable at the user's build time.
    configurations = listOf(shaded)
    // ServiceLoader needs every CompatContext.Factory entry from every k**
    // subproject present in the final jar; without this the last-wins default
    // would silently drop all but one version's registration.
    mergeServiceFiles()
}

// The unshaded jar keeps building but under a classifier so the shaded output
// can own the default coordinate. Not published — just kept for Gradle's
// internal task graph and anyone who wants to inspect the pre-shade classes.
tasks.named<Jar>("jar") {
    archiveClassifier.set("original")
}

// Register a publication driven by the `shadow` component (shaded jar + POM
// with external runtime deps). Eagerly — not in afterEvaluate — so the
// aria.publish convention sees a non-empty publications list and skips
// creating its own `maven` publication from the raw `java` component
// (which would otherwise collide with ours and include the test-fixtures jar).
publishing {
    publications {
        create<MavenPublication>("shadow") {
            from(components["shadow"])
            artifactId = project.name
        }
    }
}
