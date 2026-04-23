package com.kitakkun.fusio.compiler.test.runners

import com.kitakkun.fusio.compiler.test.services.FusioHeadlessRunnerSourceProvider
import com.kitakkun.fusio.compiler.test.services.configureFusioPlugin
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.ir.AbstractFirLightTreeJvmIrTextTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

/**
 * Golden-file IR dump tests for the Fusio IR transformer.
 *
 * Each testData/ir/foo.kt is compiled with the Fusio plugin, then its
 * post-transform IR is dumped and compared to a sibling `foo.ir.txt`. A
 * regression in `FuseTransformer` — changed call sites, lost
 * arguments, reordered blocks — shows up as a diff against the golden
 * file instead of just as a behavioural failure in the box tests.
 *
 * Regenerate the .ir.txt expectations with:
 *   ./gradlew :fusio-compiler-plugin:test -Pkotlin.test.update.test.data=true
 *
 * Same framework base as the box tests, so plugin registration and the
 * JDK / stdlib setup mirror exactly.
 */
open class AbstractFusioJvmIrTextTest : AbstractFirLightTreeJvmIrTextTest() {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            defaultDirectives {
                +JvmEnvironmentConfigurationDirectives.FULL_JDK
                JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_21
                +CodegenTestDirectives.IGNORE_DEXING
                // IrText tests also run JvmNewKotlinReflectCompatibilityCheck
                // by default, which synthesizes a new kotlin-reflect process
                // and dumps every KClass in the compilation. It crashes on
                // our Compose / plugin-generated types (NPE in
                // ReflectTypeSystemContext). Skip it — our concern here is
                // the IR dump, not kotlin-reflect's own compatibility.
                +CodegenTestDirectives.SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK
            }
            configureFusioPlugin()
            useAdditionalSourceProviders(::FusioHeadlessRunnerSourceProvider)
        }
    }
}
