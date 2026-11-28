package wasm2kt.bench

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import wasm2kt.runtime.chicory.ChicoryComponent
import wasm2kt.wasi.wasi03Imports
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * Steady-state measurements for Component Model Async and WASI 0.3 across `chicory-aot`,
 * `chicory-interp`, and `aot` backends (ADR-0024 Stage 1).
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    println(environment())
    val h = Harness()

    // 1. async-stream: async value and stream consumption (count-up allocates non-freeing heap per call)
    bench.async.async_stream_aot.openSession(bench.async.async_stream_aot.AsyncStreamAotComponent).use { s ->
        context(s) {
            h.run("async-stream", "double(21)", "aot") { runBlocking { bench.async.async_stream_aot.double(21u) } }
            val input = (1u..32u).asFlow()
            h.run("async-stream", "sum(32)", "aot") { runBlocking { bench.async.async_stream_aot.sum(input) } }
        }
    }
    bench.async.async_stream.openSession(bench.async.async_stream.AsyncStreamComponent).use { s ->
        context(s) {
            h.run("async-stream", "double(21)", "chicory-aot") { runBlocking { bench.async.async_stream.double(21u) } }
            val input = (1u..32u).asFlow()
            h.run("async-stream", "sum(32)", "chicory-aot") { runBlocking { bench.async.async_stream.sum(input) } }
        }
    }
    bench.async.async_stream.openSession(ChicoryComponent.fromDirectory(BenchPaths.decomposed("async-stream"))).use { s ->
        context(s) {
            h.run("async-stream", "double(21)", "chicory-interp") { runBlocking { bench.async.async_stream.double(21u) } }
            val input = (1u..32u).asFlow()
            h.run("async-stream", "sum(32)", "chicory-interp") { runBlocking { bench.async.async_stream.sum(input) } }
        }
    }

    // 2. async-pipeline: suspending host enricher + stream + future
    run {
        val aotImports = bench.async.async_pipeline_aot.imports(
            exampleAsyncPipelineEnricher = {
                object : bench.async.async_pipeline_aot.example.async_pipeline.enricher.Enricher {
                    override suspend fun formatItem(id: UInt, payload: String): String = "#$id:${payload.uppercase()}"
                }
            },
        )
        bench.async.async_pipeline_aot.openSession(bench.async.async_pipeline_aot.AsyncPipelineAotComponent, aotImports).use { s ->
            val items = flowOf("alpha", "beta", "gamma")
            context(s) {
                h.run("async-pipeline", "process-items(3)", "aot") {
                    runBlocking {
                        val (out, cnt) = bench.async.async_pipeline_aot.processItems(items)
                        out.toList() to cnt.await()
                    }
                }
            }
        }

        val chicoryImports = bench.async.async_pipeline.imports(
            exampleAsyncPipelineEnricher = {
                object : bench.async.async_pipeline.example.async_pipeline.enricher.Enricher {
                    override suspend fun formatItem(id: UInt, payload: String): String = "#$id:${payload.uppercase()}"
                }
            },
        )
        bench.async.async_pipeline.openSession(bench.async.async_pipeline.AsyncPipelineComponent, chicoryImports).use { s ->
            val items = flowOf("alpha", "beta", "gamma")
            context(s) {
                h.run("async-pipeline", "process-items(3)", "chicory-aot") {
                    runBlocking {
                        val (out, cnt) = bench.async.async_pipeline.processItems(items)
                        out.toList() to cnt.await()
                    }
                }
            }
        }

        bench.async.async_pipeline.openSession(
            ChicoryComponent.fromDirectory(BenchPaths.decomposed("async-pipeline")),
            chicoryImports,
        ).use { s ->
            val items = flowOf("alpha", "beta", "gamma")
            context(s) {
                h.run("async-pipeline", "process-items(3)", "chicory-interp") {
                    runBlocking {
                        val (out, cnt) = bench.async.async_pipeline.processItems(items)
                        out.toList() to cnt.await()
                    }
                }
            }
        }
    }

    // 3. wasi-03: WASI 0.3 stdout, env, args, filesystem roundtrip
    val workDir = BenchPaths.out.resolve("wasi03-bench-work")
    workDir.toFile().deleteRecursively()
    Files.createDirectories(workDir)
    try {
        Files.writeString(workDir.resolve("input.txt"), "hello bench")
        val stdoutAot = ByteArrayOutputStream()
        val wasiAotImports = bench.async.wasi_03_aot.imports(
            base = wasi03Imports {
                args("wasi-03", "--bench")
                env("WASI_ENV" to "bench-ok")
                stdin("hi".encodeToByteArray())
                stdout = stdoutAot
                mounts { readWrite("/work", workDir) }
            },
            exampleWasiAppAudit = {
                object : bench.async.wasi_03_aot.example.wasi_app.audit.Audit {
                    override fun recordStep(tag: String) {}
                }
            },
        )
        bench.async.wasi_03_aot.openSession(bench.async.wasi_03_aot.Wasi03AotComponent, wasiAotImports).use { s ->
            context(s) {
                h.run("wasi-03", "run-summary", "aot") {
                    stdoutAot.reset()
                    runBlocking { bench.async.wasi_03_aot.runSummary() }
                }
            }
        }

        val stdoutChicory = ByteArrayOutputStream()
        val wasiChicoryImports = bench.async.wasi_03.imports(
            base = wasi03Imports {
                args("wasi-03", "--bench")
                env("WASI_ENV" to "bench-ok")
                stdin("hi".encodeToByteArray())
                stdout = stdoutChicory
                mounts { readWrite("/work", workDir) }
            },
            exampleWasiAppAudit = {
                object : bench.async.wasi_03.example.wasi_app.audit.Audit {
                    override fun recordStep(tag: String) {}
                }
            },
        )
        bench.async.wasi_03.openSession(bench.async.wasi_03.Wasi03Component, wasiChicoryImports).use { s ->
            context(s) {
                h.run("wasi-03", "run-summary", "chicory-aot") {
                    stdoutChicory.reset()
                    runBlocking { bench.async.wasi_03.runSummary() }
                }
            }
        }
    } finally {
        workDir.toFile().deleteRecursively()
    }

    h.write(System.getProperty("wasm2kt.bench.outFile") ?: "async-calls.tsv")
}
