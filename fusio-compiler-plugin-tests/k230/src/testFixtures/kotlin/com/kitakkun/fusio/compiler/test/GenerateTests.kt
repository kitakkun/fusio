package com.kitakkun.fusio.compiler.test

import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmBoxTest
import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/**
 * Kotlin 2.3.0 test-runner generator. Box + diagnostics only — the IR text
 * lane lives on the canonical k2321 / k240_beta2 modules because its goldens
 * are version-specific and the `SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK`
 * directive it needs doesn't exist on older 2.3.x patches.
 */
fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = "fusio-compiler-plugin-tests/testData",
            testsRoot = "fusio-compiler-plugin-tests/k230/test-gen",
        ) {
            testClass<AbstractFusioJvmDiagnosticTest> {
                model("diagnostics")
            }
            testClass<AbstractFusioJvmBoxTest> {
                model("box")
            }
        }
    }
}
