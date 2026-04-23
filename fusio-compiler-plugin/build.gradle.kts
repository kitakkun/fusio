import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    `java-test-fixtures`
    idea
    id("fusio.publish")
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
    compileOnly(project(":fusio-compiler-compat"))

    // These three jars get bundled into shadowJar. The k** subprojects
    // register themselves via META-INF/services and are only referenced by
    // ServiceLoader, so compileOnly isn't needed for them — only shaded.
    shaded(project(":fusio-compiler-compat"))
    shaded(project(":fusio-compiler-compat:k2320"))
    shaded(project(":fusio-compiler-compat:k240_beta2"))

    // Production: kotlin-compiler-embeddable has IntelliJ classes shaded under
    // `org.jetbrains.kotlin.com.intellij.*`, matching what the Kotlin Gradle
    // plugin loads our plugin jar against at user-project compile time.
    compileOnly(libs.kotlin.compiler.embeddable)

    // Tests run the compiler in-process, so CompatContext types and at least
    // one k** impl need to be on the test classpath directly.
    testImplementation(project(":fusio-compiler-compat"))
    testRuntimeOnly(project(":fusio-compiler-compat:k2320"))

    // Tests: the internal test framework is compiled against non-embeddable
    // kotlin-compiler (i.e. references unshaded `com.intellij.*` classes), so
    // that's what goes on the test classpath. Our plugin bytecode was kept free
    // of direct IntelliJ references (we use `org.jetbrains.kotlin.psi.KtElement`
    // in FusioErrors), so it loads happily against either variant.
    testFixturesApi(libs.kotlin.compiler)
    testFixturesApi(libs.kotlin.compiler.internal.test.framework)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.reflect)
    // Compose compiler plugin — registered explicitly in FusioExtensionRegistrarConfigurator
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
    testArtifacts(project(":fusio-annotations"))
    testArtifacts(project(":fusio-runtime"))
    testArtifacts(libs.compose.runtime)
    testArtifacts(libs.kotlinx.coroutines.core)
}

// Classpath for the :smokeK24 task below: Kotlin 2.4.0-Beta2 compiler +
// Compose compiler plugin + stdlib/runtime the sample source compiles against.
// Intentionally NOT the full test framework — that had a breaking internal-API
// rename between 2.3 and 2.4 (TestConfigurationBuilder split into Grouping /
// NonGrouping variants) so the same testFixtures bytecode can't drive both
// lanes. smokeK24 sidesteps the framework entirely by invoking K2JVMCompiler
// programmatically.
val smokeK24Compiler: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val smokeK24CompileClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    smokeK24Compiler(libs.kotlin.compiler.k24)
    smokeK24Compiler(libs.kotlin.compose.compiler.plugin.k24)

    smokeK24CompileClasspath("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.k24.get()}")
    smokeK24CompileClasspath(project(":fusio-annotations"))
    smokeK24CompileClasspath(project(":fusio-runtime"))
    smokeK24CompileClasspath(libs.compose.runtime)
    smokeK24CompileClasspath(libs.kotlinx.coroutines.core)
}

/**
 * Shared configuration for both :test (Kotlin 2.3.20) and :testK24 (2.4.0-Beta2)
 * lanes. Paths that resolve jars by name look them up on the passed [runtimeJars]
 * configuration so the caller controls the Kotlin line.
 */
