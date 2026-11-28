package com.example.wasm2kt.sample

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val title = TextView(this).apply {
            text = "wasm2kt sample"
            textSize = 22f
        }
        val text = TextView(this).apply { setPadding(0, PADDING, 0, 0) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(text)
        }
        val root = ScrollView(this).apply { addView(content) }
        setContentView(root)

        // targetSdk 35 forces edge-to-edge: the window extends under the status and navigation bars,
        // so pad the content by the system bar insets (the theme has no action bar).
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (left, top, right, bottom) = systemBarInsets(insets)
            content.setPadding(PADDING + left, PADDING + top, PADDING + right, PADDING + bottom)
            insets
        }

        text.text = "Running WebAssembly components on AOT…"
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

    private fun systemBarInsets(insets: WindowInsets): List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }

    private companion object {
        const val PADDING = 48
    }
}
