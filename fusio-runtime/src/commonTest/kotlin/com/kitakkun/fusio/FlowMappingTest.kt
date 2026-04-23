package com.kitakkun.fusio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FlowMappingTest {

    sealed interface Parent {
        data class A(val n: Int) : Parent
        data class B(val s: String) : Parent
        data object Skip : Parent
    }

    sealed interface Child {
        data class X(val n: Int) : Child
        data class Y(val s: String) : Child
    }

    @Test
    fun mapEvents_passes_through_mapped_items_and_drops_nulls() = runTest {
        val source = flowOf(
            Parent.A(1),
            Parent.Skip,
            Parent.B("hi"),
            Parent.A(2),
        )

        val mapped: List<Child> = source.mapEvents { p ->
            when (p) {
                is Parent.A -> Child.X(p.n)
                is Parent.B -> Child.Y(p.s)
                Parent.Skip -> null
            }
        }.toList()

        assertEquals(
            listOf(Child.X(1), Child.Y("hi"), Child.X(2)),
            mapped,
        )
    }

    @Test
    fun mapEvents_preserves_order() = runTest {
        val source = flowOf(1, 2, 3, 4, 5)
        val doubled = source.mapEvents { it * 2 }.toList()
        assertEquals(listOf(2, 4, 6, 8, 10), doubled)
    }
}
