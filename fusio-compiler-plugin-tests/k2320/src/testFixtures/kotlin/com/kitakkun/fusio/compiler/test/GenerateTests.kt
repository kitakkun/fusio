package com.kitakkun.fusio.compiler.test

import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmBoxTest
import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/**
 * Kotlin 2.3.20 test-runner generator. Box + diagnostics only; IR lane
 * lives on k2321 / k240_beta2.
 */
fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = "fusio-compiler-plugin-tests/testData",
            testsRoot = "fusio-compiler-plugin-tests/k2320/test-gen",
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
