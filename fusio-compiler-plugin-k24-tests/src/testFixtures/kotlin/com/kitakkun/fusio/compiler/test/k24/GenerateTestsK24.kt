package com.kitakkun.fusio.compiler.test.k24

import com.kitakkun.fusio.compiler.test.k24.runners.AbstractFusioK24JvmBoxTest
import com.kitakkun.fusio.compiler.test.k24.runners.AbstractFusioK24JvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/**
 * Parallel test-runner generator for the Kotlin 2.4.0-Beta2 lane. Points at
 * the SAME `fusio-compiler-plugin/testData` directory the primary 2.3 lane
 * consumes — every box / diagnostic case runs twice, once on each compiler.
 *
 * IR text tests are deliberately excluded: the Kotlin compiler's IR dump
 * format evolves across patch releases, so the goldens checked in next to
 * the .kt cases are tied to one compiler version. Running them on 2.4
 * would demand a parallel set of goldens that drifts every time IR
 * rendering changes, and catches nothing the box lane doesn't already
 * cover. The primary 2.3 lane keeps the IR snapshot as its regression
 * guard; the 2.4 lane just has to prove behaviour is correct.
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
    }
}
