package com.kitakkun.aria.compiler.test

import com.kitakkun.aria.compiler.test.runners.AbstractAriaJvmBoxTest
import com.kitakkun.aria.compiler.test.runners.AbstractAriaJvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = "aria-compiler-plugin/testData",
            testsRoot = "aria-compiler-plugin/test-gen",
        ) {
            testClass<AbstractAriaJvmDiagnosticTest> {
                model("diagnostics")
            }
            testClass<AbstractAriaJvmBoxTest> {
                model("box")
            }
        }
    }
}
