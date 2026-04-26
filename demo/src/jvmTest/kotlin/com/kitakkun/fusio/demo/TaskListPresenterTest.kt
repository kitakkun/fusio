package com.kitakkun.fusio.demo

import com.kitakkun.fusio.test.awaitEffect
import com.kitakkun.fusio.test.testSubPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for `taskList` — the TaskList sub-presenter in isolation.
 *
 * Uses [testSubPresenter] because `taskList` returns `TaskListState`
 * directly (the sub-presenter shape) rather than a `Presentation<_, _, _>` (Event, Effect, State).
 * No parent, no `@MapTo` / `@MapFrom` wiring in scope — this is the
 * smallest blast radius a bug can have in a decomposed presenter tree.
 */
class TaskListPresenterTest {

    @Test
    fun add_appends_a_task_and_emits_Added() = testSubPresenter(
        subPresenter = { taskList() },
    ) {
        send(TaskListEvent.Add(title = "Buy milk"))
        awaitState { it.tasks.singleOrNull()?.title == "Buy milk" }

        val added = awaitEffect<TaskListEffect.Added>()
        assertEquals("Buy milk", added.title)
    }

    @Test
    fun add_ignores_blank_title() = testSubPresenter<TaskListEvent, TaskListState, TaskListEffect>(
        subPresenter = { taskList() },
    ) {
        send(TaskListEvent.Add(title = "   "))
        // State stays empty — the presenter silently drops the blank.
        assertState { it.tasks.isEmpty() }
        expectNoEffects(within = 20.milliseconds)
    }

    @Test
    fun toggle_true_fires_Completed_but_toggle_back_stays_quiet() = testSubPresenter(
        subPresenter = { taskList() },
    ) {
        // Arrange: add one task and drain the Added effect.
        send(TaskListEvent.Add("Ship it"))
        awaitState { it.tasks.size == 1 }
        val id = state.tasks.first().id
        awaitEffect<TaskListEffect.Added>()

        // Act 1: mark complete — should flip state and emit Completed.
        send(TaskListEvent.Toggle(id))
        awaitState { it.tasks.first().completed }
        val completed = awaitEffect<TaskListEffect.Completed>()
        assertEquals("Ship it", completed.title)

        // Act 2: un-complete — state flips, but no effect. The presenter
        // documents this edge-only emission explicitly; this test is the
        // regression guard.
        send(TaskListEvent.Toggle(id))
        awaitState { !it.tasks.first().completed }
        expectNoEffects(within = 20.milliseconds)
    }

    @Test
    fun remove_shrinks_the_list() = testSubPresenter<TaskListEvent, TaskListState, TaskListEffect>(
        subPresenter = { taskList() },
    ) {
        send(TaskListEvent.Add("A"))
        awaitState { it.tasks.size == 1 }
        awaitEffect<TaskListEffect.Added>()
        send(TaskListEvent.Add("B"))
        awaitState { it.tasks.size == 2 }
        awaitEffect<TaskListEffect.Added>()

        val firstId = state.tasks.first().id
        send(TaskListEvent.Remove(firstId))

        awaitState { it.tasks.size == 1 }
        assertTrue(state.tasks.none { it.id == firstId })
    }
}