fun Test.configureFusioCompilerPluginTest(runtimeJars: Configuration) {
    dependsOn(runtimeJars)
    useJUnitPlatform()
    workingDir = rootDir

    setLibraryProperty(runtimeJars, "org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty(runtimeJars, "org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty(runtimeJars, "org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty(runtimeJars, "org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty(runtimeJars, "org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty(runtimeJars, "org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    // Surface Fusio's own jars to testData sources that import com.kitakkun.fusio.*.
    systemProperty("fusioRuntime.classpath", runtimeJars.asPath)

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)

    // Forward -Pkotlin.test.update.test.data=true to the test JVM so the
    // framework auto-writes expected testData / .fir.txt files on mismatch.
    project.findProperty("kotlin.test.update.test.data")?.let {
        systemProperty("kotlin.test.update.test.data", it.toString())
    }
}

tasks.test {
    configureFusioCompilerPluginTest(testArtifacts)
}

// --- smokeK24 ---
// Invokes the Kotlin 2.4.0-Beta2 compiler (K2JVMCompiler) in a forked JVM with
// the shaded fusio-compiler-plugin.jar on its -Xplugin classpath. The run
// compiles src/smokeK24/kotlin/Sample.kt, which exercises every version-
// sensitive helper on CompatContext (@MapTo annotation -> kclassArg,
// mappedScope { ... } -> setArg/setTypeArg). Compile success proves:
//
//   1. CompatContextResolver selected the k240_beta2 impl via ServiceLoader
//      against the 2.4 compiler.
//   2. k240_beta2's kclassArg override + delegated setArg/setTypeArg both
//      link-resolve under 2.4 bytecode.
//   3. The shaded jar's META-INF/services stitching survives being loaded
//      into a 2.4 compiler plugin classloader.
//
// Much narrower than a full box-test matrix, but the bits we actually need
// to know work under 2.4 are covered.
val smokeK24 by tasks.registering(JavaExec::class) {
    description = "Compiles src/smokeK24/kotlin/ with Kotlin 2.4.0-Beta2 + the shaded Fusio plugin."
    group = "verification"

    dependsOn(tasks.named("shadowJar"))
    dependsOn(smokeK24Compiler, smokeK24CompileClasspath)

    val sourceDirProvider = layout.projectDirectory.dir("src/smokeK24/kotlin")
    inputs.dir(sourceDirProvider)
        .withPropertyName("smokeK24Sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(tasks.named("shadowJar")).withPropertyName("shadowJar")
    val outDir = layout.buildDirectory.dir("smokeK24-out")
    outputs.dir(outDir).withPropertyName("smokeK24Out")

    classpath = smokeK24Compiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

    // Capture every script-side reference as a local Provider so the doFirst
    // lambda below doesn't reach into `tasks` / `layout` / `Configuration`
    // instances at execution time — the configuration cache cannot serialize
    // those live Gradle objects and the task would be marked incompatible.
    val sourceDir = sourceDirProvider.asFile
    val pluginJarProvider = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    val compileClasspathProvider = smokeK24CompileClasspath.elements
    val compilerFilesProvider = smokeK24Compiler.elements

    // Build up K2JVMCompiler argv. Using doFirst so layout/resolution happens
    // at execution time (shadowJar output path etc.).
    doFirst {
        require(sourceDir.exists()) { "smoke source missing: $sourceDir" }
        val pluginJar = pluginJarProvider.get().asFile
        val out = outDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val cp = compileClasspathProvider.get().joinToString(File.pathSeparator) {
            it.asFile.absolutePath
        }
        val composeJar = compilerFilesProvider.get()
            .first { it.asFile.name.startsWith("kotlin-compose-compiler-plugin") }
            .asFile

        args = listOf(
            sourceDir.absolutePath,
            "-d", out.absolutePath,
            "-classpath", cp,
            "-Xplugin=${pluginJar.absolutePath}",
            "-Xplugin=${composeJar.absolutePath}",
            "-Xcompiler-plugin-order=com.kitakkun.fusio>androidx.compose.compiler.plugins.kotlin",
            // Match the JVM target the runtime deps were built against (21), else
            // `buildPresenter`'s @Composable inline body can't be inlined into
            // our Sample.kt's bytecode built at the default 1.8 target.
            "-jvm-target", "21",
            "-no-reflect",
            "-no-stdlib", // stdlib is already on -classpath
        )
    }
}

// Keep smokeK24 included in the aggregate `check` lifecycle so it fails CI if
// the shaded jar stops loading cleanly under 2.4.
tasks.named("check") { dependsOn(smokeK24) }

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
    mainClass.set("com.kitakkun.fusio.compiler.test.GenerateTestsKt")
    workingDir = rootDir
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}

fun Test.setLibraryProperty(runtimeJars: Configuration, propName: String, jarName: String) {
    val path = runtimeJars.files
        .find { """$jarName-\d.*""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
}

// --- Shading ---
// End users install a single `com.kitakkun.fusio:fusio-compiler-plugin` jar;
// every fusio-compiler-compat subproject's classes live inside it. ServiceLoader
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
    // Shadow 9.x defaults duplicatesStrategy to EXCLUDE at the Copy layer,
    // which drops duplicate entries BEFORE mergeServiceFiles() sees them.
    // Allow INCLUDE so the transformer receives every copy, then re-exclude
    // non-service duplicates so we don't bloat the jar with dupes of other
    // resources. Without this, only one k** subproject's CompatContext.Factory
    // entry survives and ServiceLoader can't resolve the non-primary version.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

// The unshaded jar keeps building but under a classifier so the shaded output
// can own the default coordinate. Not published — just kept for Gradle's
// internal task graph and anyone who wants to inspect the pre-shade classes.
tasks.named<Jar>("jar") {
    archiveClassifier.set("original")
}

// Composite-build consumers (e.g. ../sample) resolve :fusio-compiler-plugin
// through the `runtimeElements` / `apiElements` configurations, whose default
// outgoing artifact is the plain `jar` output. Since we've reclassified that
// jar and the shaded output is what the Kotlin compiler actually needs at
// runtime, swap the outgoing artifact for shadowJar on both configurations.
configurations {
    named("apiElements") {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar"))
    }
    named("runtimeElements") {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.named("shadowJar"))
    }
}

// Register a publication driven by the `shadow` component (shaded jar + POM
// with external runtime deps). Eagerly — not in afterEvaluate — so the
// fusio.publish convention sees a non-empty publications list and skips
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
