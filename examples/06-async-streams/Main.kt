package com.example.asyncpipeline

import com.example.asyncpipeline.example.async_pipeline.enricher.Enricher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

fun main() {
    val hostImports = imports(
        exampleAsyncPipelineEnricher = {
            object : Enricher {
                override suspend fun formatItem(id: UInt, payload: String): String {
                    delay(10)
                    return "#$id:${payload.uppercase()}"
                }
            }
        },
    )

    runBlocking {
        withSession(AsyncPipelineComponent, hostImports) {
            val numbers = produceNumbers(10u, 4u).toList()
            val doubledSum = sumStream(numbers.asFlow().map { it * 2u })

            val (formattedFlow, countDeferred) = processItems(flowOf("alpha", "beta", "gamma"))
            val (formatted, processedCount) = coroutineScope {
                val collected = async { formattedFlow.toList() }
                val total = async { countDeferred.await() }
                collected.await() to total.await()
            }

            println(
                "numbers = $numbers, doubledSum = $doubledSum, count = $processedCount, formatted = $formatted",
            )
        }
    }
}
