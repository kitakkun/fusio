package com.kitakkun.fusio.compiler.test.k24.services

import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.AdditionalSourceProvider
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager

/**
 * Same `WITH_FUSIO_HEADLESS` opt-in as the primary lane — duplicated here
 * so the k24 test module doesn't need to pull in the 2.3-shaped
 * TestConfigurationBuilder symbols that its sibling module's test-fixtures
 * also contain. The helper source body is identical; the Kotlin runtime
 * it's compiled against changes (2.4.0-Beta2 via this module's
 * testArtifacts configuration).
 */
object FusioK24Directives : SimpleDirectivesContainer() {
    val WITH_FUSIO_HEADLESS by directive("Include the headless Compose runner helper")
}

class FusioK24HeadlessRunnerSourceProvider(testServices: TestServices) : AdditionalSourceProvider(testServices) {
    override val directiveContainers: List<DirectivesContainer> = listOf(FusioK24Directives)

    override fun produceAdditionalFiles(
        globalDirectives: RegisteredDirectives,
        module: TestModule,
        testModuleStructure: TestModuleStructure,
    ): List<TestFile> {
        if (!containsDirective(globalDirectives, module, FusioK24Directives.WITH_FUSIO_HEADLESS)) {
            return emptyList()
        }

        val dir = testServices.temporaryDirectoryManager.getOrCreateTempDirectory("fusioHeadless")
        val file = dir.resolve(HELPER_FILE_NAME).also { it.writeText(HELPER_SOURCE) }
        return listOf(
            TestFile(
                relativePath = HELPER_FILE_NAME,
                originalContent = HELPER_SOURCE,
                originalFile = file,
                startLineNumberInOriginalFile = 0,
                isAdditional = true,
                directives = RegisteredDirectives.Empty,
            ),
        )
    }

    private companion object {
        const val HELPER_FILE_NAME = "FusioHeadlessRunner.kt"

        val HELPER_SOURCE: String = """
            package fusio.test

            import androidx.compose.runtime.AbstractApplier
            import androidx.compose.runtime.BroadcastFrameClock
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Composition
            import androidx.compose.runtime.LaunchedEffect
            import androidx.compose.runtime.Recomposer
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.snapshots.Snapshot
            import com.kitakkun.fusio.Presentation
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.Job
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.MutableSharedFlow
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.runBlocking

            private class HeadlessApplier : AbstractApplier<Unit>(Unit) {
                override fun onClear() = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun remove(index: Int, count: Int) = Unit
            }

            fun <E, S, Eff> runHeadless(
                present: @Composable (Flow<E>) -> Presentation<S, Eff>,
                block: suspend Inputs<E, S, Eff>.() -> Unit,
            ) = runBlocking {
                val events = MutableSharedFlow<E>(extraBufferCapacity = 64)
                val clock = BroadcastFrameClock()
                val ctx = coroutineContext + clock + Job(coroutineContext[Job])
                val recomposer = Recomposer(ctx)
                val runner = CoroutineScope(ctx)
                runner.launch { recomposer.runRecomposeAndApplyChanges() }

                val currentState = mutableStateOf<S?>(null)
                val collectedEffects = mutableListOf<Eff>()

                val composition = Composition(HeadlessApplier(), recomposer)
                composition.setContent {
                    val presentation = present(events)
                    currentState.value = presentation.state
                    LaunchedEffect(presentation.effectFlow) {
                        presentation.effectFlow.collect { collectedEffects.add(it) }
                    }
                }

                val inputs = Inputs(events, currentState, collectedEffects, clock)
                try {
                    inputs.pump()
                    inputs.block()
                } finally {
                    composition.dispose()
                    recomposer.close()
                    runner.coroutineContext[Job]?.cancel()
                }
            }

            class Inputs<E, S, Eff>(
                private val events: MutableSharedFlow<E>,
                private val stateHolder: androidx.compose.runtime.MutableState<S?>,
                val effects: MutableList<Eff>,
                private val clock: BroadcastFrameClock,
            ) {
                val state: S? get() = stateHolder.value

                suspend fun emit(event: E) {
                    events.emit(event)
                    pump()
                }

                suspend fun pump(ticks: Int = 3) {
                    repeat(ticks) {
                        delay(5)
                        Snapshot.sendApplyNotifications()
                        clock.sendFrame(System.nanoTime())
                    }
                }
            }
        """.trimIndent()
    }
}
