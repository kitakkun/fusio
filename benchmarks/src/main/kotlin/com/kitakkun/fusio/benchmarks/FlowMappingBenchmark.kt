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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Measures the throughput of event-mapping pipelines that the Fusio compiler
 * plugin generates for `fuse { subPresenter() }`. The plugin rewrites the
 * call site into `parentFlow.mapEvents { when(event) { ... } }`, where
 * `mapEvents` is a thin `Flow.mapNotNull` wrapper in `fusio-runtime`. We
 * bench the exact shape so regressions in Flow cold-path cost land
 * visibly, and compare it against two other hand-written patterns a user
 * might reach for if they weren't using the plugin.
 *
 * ## What the numbers are for
 *
 * Fusio doesn't claim to be *faster* than raw Flow — its value is code
 * reduction + compile-time exhaustiveness. The honest claim this
 * benchmark backs up is that the generated shape has **zero overhead
 * versus a hand-written equivalent**, and in fact keeps pace with the
 * cleanest non-Fusio alternative (`filterIsInstance + map`).
 *
 * ## Running locally
 *
 * ```
 * ./gradlew :benchmarks:jvmJar :benchmarks:benchmark
 * ```
 *
 * or for a single run:
 *
 * ```
 * ./gradlew :benchmarks:mainBenchmark -Pkotlinx.benchmark.include=FlowMappingBenchmark
 * ```
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

    // ---- "mapNotNull + when" — the pattern Fusio's plugin emits -----

    /** Every event maps — pure transformation, no filtering. */
    @Benchmark
    fun mapNotNullAll(): Int = runBlocking {
        allMapped.asFlow()
            .mapNotNullWithWhen()
            .count()
    }

    /** Half the events map to null — exercises mapNotNull's filter path. */
    @Benchmark
    fun mapNotNullHalf(): Int = runBlocking {
        halfMapped.asFlow()
            .mapNotNullWithWhen()
            .count()
    }

    // ---- "filterIsInstance + map" — cleanest non-Fusio alternative --

    /**
     * `filterIsInstance` is type-safe and readable when you're only routing
     * ONE parent subtype to a child. For N subtypes the user has to compose
     * multiple branches manually, which is part of why Fusio's `@MapTo`-
     * driven `when` wins on code density (not on micro-throughput).
     */
    @Benchmark
    fun filterIsInstanceMapAll(): Int = runBlocking {
        allMapped.asFlow()
            .filterIsInstance<ParentEvent.ToChild>()
            .map { ChildEvent.Mapped(it.payload) }
            .count()
    }

    @Benchmark
    fun filterIsInstanceMapHalf(): Int = runBlocking {
        halfMapped.asFlow()
            .filterIsInstance<ParentEvent.ToChild>()
            .map { ChildEvent.Mapped(it.payload) }
            .count()
    }

    // ---- "filter + map" — the naive two-pass alternative -----------

    /**
     * A reader unfamiliar with `filterIsInstance` might write this. Kept
     * for comparison so we can point at a real reason `filterIsInstance`
     * or `mapNotNull + when` is preferable.
     */
    @Benchmark
    fun filterMapAll(): Int = runBlocking {
        allMapped.asFlow()
            .filter { it is ParentEvent.ToChild }
            .map { ChildEvent.Mapped((it as ParentEvent.ToChild).payload) }
            .count()
    }

    @Benchmark
    fun filterMapHalf(): Int = runBlocking {
        halfMapped.asFlow()
            .filter { it is ParentEvent.ToChild }
            .map { ChildEvent.Mapped((it as ParentEvent.ToChild).payload) }
            .count()
    }

    // ---- Shared helper mirroring the exact `mapEvents` body --------

    /**
     * Local copy of `fusio-runtime`'s `mapEvents` so we can call it from a
     * non-inline context (the production function is `@PublishedApi
     * internal`; we duplicate the body instead of opening up its
     * visibility just for benchmarking).
     */
    private fun Flow<ParentEvent>.mapNotNullWithWhen(): Flow<ChildEvent> = mapNotNull { event ->
        when (event) {
            is ParentEvent.ToChild -> ChildEvent.Mapped(event.payload)
            is ParentEvent.SiblingOnly -> null
        }
    }

    private companion object {
        const val EVENT_COUNT = 10_000
    }
}
