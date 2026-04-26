package com.kitakkun.fusio.demo

import com.kitakkun.fusio.test.awaitEffect
import com.kitakkun.fusio.test.testPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the full screen-level presenter.
 *
 * Unlike the sibling sub-presenter tests, these go through `myScreenPresenter`
 * — meaning every call exercises the compiler-plugin-generated plumbing:
 *
 *   - `@MapTo` routing (parent [MyScreenEvent] → child [TaskListEvent] / [FilterEvent])
 *   - `@MapFrom` lifting (child [TaskListEffect] / [FilterEffect] → parent [MyScreenEffect])
 *   - Filter-applied view derivation in the parent body
 *
 * The sibling sub-presenters already have their own unit tests, so this file
 * intentionally stays shallow: it only exercises the fusion layer, not the
 * internals of either child.
 */
class MyScreenPresenterTest {

    @Test
    fun AddTask_routes_through_to_child_and_Added_lifts_as_ShowTaskAdded() = testPresenter(
        presenter = { myScreenPresenter() },
    ) {
        send(MyScreenEvent.AddTask("Buy milk"))
        awaitState { it.visibleTasks.any { t -> t.title == "Buy milk" } }

        // The child emitted TaskListEffect.Added, which the parent's
        // @MapFrom(TaskListEffect.Added::class) lifted into ShowTaskAdded.
        val effect = awaitEffect<MyScreenEffect.ShowTaskAdded>()
        assertEquals("Buy milk", effect.title)
    }

    @Test
    fun SelectFilter_applies_in_the_parent_body() = testPresenter(
        presenter = { myScreenPresenter() },
    ) {
        send(MyScreenEvent.AddTask("Active task"))
        awaitState { it.totalCount == 1 }
        awaitEffect<MyScreenEffect.ShowTaskAdded>()

        send(MyScreenEvent.AddTask("Completed task"))
        awaitState { it.totalCount == 2 }
        awaitEffect<MyScreenEffect.ShowTaskAdded>()

        // Complete the second task to create a mixed list.
        val completedId = state.visibleTasks.last().id
        send(MyScreenEvent.ToggleTask(completedId))
        awaitState { it.completedCount == 1 }
        awaitEffect<MyScreenEffect.ShowTaskCompleted>()

        // Now filter to Completed — the parent derives `visibleTasks` from
        // the fused child state, so only one task should be visible.
        send(MyScreenEvent.SelectFilter(TaskFilter.Completed))
        awaitState { it.filter == TaskFilter.Completed && it.visibleTasks.size == 1 }

        val changed = awaitEffect<MyScreenEffect.ShowFilterChanged>()
        assertEquals(TaskFilter.Completed, changed.newFilter)
    }

    @Test
    fun counters_reflect_fused_child_state() = testPresenter(
        presenter = { myScreenPresenter() },
    ) {
        repeat(3) { i ->
            send(MyScreenEvent.AddTask("Task ${i + 1}"))
            awaitState { it.totalCount == i + 1 }
            awaitEffect<MyScreenEffect.ShowTaskAdded>()
        }

        // Complete 2 of the 3.
        val ids = state.visibleTasks.map { it.id }
        send(MyScreenEvent.ToggleTask(ids[0]))
        awaitState { it.completedCount == 1 }
        awaitEffect<MyScreenEffect.ShowTaskCompleted>()
        send(MyScreenEvent.ToggleTask(ids[1]))
        awaitState { it.completedCount == 2 }
        awaitEffect<MyScreenEffect.ShowTaskCompleted>()

        assertEquals(3, state.totalCount)
        assertEquals(2, state.completedCount)
        assertEquals(1, state.activeCount)
        assertTrue(state.visibleTasks.size == 3) // TaskFilter.All
    }
}
