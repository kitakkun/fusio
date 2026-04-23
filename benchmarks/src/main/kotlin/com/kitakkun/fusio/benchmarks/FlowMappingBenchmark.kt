package com.kitakkun.fusio.benchmarks

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Measures the throughput of event-mapping pipelines that the Fusio compiler
 * plugin generates for `fuse { subPresenter() }`. The plugin rewrites
 * the call site into `parentFlow.mapNotNull { when(event) { ... } }` — we
 * bench the exact shape here so regressions in Flow cold-path cost land
 * visibly.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
open class FlowMappingBenchmark {

    sealed interface ParentEvent {
        data class ToChild(val payload: Int) : ParentEvent
        data class SiblingOnly(val payload: Int) : ParentEvent
    }

    sealed interface ChildEvent {
        data class Mapped(val payload: Int) : ChildEvent
    }

    private lateinit var allMapped: List<ParentEvent>
    private lateinit var halfMapped: List<ParentEvent>

    @Setup
    fun setup() {
        allMapped = List(EVENT_COUNT) { ParentEvent.ToChild(it) }
        halfMapped = List(EVENT_COUNT) { i ->
            if (i % 2 == 0) ParentEvent.ToChild(i) else ParentEvent.SiblingOnly(i)
        }
    }

    /** Every event maps — pure transformation, no filtering. */
    @Benchmark
    fun mapAllEvents(): Int = runBlocking {
        allMapped.asFlow()
            .mapToChild()
            .count()
    }

    /** Half the events map to null — measures mapNotNull's filter path. */
    @Benchmark
    fun mapHalfEvents(): Int = runBlocking {
        halfMapped.asFlow()
            .mapToChild()
            .count()
    }

    private fun Flow<ParentEvent>.mapToChild(): Flow<ChildEvent> = mapNotNull { event ->
        when (event) {
            is ParentEvent.ToChild -> ChildEvent.Mapped(event.payload)
            is ParentEvent.SiblingOnly -> null
        }
    }

    private companion object {
        const val EVENT_COUNT = 10_000
    }
}
