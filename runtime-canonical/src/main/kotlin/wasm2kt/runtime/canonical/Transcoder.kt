@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

import java.io.ByteArrayOutputStream

/** A string transcode a fused adapter performs, named as in wasmtime-environ (`Transcode::desc`). */
@InternalWasm2KtApi
enum class TranscodeOp(val desc: String) {
    UTF8_TO_UTF8("utf8-to-utf8"),
    UTF16_TO_UTF16("utf16-to-utf16"),
    LATIN1_TO_LATIN1("latin1-to-latin1"),
    LATIN1_TO_UTF16("latin1-to-utf16"),
    LATIN1_TO_UTF8("latin1-to-utf8"),
    UTF16_TO_COMPACT_PROBABLY_UTF16("utf16-to-compact-probably-utf16"),
    UTF16_TO_COMPACT_UTF16("utf16-to-compact-utf16"),
    UTF16_TO_LATIN1("utf16-to-latin1"),
    UTF16_TO_UTF8("utf16-to-utf8"),
    UTF8_TO_COMPACT_UTF16("utf8-to-compact-utf16"),
    UTF8_TO_LATIN1("utf8-to-latin1"),
    UTF8_TO_UTF16("utf8-to-utf16"),
    ;

    companion object {
        fun fromPlan(desc: String): TranscodeOp? = values().firstOrNull { it.desc == desc }
    }
}

/**
 * The string transcoders that wasmtime-environ's fused adapters call to copy a string from one
 * child component's linear memory to another's (#47), with the same semantics as Wasmtime 48's
 * libcalls (`wasmtime/src/runtime/vm/component/libcalls.rs`): arguments and results, the partial
 * transcodes that report how much was read and written, and traps on invalid encodings.
 *
 * Pointers and lengths are unsigned 32-bit values. The adapter has already checked that each
 * buffer is aligned and inside its memory; an access outside it still traps here.
 */
