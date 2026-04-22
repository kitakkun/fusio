package com.github.kitakkun.aria.sample

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

// Minimal runner for a headless Compose-based presenter so we can exercise the
// IR-transformed mappedScope call outside an Android/desktop UI host.
fun main(): Unit = runBlocking {
    val eventFlow = MutableSharedFlow<MyScreenEvent>(extraBufferCapacity = 64)

    val clock = BroadcastFrameClock()
    val recomposerContext: CoroutineContext = coroutineContext + clock + Job(coroutineContext[Job])
    val recomposer = Recomposer(recomposerContext)

    val runnerScope = CoroutineScope(recomposerContext)
    runnerScope.launch { recomposer.runRecomposeAndApplyChanges() }

    val currentState = mutableStateOf<MyScreenUiState?>(null)

    val composition = Composition(AriaHeadlessApplier(), recomposer)
    composition.setContent {
        val aria = myScreenPresenter(eventFlow)
        currentState.value = aria.state
    }

    suspend fun pump(label: String) {
        // Let suspended event handlers run, apply snapshot changes, then recompose.
        repeat(3) {
            delay(10)
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
        }
        println("[$label] state = ${currentState.value}")
    }

    pump("initial")

    eventFlow.emit(MyScreenEvent.Search("hello"))
    pump("after Search")

    // This event will NOT be mapped to FavoriteEvent.Toggle until @MapTo IR
    // generation is implemented. For now it is silently dropped by the child
    // scope's on<FavoriteEvent.Toggle> filter.
    eventFlow.emit(MyScreenEvent.ToggleFavorite("item-1"))
    pump("after ToggleFavorite")

    composition.dispose()
    recomposer.close()
    runnerScope.coroutineContext[Job]?.cancel()
}
