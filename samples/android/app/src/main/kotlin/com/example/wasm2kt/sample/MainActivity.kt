package com.example.wasm2kt.sample

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(48, 48, 48, 48) }
        setContentView(ScrollView(this).apply { addView(text) })
        text.text = "Running WebAssembly components on Chicory…"
        // First use instantiates the components; keep it off the main thread.
        Thread {
            val started = System.nanoTime()
            val result = runCatching { ComponentDemo.run() }
            val ms = (System.nanoTime() - started) / 1_000_000
            runOnUiThread {
                text.text = result.fold(
                    onSuccess = { lines -> (lines + listOf("", "took $ms ms")).joinToString("\n") },
                    onFailure = { e -> "Failed: $e" },
                )
            }
        }.start()
    }
}
