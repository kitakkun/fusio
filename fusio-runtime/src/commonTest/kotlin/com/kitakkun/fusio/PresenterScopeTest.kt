package com.kitakkun.fusio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PresenterScopeTest {

    @Test
    fun emitEffect_is_received_via_internalEffectFlow() = runTest {
        val scope = PresenterScope<String, Int>(eventFlow = emptyFlow())
        val collected = async { scope.internalEffectFlow.take(3).toList() }

        scope.emitEffect(1)
        scope.emitEffect(2)
        scope.emitEffect(3)

        assertEquals(listOf(1, 2, 3), collected.await())
    }

    @Test
    fun close_terminates_effect_flow() = runTest {
        val scope = PresenterScope<String, Int>(eventFlow = emptyFlow())
        val collected = async { scope.internalEffectFlow.toList() }

        scope.emitEffect(10)
        scope.emitEffect(20)
        scope.close()

        assertEquals(listOf(10, 20), collected.await())
    }

    @Test
    fun eventFlow_is_the_flow_the_scope_was_constructed_with() = runTest {
        val source = kotlinx.coroutines.flow.flowOf("hello", "world")
        val scope = PresenterScope<String, Int>(eventFlow = source)

        // The eventFlow is the same flow instance we passed in (no transformation at scope level).
        assertEquals(listOf("hello", "world"), scope.eventFlow.toList())
    }
}
