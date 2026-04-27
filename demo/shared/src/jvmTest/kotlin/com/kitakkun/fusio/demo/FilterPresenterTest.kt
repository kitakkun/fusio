package com.kitakkun.fusio.demo

import com.kitakkun.fusio.test.awaitEffect
import com.kitakkun.fusio.test.testSubPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for `filter` — the Filter sub-presenter in isolation.
 *
 * Small enough that every code path fits here in three tests. The interesting
 * behaviour is the "no-op on same filter" branch, which is the kind of
 * quiet optimisation that can silently regress without a test.
 */
class FilterPresenterTest {

    @Test
    fun initial_state_is_All() = testSubPresenter<FilterEvent, FilterState, FilterEffect>(
        subPresenter = { filter() },
    ) {
        assertState { it.current == TaskFilter.All }
    }

    @Test
    fun select_new_filter_updates_state_and_emits_Changed() = testSubPresenter(
        subPresenter = { filter() },
    ) {
        send(FilterEvent.Select(TaskFilter.Active))
        awaitState { it.current == TaskFilter.Active }

        val changed = awaitEffect<FilterEffect.Changed>()
        assertEquals(TaskFilter.Active, changed.newFilter)
    }

    @Test
    fun selecting_same_filter_is_a_no_op() = testSubPresenter<FilterEvent, FilterState, FilterEffect>(
        subPresenter = { filter() },
    ) {
        send(FilterEvent.Select(TaskFilter.All)) // == current
        // State unchanged, no effect — the presenter guards against flapping.
        assertState { it.current == TaskFilter.All }
        expectNoEffects(within = 20.milliseconds)
    }
}
