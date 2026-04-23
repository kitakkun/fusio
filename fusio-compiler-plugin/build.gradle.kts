import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    id("fusio.publish")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
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

    // These jars get bundled into shadowJar. The k** subprojects register
    // themselves via META-INF/services and are only referenced by
    // ServiceLoader, so compileOnly isn't needed for them — only shaded.
    shaded(project(":fusio-compiler-compat"))
    shaded(project(":fusio-compiler-compat:k230"))
    shaded(project(":fusio-compiler-compat:k2320"))
    shaded(project(":fusio-compiler-compat:k240_beta2"))

    // Production: kotlin-compiler-embeddable has IntelliJ classes shaded under
    // `org.jetbrains.kotlin.com.intellij.*`, matching what the Kotlin Gradle
    // plugin loads our plugin jar against at user-project compile time.
    compileOnly(libs.kotlin.compiler.embeddable)
}

// Classpath for the :smokeK24 task below: Kotlin 2.4.0-Beta2 compiler +
// Compose compiler plugin + stdlib/runtime the sample source compiles against.
// smokeK24 lives here (not in the tests umbrella) because it's a test of the
// shaded jar's LOAD path — ServiceLoader + classloader — rather than a
// testData regression guard. See fusio-compiler-plugin-tests/ for the
// behavioural lanes.
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

// --- smokeK24 ---
// Invokes the Kotlin 2.4.0-Beta2 compiler (K2JVMCompiler) in a forked JVM with
// the shaded fusio-compiler-plugin.jar on its -Xplugin classpath. Compile
// success proves:
//   1. CompatContextResolver selected the k240_beta2 impl via ServiceLoader
//      against the 2.4 compiler.
//   2. k240_beta2's kclassArg override + delegated setArg/setTypeArg both
//      link-resolve under 2.4 bytecode.
//   3. The shaded jar's META-INF/services stitching survives being loaded
//      into a 2.4 compiler plugin classloader.
val smokeK24 by tasks.registering(JavaExec::class) {
    description = "Compiles src/smokeK24/kotlin/ with Kotlin 2.4.0-Beta2 + the shaded Fusio plugin."
    group = "verification"

    dependsOn(tasks.named("shadowJar"))
    dependsOn(smokeK24Compiler, smokeK24CompileClasspath)

    val sourceDirProvider = layout.projectDirectory.dir("src/smokeK24/kotlin")
    inputs.dir(sourceDirProvider)
        .withPropertyName("smokeK24Sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(tasks.named("shadowJar"))
        .withPropertyName("shadowJar")
        .withNormalizer(ClasspathNormalizer::class)
    inputs.files(smokeK24CompileClasspath)
        .withPropertyName("smokeK24CompileClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    inputs.files(smokeK24Compiler)
        .withPropertyName("smokeK24Compiler")
        .withNormalizer(ClasspathNormalizer::class)
    val outDir = layout.buildDirectory.dir("smokeK24-out")
    outputs.dir(outDir).withPropertyName("smokeK24Out")
    outputs.cacheIf("all inputs are declared + classpath-normalized") { true }

    classpath = smokeK24Compiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

    val sourceDir = sourceDirProvider.asFile
    val pluginJarProvider = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    val compileClasspathProvider = smokeK24CompileClasspath.elements
    val compilerFilesProvider = smokeK24Compiler.elements

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
            "-jvm-target", "21",
            "-no-reflect",
            "-no-stdlib",
        )
    }
}

tasks.named("check") { dependsOn(smokeK24) }

// --- Shading ---
// End users install a single `com.kitakkun.fusio:fusio-compiler-plugin` jar;
// every fusio-compiler-compat subproject's classes live inside it.

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shaded)
    // Shadow 9.x defaults duplicatesStrategy to EXCLUDE at the Copy layer,
    // which drops duplicate entries BEFORE mergeServiceFiles() sees them.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("original")
}

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

publishing {
    publications {
        create<MavenPublication>("shadow") {
            from(components["shadow"])
            artifactId = project.name
        }
    }
}