@InternalWasm2KtApi
class Transcoder(private val op: TranscodeOp, private val from: CanonicalMemory, private val to: CanonicalMemory) {
    /** Runs the transcode on the core arguments and returns its core results. */
    fun apply(a: LongArray): LongArray? = when (op) {
        TranscodeOp.UTF8_TO_UTF8 -> {
            val len = u(a[1])
            val bytes = read(from, u(a[0]), len)
            checkUtf8(bytes)
            write(to, u(a[2]), bytes)
            null
        }
        TranscodeOp.UTF16_TO_UTF16 -> {
            val units = readUtf16(u(a[0]), u(a[1]))
            checkUtf16(units)
            writeUtf16(u(a[2]), units)
            null
        }
        TranscodeOp.LATIN1_TO_LATIN1 -> {
            write(to, u(a[2]), read(from, u(a[0]), u(a[1])))
            null
        }
        TranscodeOp.LATIN1_TO_UTF16 -> {
            val bytes = read(from, u(a[0]), u(a[1]))
            writeUtf16(u(a[2]), CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
            null
        }
        TranscodeOp.UTF8_TO_UTF16 -> {
            // The destination has room for len units; returns how many were written.
            val text = decodeUtf8(read(from, u(a[0]), u(a[1])))
            writeUtf16(u(a[2]), text.toCharArray())
            longArrayOf(text.length.toLong())
        }
        TranscodeOp.UTF16_TO_UTF8 -> utf16ToUtf8(u(a[0]), u(a[1]), u(a[2]), u(a[3]), firstPass = a[4].toInt() != 0)
        TranscodeOp.LATIN1_TO_UTF8 -> latin1ToUtf8(u(a[0]), u(a[1]), u(a[2]), u(a[3]), firstPass = a[4].toInt() != 0)
        TranscodeOp.UTF16_TO_COMPACT_PROBABLY_UTF16 -> {
            val len = u(a[1])
            val units = readUtf16(u(a[0]), len)
            checkUtf16(units)
            if (units.all { it.code <= 0xFF }) {
                write(to, u(a[2]), ByteArray(units.size) { units[it].code.toByte() })
                longArrayOf(len)
            } else {
                writeUtf16(u(a[2]), units)
                longArrayOf(len or UTF16_TAG)
            }
        }
        TranscodeOp.UTF8_TO_LATIN1 -> {
            // The longest prefix that is valid UTF-8 made only of code points up to U+00FF.
            val bytes = read(from, u(a[0]), u(a[1]))
            val latin1 = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                if (b0 < 0x80) {
                    latin1.append(b0.toChar()); i += 1
                } else if ((b0 == 0xC2 || b0 == 0xC3) && i + 1 < bytes.size && (bytes[i + 1].toInt() and 0xC0) == 0x80) {
                    latin1.append((((b0 and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar()); i += 2
                } else {
                    break
                }
            }
            write(to, u(a[2]), ByteArray(latin1.length) { latin1[it].code.toByte() })
            longArrayOf(i.toLong(), latin1.length.toLong())
        }
        TranscodeOp.UTF16_TO_LATIN1 -> {
            // Stops at the first code unit above U+00FF.
            val units = readUtf16(u(a[0]), u(a[1]))
            val n = units.indexOfFirst { it.code > 0xFF }.let { if (it < 0) units.size else it }
            write(to, u(a[2]), ByteArray(n) { units[it].code.toByte() })
            longArrayOf(n.toLong(), n.toLong())
        }
        TranscodeOp.UTF8_TO_COMPACT_UTF16 -> {
            // Inflate the latin1 bytes already written, then transcode the rest of the source after them.
            val dst = u(a[2])
            val latin1 = u(a[4])
            inflateLatin1(dst, latin1)
            // Like Wasmtime, only as many units as fit in the rest of the destination are written.
            val units = decodeUtf8(read(from, u(a[0]), u(a[1]))).toCharArray()
            val written = minOf(units.size.toLong(), u(a[3]) - latin1).toInt()
            writeUtf16(dst + 2 * latin1, units.copyOf(written))
            longArrayOf(written + latin1)
        }
        TranscodeOp.UTF16_TO_COMPACT_UTF16 -> {
            val dst = u(a[2])
            val latin1 = u(a[4])
            inflateLatin1(dst, latin1)
            val units = readUtf16(u(a[0]), u(a[1]))
            checkUtf16(units)
            writeUtf16(dst + 2 * latin1, units)
            longArrayOf(units.size + latin1)
        }
    }

    /** Partial transcode: stops before a character that does not fit, or (first pass) at the first non-ASCII one. */
    private fun utf16ToUtf8(src: Long, srcLen: Long, dst: Long, dstLen: Long, firstPass: Boolean): LongArray {
        val units = readUtf16(src, srcLen)
        val out = ByteArrayOutputStream()
        var read = 0
        var i = 0
        while (i < units.size) {
            val c = units[i]
            val cp: Int
            val width: Int
            if (Character.isHighSurrogate(c) && i + 1 < units.size && Character.isLowSurrogate(units[i + 1])) {
                cp = Character.toCodePoint(c, units[i + 1]); width = 2
            } else if (Character.isSurrogate(c)) {
                trap("invalid utf16 encoding")
            } else {
                cp = c.code; width = 1
            }
            if (firstPass && cp >= 0x80) break
            val utf8Len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            val remaining = dstLen - out.size()
            if (remaining < 4 && remaining < utf8Len) break
            when (utf8Len) {
                1 -> out.write(cp)
                2 -> {
                    out.write(0xC0 or (cp shr 6))
                    out.write(0x80 or (cp and 0x3F))
                }
                3 -> {
                    out.write(0xE0 or (cp shr 12))
                    out.write(0x80 or ((cp shr 6) and 0x3F))
                    out.write(0x80 or (cp and 0x3F))
                }
                else -> {
                    out.write(0xF0 or (cp shr 18))
                    out.write(0x80 or ((cp shr 12) and 0x3F))
                    out.write(0x80 or ((cp shr 6) and 0x3F))
                    out.write(0x80 or (cp and 0x3F))
                }
            }
            i += width
            read = i
        }
        write(to, dst, out.toByteArray())
        return longArrayOf(read.toLong(), out.size().toLong())
    }

    private fun latin1ToUtf8(src: Long, srcLen: Long, dst: Long, dstLen: Long, firstPass: Boolean): LongArray {
        val bytes = read(from, src, srcLen)
        val stop = if (firstPass) bytes.indexOfFirst { (it.toInt() and 0xFF) >= 0x80 }.let { if (it < 0) bytes.size else it } else bytes.size
        val out = ByteArrayOutputStream()
        var read = 0
        while (read < stop) {
            val b = bytes[read].toInt() and 0xFF
            val width = if (b < 0x80) 1 else 2
            if (dstLen - out.size() < width) break
            if (b < 0x80) out.write(b) else { out.write(0xC0 or (b shr 6)); out.write(0x80 or (b and 0x3F)) }
            read++
        }
        write(to, dst, out.toByteArray())
        return longArrayOf(read.toLong(), out.size().toLong())
    }

    /** Widens the first [count] bytes at [dst] in the destination into UTF-16 code units, in place. */
    private fun inflateLatin1(dst: Long, count: Long) {
        val bytes = read(to, dst, count)
        writeUtf16(dst, CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
    }

    private fun readUtf16(ptr: Long, units: Long): CharArray {
        val bytes = read(from, ptr, 2 * units)
        return CharArray(units.toInt()) { ((bytes[2 * it].toInt() and 0xFF) or ((bytes[2 * it + 1].toInt() and 0xFF) shl 8)).toChar() }
    }

    private fun writeUtf16(ptr: Long, units: CharArray) {
        val bytes = ByteArray(2 * units.size)
        units.forEachIndexed { i, c -> bytes[2 * i] = c.code.toByte(); bytes[2 * i + 1] = (c.code shr 8).toByte() }
        write(to, ptr, bytes)
    }

    private fun read(memory: CanonicalMemory, ptr: Long, len: Long): ByteArray {
        checkBounds(memory, ptr, len)
        return if (len == 0L) ByteArray(0) else memory.readBytes(ptr.toInt(), len.toInt())
    }

    private fun write(memory: CanonicalMemory, ptr: Long, bytes: ByteArray) {
        checkBounds(memory, ptr, bytes.size.toLong())
        if (bytes.isNotEmpty()) memory.writeBytes(ptr.toInt(), bytes)
    }

    private fun checkBounds(memory: CanonicalMemory, ptr: Long, len: Long) {
        val size = memory.byteSize
        if (ptr + len > size) trap("${op.desc}: [$ptr, ${ptr + len}) is outside linear memory of $size bytes")
    }

    private fun checkUtf16(units: CharArray) {
        if (!Utf8.isValidUtf16(units)) trap("invalid utf16 encoding")
    }

    private fun checkUtf8(bytes: ByteArray) {
        if (!Utf8.isValid(bytes, 0, bytes.size)) trap("invalid utf8 encoding")
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        checkUtf8(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun u(v: Long): Long = v and 0xFFFF_FFFFL

    private companion object {
        const val UTF16_TAG = 1L shl 31
    }
}
