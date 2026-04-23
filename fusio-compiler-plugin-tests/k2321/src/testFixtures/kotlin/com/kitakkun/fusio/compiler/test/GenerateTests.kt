package com.kitakkun.fusio.compiler.test

import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmBoxTest
import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmDiagnosticTest
import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmIrTextTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/**
 * Primary (Kotlin 2.3.21) test-runner generator.
 *
 * Two testGroup blocks because the source-of-truth for testData is split:
 * - box / diagnostics live under `fusio-compiler-plugin-tests/testData/` and
 *   are shared verbatim with the k240_beta2 lane — the cases describe
 *   behavioural contracts that should be identical on every supported compiler.
 * - ir text goldens are compiler-version-specific (Kotlin's IR dump format
 *   shifts across releases), so this lane owns its own
 *   `fusio-compiler-plugin-tests/k2321/testData/ir/` directory.
 */
fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = "fusio-compiler-plugin-tests/testData",
            testsRoot = "fusio-compiler-plugin-tests/k2321/test-gen",
        ) {
            testClass<AbstractFusioJvmDiagnosticTest> {
                model("diagnostics")
            }
            testClass<AbstractFusioJvmBoxTest> {
                model("box")
            }
        }
        testGroup(
            testDataRoot = "fusio-compiler-plugin-tests/k2321/testData",
            testsRoot = "fusio-compiler-plugin-tests/k2321/test-gen",
        ) {
            testClass<AbstractFusioJvmIrTextTest> {
                model("ir")
            }
        }
    }
}
