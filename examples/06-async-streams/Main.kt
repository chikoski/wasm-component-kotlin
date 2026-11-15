package com.example.asyncpipeline

import com.example.asyncpipeline.example.async_pipeline.enricher.Enricher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

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

    withSessionBlocking(AsyncPipelineComponent, hostImports) {
        val numbers = produceNumbers(10u, 4u).toList()
        val doubledSum = sumStream(numbers.asFlow().map { it * 2u })

        val (formattedFlow, countDeferred) = processItems(flowOf("alpha", "beta", "gamma"))
        val collected = async { formattedFlow.toList() }
        val total = async { countDeferred.await() }
        val formatted = collected.await()
        val processedCount = total.await()

        println(
            "numbers = $numbers, doubledSum = $doubledSum, count = $processedCount, formatted = $formatted",
        )
    }
}
