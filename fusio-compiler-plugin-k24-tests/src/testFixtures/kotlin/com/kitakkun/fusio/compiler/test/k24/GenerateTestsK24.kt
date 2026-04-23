package com.kitakkun.fusio.compiler.test.k24

import com.kitakkun.fusio.compiler.test.k24.runners.AbstractFusioK24JvmBoxTest
import com.kitakkun.fusio.compiler.test.k24.runners.AbstractFusioK24JvmDiagnosticTest
import com.kitakkun.fusio.compiler.test.k24.runners.AbstractFusioK24JvmIrTextTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/**
 * Parallel test-runner generator for the Kotlin 2.4.0-Beta2 lane.
 *
 * Two testGroup blocks because the source-of-truth for testData is split
 * by category:
 * - box / diagnostics share the primary lane's testData — the .kt cases
 *   describe behavioural contracts that should be identical on every
 *   supported compiler, and duplicating them would only invite drift.
 * - ir text tests carry compiler-version-specific goldens (the Kotlin IR
 *   dump format shifts across releases), so the k24 lane owns its own
 *   testData/ir/ directory and its own `.fir.ir.txt` / `.fir.kt.txt`
 *   goldens pinned to 2.4.0-Beta2.
 */
fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = "fusio-compiler-plugin/testData",
            testsRoot = "fusio-compiler-plugin-k24-tests/test-gen",
        ) {
            testClass<AbstractFusioK24JvmDiagnosticTest> {
                model("diagnostics")
            }
            testClass<AbstractFusioK24JvmBoxTest> {
                model("box")
            }
        }
        testGroup(
            testDataRoot = "fusio-compiler-plugin-k24-tests/testData",
            testsRoot = "fusio-compiler-plugin-k24-tests/test-gen",
        ) {
            testClass<AbstractFusioK24JvmIrTextTest> {
                model("ir")
            }
        }
    }
}
