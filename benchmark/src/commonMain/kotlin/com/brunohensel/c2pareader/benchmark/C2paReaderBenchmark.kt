package com.brunohensel.c2pareader.benchmark

import com.brunohensel.c2pareader.C2paReader
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = BenchmarkTimeUnit.SECONDS)
open class C2paReaderBenchmark {

    @Param(
        "firefly_tabby_cat.jpg",
        "chatgpt_image.png",
        "chatgpt_image.c2pa",
        // Phase 3 fixtures. TIFF ships pre-signed in c2pa-rs; HEIC and WebP were signed locally
        // from the c2pa-rs source images using c2patool + the ES256 test cert.
        "100kb-signed.tiff",
        "sample1.heic",
        "sample1.webp",
    )
    lateinit var fixture: String

    private lateinit var bytes: ByteArray

    @Setup
    fun loadFixture() {
        val resource = "fixtures/$fixture"
        val stream = C2paReaderBenchmark::class.java.classLoader
            ?.getResourceAsStream(resource)
            ?: error("fixture '$resource' not on classpath")
        bytes = stream.use { it.readBytes() }
    }

    @Benchmark
    fun read(blackhole: Blackhole) {
        blackhole.consume(C2paReader.read(bytes))
    }
}
