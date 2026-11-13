package wasm2kt.wasi

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import wasm2kt.runtime.Linker
import wasm2kt.runtime.WitWriter
import wasm2kt.wasi.wasi.cli.terminal_input.TerminalInput
import wasm2kt.wasi.wasi.cli.terminal_output.TerminalOutput
import wasm2kt.wasi.wasi.cli.types.ErrorCode
import wasm2kt.wasi.wasi.clocks.system_clock.Instant
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant as JavaInstant
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Wasi03Test {
    @Test
    fun `random interfaces generate bytes, u64, and seed`() {
        val random = WasiRandom(SecureRandom())
        assertEquals(0, random.getRandomBytes(0uL).size)
        assertEquals(16, random.getRandomBytes(16uL).size)
        assertEquals(65536, random.getRandomBytes(1_000_000uL).size)
        random.getRandomU64()

        val insecure = WasiInsecureRandom(Random(1234L))
        assertEquals(0, insecure.getInsecureRandomBytes(0uL).size)
        assertEquals(8, insecure.getInsecureRandomBytes(8uL).size)
        assertEquals(Random(1234L).nextLong().toULong(), WasiInsecureRandom(Random(1234L)).getInsecureRandomU64())

        val seed = WasiInsecureSeed(1uL to 2uL).getInsecureSeed()
        assertEquals(1uL, seed.f0)
        assertEquals(2uL, seed.f1)
    }

    @Test
    fun `clocks and timezone convert timestamps, offsets, and waits`() = runBlocking {
        var fakeNanos = 1_000_000L
        val mono = WasiMonotonicClock(nanoTime = { fakeNanos }, resolutionNanos = 5uL)
        assertEquals(0uL, mono.now())
        fakeNanos += 250L
        assertEquals(250uL, mono.now())
        assertEquals(5uL, mono.getResolution())
        mono.waitUntil(250uL)
        mono.waitFor(0uL)

        val realMono = WasiMonotonicClock()
        val before = realMono.now()
        realMono.waitFor(5_000_000uL)
        assertTrue(realMono.now() >= before)

        val fixed = Clock.fixed(JavaInstant.ofEpochSecond(1_700_000_000L, 123_456_789L), ZoneOffset.UTC)
        val sys = WasiSystemClock(fixed, resolutionNanos = 1_000uL)
        assertEquals(Instant(1_700_000_000L, 123_456_789u), sys.now())
        assertEquals(1_000uL, sys.getResolution())

        val tokyo = WasiTimezone { ZoneId.of("Asia/Tokyo") }
        assertEquals("Asia/Tokyo", tokyo.ianaId())
        assertEquals(9L * 3600L * 1_000_000_000L, tokyo.utcOffset(Instant(1_700_000_000L, 0u)))
        assertEquals("Asia/Tokyo", tokyo.toDebugString())

        val fixedOffset = WasiTimezone { ZoneOffset.ofHours(-5) }
        assertNull(fixedOffset.ianaId())
        assertEquals(-5L * 3600L * 1_000_000_000L, fixedOffset.utcOffset(Instant(1_700_000_000L, 0u)))
    }

    @Test
    fun `cli environment, exit, stdin, stdout, stderr, and terminals behave as specified`() = runBlocking {
        val env = WasiEnvironment(
            environment = listOf("A" to "1", "B" to "2"),
            arguments = listOf("prog", "--flag"),
            initialCwd = "/work",
        )
        assertEquals(listOf(Tuple2("A", "1"), Tuple2("B", "2")), env.getEnvironment())
        assertEquals(listOf("prog", "--flag"), env.getArguments())
        assertEquals("/work", env.getInitialCwd())

        val exit = WasiExit()
        assertEquals(0, assertFailsWith<WasiExitException> { exit.exit(WitResult.Ok(Unit)) }.statusCode)
        assertEquals(1, assertFailsWith<WasiExitException> { exit.exit(WitResult.Err(Unit)) }.statusCode)
        assertEquals(42, assertFailsWith<WasiExitException> { exit.exitWithCode(42u) }.statusCode)

        val (stdinStream, stdinFuture) = WasiStdin("hello stdin".encodeToByteArray()).readViaStream()
        assertEquals("hello stdin", stdinStream.toList().fold(ByteArray(0)) { acc, chunk -> acc + chunk }.decodeToString())
        assertEquals(WitResult.Ok(Unit), stdinFuture.await())

        val outBuf = ByteArrayOutputStream()
        val stdoutResult = WasiStdout(outBuf).writeViaStream(flowOf("hello ".encodeToByteArray(), "stdout".encodeToByteArray())).await()
        assertEquals(WitResult.Ok(Unit), stdoutResult)
        assertEquals("hello stdout", outBuf.toString(Charsets.UTF_8))

        val errBuf = ByteArrayOutputStream()
        val stderrResult = WasiStderr(errBuf).writeViaStream(flowOf("err".encodeToByteArray())).await()
        assertEquals(WitResult.Ok(Unit), stderrResult)
        assertContentEquals("err".encodeToByteArray(), errBuf.toByteArray())

        val failingOut = object : OutputStream() {
            override fun write(b: Int) = throw IOException("boom")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("boom")
        }
        assertEquals(WitResult.Err(ErrorCode.IO), WasiStdout(failingOut).writeViaStream(flowOf(byteArrayOf(1))).await())

        assertNull(WasiTerminalStdin().getTerminalStdin())
        assertNull(WasiTerminalStdout().getTerminalStdout())
        assertNull(WasiTerminalStderr().getTerminalStderr())
        val ti = object : TerminalInput {}
        val to = object : TerminalOutput {}
        assertSame(ti, WasiTerminalStdin { ti }.getTerminalStdin())
        assertSame(to, WasiTerminalStdout { to }.getTerminalStdout())
        assertSame(to, WasiTerminalStderr { to }.getTerminalStderr())
    }

    @Test
    fun `addWasi03 registers all WASI 0_3 core import keys on Linker`() {
        val linker = Linker().addWasi03 {
            env("FOO" to "bar")
            args("app", "arg1")
            initialCwd = "/tmp"
        }
        val expectedKeys = listOf(
            "wasi:random/random@0.3.0#get-random-bytes",
            "wasi:random/random@0.3.0#get-random-u64",
            "wasi:random/insecure@0.3.0#get-insecure-random-bytes",
            "wasi:random/insecure@0.3.0#get-insecure-random-u64",
            "wasi:random/insecure-seed@0.3.0#get-insecure-seed",
            "wasi:clocks/monotonic-clock@0.3.0#now",
            "wasi:clocks/monotonic-clock@0.3.0#get-resolution",
            "wasi:clocks/monotonic-clock@0.3.0#wait-until",
            "wasi:clocks/monotonic-clock@0.3.0#wait-for",
            "wasi:clocks/system-clock@0.3.0#now",
            "wasi:clocks/system-clock@0.3.0#get-resolution",
            "wasi:clocks/timezone@0.3.0#iana-id",
            "wasi:clocks/timezone@0.3.0#utc-offset",
            "wasi:clocks/timezone@0.3.0#to-debug-string",
            "wasi:cli/environment@0.3.0#get-environment",
            "wasi:cli/environment@0.3.0#get-arguments",
            "wasi:cli/environment@0.3.0#get-initial-cwd",
            "wasi:cli/exit@0.3.0#exit",
            "wasi:cli/exit@0.3.0#exit-with-code",
            "wasi:cli/stdin@0.3.0#read-via-stream",
            "wasi:cli/stdout@0.3.0#write-via-stream",
            "wasi:cli/stderr@0.3.0#write-via-stream",
            "wasi:cli/terminal-input@0.3.0#[resource-drop]terminal-input",
            "wasi:cli/terminal-output@0.3.0#[resource-drop]terminal-output",
            "wasi:cli/terminal-stdin@0.3.0#get-terminal-stdin",
            "wasi:cli/terminal-stdout@0.3.0#get-terminal-stdout",
            "wasi:cli/terminal-stderr@0.3.0#get-terminal-stderr",
        )
        for (key in expectedKeys) {
            assertTrue(linker.isDefined(key), "Missing WASI 0.3 linker key: $key")
        }
        val envReader = linker.call("wasi:cli/environment@0.3.0#get-arguments", WitWriter())
        val count = envReader.length()
        assertEquals(2, count)
        assertEquals("app", envReader.string())
        assertEquals("arg1", envReader.string())
    }
}
