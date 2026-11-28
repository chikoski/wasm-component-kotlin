package wasm2kt.bench

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import wasm2kt.wasi.wasi03Imports
import java.io.ByteArrayOutputStream
import java.nio.file.Files

fun main(args: Array<String>) {
    val (component, backend) = args
    fun ms(start: Long) = (System.nanoTime() - start) / 1e6
    fun phase(name: String, start: Long) = println("PHASE $name ${"%.3f".format(ms(start))}")
    val t0 = System.nanoTime()
    when (component) {
        "async-pipeline" -> {
            if (backend == "aot") {
                val imp = bench.async.async_pipeline_aot.imports(
                    exampleAsyncPipelineEnricher = {
                        object : bench.async.async_pipeline_aot.example.async_pipeline.enricher.Enricher {
                            override suspend fun formatItem(id: UInt, payload: String): String = "#$id:${payload.uppercase()}"
                        }
                    },
                )
                val s = bench.async.async_pipeline_aot.openSession(bench.async.async_pipeline_aot.AsyncPipelineAotComponent, imp); phase("open", t0)
                val t1 = System.nanoTime()
                runBlocking {
                    context(s) {
                        val (f, d) = bench.async.async_pipeline_aot.processItems(flowOf("a"))
                        Blackhole.consume(f.toList() to d.await())
                    }
                }
                phase("first-call", t1); phase("total", t0)
                val t2 = System.nanoTime()
                runBlocking {
                    context(s) {
                        val (f, d) = bench.async.async_pipeline_aot.processItems(flowOf("b"))
                        Blackhole.consume(f.toList() to d.await())
                    }
                }
                phase("second-call", t2); s.close()
                val t3 = System.nanoTime(); val s2 = bench.async.async_pipeline_aot.openSession(bench.async.async_pipeline_aot.AsyncPipelineAotComponent, imp); phase("second-open", t3); s2.close()
            } else {
                val imp = bench.async.async_pipeline.imports(
                    exampleAsyncPipelineEnricher = {
                        object : bench.async.async_pipeline.example.async_pipeline.enricher.Enricher {
                            override suspend fun formatItem(id: UInt, payload: String): String = "#$id:${payload.uppercase()}"
                        }
                    },
                )
                val s = bench.async.async_pipeline.openSession(bench.async.async_pipeline.AsyncPipelineComponent, imp); phase("open", t0)
                val t1 = System.nanoTime()
                runBlocking {
                    context(s) {
                        val (f, d) = bench.async.async_pipeline.processItems(flowOf("a"))
                        Blackhole.consume(f.toList() to d.await())
                    }
                }
                phase("first-call", t1); phase("total", t0)
                val t2 = System.nanoTime()
                runBlocking {
                    context(s) {
                        val (f, d) = bench.async.async_pipeline.processItems(flowOf("b"))
                        Blackhole.consume(f.toList() to d.await())
                    }
                }
                phase("second-call", t2); s.close()
                val t3 = System.nanoTime(); val s2 = bench.async.async_pipeline.openSession(bench.async.async_pipeline.AsyncPipelineComponent, imp); phase("second-open", t3); s2.close()
            }
        }
        "wasi-03" -> {
            val workDir = BenchPaths.out.resolve("wasi03-cold-${ProcessHandle.current().pid()}")
            workDir.toFile().deleteRecursively()
            Files.createDirectories(workDir)
            try {
                Files.writeString(workDir.resolve("input.txt"), "hello cold")
                if (backend == "aot") {
                    val imp = bench.async.wasi_03_aot.imports(
                        base = wasi03Imports {
                            args("wasi-03")
                            env("WASI_ENV" to "ok")
                            stdin("hi".encodeToByteArray())
                            stdout = ByteArrayOutputStream()
                            mounts { readWrite("/work", workDir) }
                        },
                        exampleWasiAppAudit = {
                            object : bench.async.wasi_03_aot.example.wasi_app.audit.Audit {
                                override fun recordStep(tag: String) {}
                            }
                        },
                    )
                    val s = bench.async.wasi_03_aot.openSession(bench.async.wasi_03_aot.Wasi03AotComponent, imp); phase("open", t0)
                    val t1 = System.nanoTime(); runBlocking { context(s) { Blackhole.consume(bench.async.wasi_03_aot.runSummary()) } }
                    phase("first-call", t1); phase("total", t0)
                    val t2 = System.nanoTime(); runBlocking { context(s) { Blackhole.consume(bench.async.wasi_03_aot.runSummary()) } }
                    phase("second-call", t2); s.close()
                    val t3 = System.nanoTime(); val s2 = bench.async.wasi_03_aot.openSession(bench.async.wasi_03_aot.Wasi03AotComponent, imp); phase("second-open", t3); s2.close()
                } else {
                    val imp = bench.async.wasi_03.imports(
                        base = wasi03Imports {
                            args("wasi-03")
                            env("WASI_ENV" to "ok")
                            stdin("hi".encodeToByteArray())
                            stdout = ByteArrayOutputStream()
                            mounts { readWrite("/work", workDir) }
                        },
                        exampleWasiAppAudit = {
                            object : bench.async.wasi_03.example.wasi_app.audit.Audit {
                                override fun recordStep(tag: String) {}
                            }
                        },
                    )
                    val s = bench.async.wasi_03.openSession(bench.async.wasi_03.Wasi03Component, imp); phase("open", t0)
                    val t1 = System.nanoTime(); runBlocking { context(s) { Blackhole.consume(bench.async.wasi_03.runSummary()) } }
                    phase("first-call", t1); phase("total", t0)
                    val t2 = System.nanoTime(); runBlocking { context(s) { Blackhole.consume(bench.async.wasi_03.runSummary()) } }
                    phase("second-call", t2); s.close()
                    val t3 = System.nanoTime(); val s2 = bench.async.wasi_03.openSession(bench.async.wasi_03.Wasi03Component, imp); phase("second-open", t3); s2.close()
                }
            } finally {
                workDir.toFile().deleteRecursively()
            }
        }
        else -> error("unknown async cold component $component")
    }
}
