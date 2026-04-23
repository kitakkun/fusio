package com.kitakkun.fusio.compiler.test

import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmBoxTest
import com.kitakkun.fusio.compiler.test.runners.AbstractFusioJvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = "fusio-compiler-plugin/testData",
            testsRoot = "fusio-compiler-plugin/test-gen",
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
