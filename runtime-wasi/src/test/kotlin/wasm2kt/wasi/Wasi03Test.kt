package wasm2kt.wasi

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.runtime.Linker
import wasm2kt.runtime.WitWriter
import wasm2kt.wasi.wasi.cli.terminal_input.TerminalInput
import wasm2kt.wasi.wasi.cli.terminal_output.TerminalOutput
import wasm2kt.wasi.wasi.cli.types.ErrorCode
import wasm2kt.wasi.wasi.clocks.system_clock.Instant
import wasm2kt.wasi.wasi.filesystem.types.Advice
import wasm2kt.wasi.wasi.filesystem.types.Descriptor
import wasm2kt.wasi.wasi.filesystem.types.DescriptorFlags
import wasm2kt.wasi.wasi.filesystem.types.DescriptorStat
import wasm2kt.wasi.wasi.filesystem.types.DescriptorType
import wasm2kt.wasi.wasi.filesystem.types.DirectoryEntry
import wasm2kt.wasi.wasi.filesystem.types.ErrorCode as FsErrorCode
import wasm2kt.wasi.wasi.filesystem.types.Filesize
import wasm2kt.wasi.wasi.filesystem.types.MetadataHashValue
import wasm2kt.wasi.wasi.filesystem.types.NewTimestamp
import wasm2kt.wasi.wasi.filesystem.types.OpenFlags
import wasm2kt.wasi.wasi.filesystem.types.PathFlags
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant as JavaInstant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
            "wasi:filesystem/preopens@0.3.0#get-directories",
            "wasi:filesystem/types@0.3.0#[method]descriptor.open-at",
            "wasi:filesystem/types@0.3.0#[method]descriptor.read-via-stream",
            "wasi:filesystem/types@0.3.0#[method]descriptor.write-via-stream",
            "wasi:filesystem/types@0.3.0#[method]descriptor.read-directory",
            "wasi:filesystem/types@0.3.0#[resource-drop]descriptor",
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

    @Test
    fun `default Wasi03 exposes zero preopens`() {
        val wasi = Wasi03.builder().build()
        assertTrue(wasi.preopens.getDirectories().isEmpty())
    }

    @Test
    fun `declarative mounts DSL configures readOnly, readWrite, mount block, and custom descriptor`(@TempDir tempDir: Path) = runBlocking {
        val roDir = Files.createDirectories(tempDir.resolve("ro"))
        val rwDir = Files.createDirectories(tempDir.resolve("rw"))
        val cfgDir = Files.createDirectories(tempDir.resolve("cfg"))
        Files.writeString(roDir.resolve("hello.txt"), "readonly-content")

        val customDescriptor = object : Descriptor {
            override fun readViaStream(offset: Filesize) = Tuple2(
                flowOf("virtual-bytes".encodeToByteArray()),
                CompletableDeferred<WitResult<Unit, FsErrorCode>>(WitResult.Ok(Unit)),
            )
            override fun writeViaStream(data: Flow<ByteArray>, offset: Filesize) =
                CompletableDeferred<WitResult<Unit, FsErrorCode>>(WitResult.Err(FsErrorCode.ReadOnly))
            override fun appendViaStream(data: Flow<ByteArray>) =
                CompletableDeferred<WitResult<Unit, FsErrorCode>>(WitResult.Err(FsErrorCode.ReadOnly))
            override suspend fun advise(offset: Filesize, length: Filesize, advice: Advice) = WitResult.Ok(Unit)
            override suspend fun syncData() = WitResult.Ok(Unit)
            override suspend fun getFlags() = WitResult.Ok(DescriptorFlags.READ)
            override suspend fun getType() = WitResult.Ok(DescriptorType.Directory)
            override suspend fun setSize(size: Filesize) = WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun setTimes(dataAccessTimestamp: NewTimestamp, dataModificationTimestamp: NewTimestamp) =
                WitResult.Err(FsErrorCode.ReadOnly)
            override fun readDirectory() = Tuple2(
                emptyFlow<DirectoryEntry>(),
                CompletableDeferred<WitResult<Unit, FsErrorCode>>(WitResult.Ok(Unit)),
            )
            override suspend fun sync() = WitResult.Ok(Unit)
            override suspend fun createDirectoryAt(path: String) = WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun stat() = WitResult.Err(FsErrorCode.Unsupported)
            override suspend fun statAt(pathFlags: PathFlags, path: String) = WitResult.Err(FsErrorCode.Unsupported)
            override suspend fun setTimesAt(pathFlags: PathFlags, path: String, dataAccessTimestamp: NewTimestamp, dataModificationTimestamp: NewTimestamp) =
                WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun linkAt(oldPathFlags: PathFlags, oldPath: String, newDescriptor: Descriptor, newPath: String) =
                WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun openAt(pathFlags: PathFlags, path: String, openFlags: OpenFlags, flags: DescriptorFlags) =
                WitResult.Err(FsErrorCode.NoEntry)
            override suspend fun readlinkAt(path: String) = WitResult.Err(FsErrorCode.Unsupported)
            override suspend fun removeDirectoryAt(path: String) = WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun renameAt(oldPath: String, newDescriptor: Descriptor, newPath: String) =
                WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun symlinkAt(oldPath: String, newPath: String) = WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun unlinkFileAt(path: String) = WitResult.Err(FsErrorCode.ReadOnly)
            override suspend fun isSameObject(other: Descriptor) = this === other
            override suspend fun metadataHash() = WitResult.Ok(MetadataHashValue(1uL, 2uL))
            override suspend fun metadataHashAt(pathFlags: PathFlags, path: String) = WitResult.Err(FsErrorCode.NoEntry)
            override fun close() {}
        }

        val wasi = Wasi03.builder().apply {
            mounts {
                readOnly("/ro", roDir)
                readWrite("/rw", rwDir)
                mount("/cfg") {
                    hostPath = cfgDir
                    readOnly = true
                }
                custom("/virtual", customDescriptor)
            }
        }.build()

        val dirs = wasi.preopens.getDirectories()
        assertEquals(listOf("/ro", "/rw", "/cfg", "/virtual"), dirs.map { it.f1 })

        val roRoot = dirs[0].f0
        val rwRoot = dirs[1].f0
        val virtualRoot = dirs[3].f0
        try {
            val readFile = roRoot.openAt(PathFlags.NONE, "hello.txt", OpenFlags.NONE, DescriptorFlags.READ).getOrThrow()
            readFile.use { d ->
                val (stream, future) = d.readViaStream(9uL)
                val data = stream.toList().fold(ByteArray(0)) { a, b -> a + b }
                assertEquals(WitResult.Ok(Unit), future.await())
                assertEquals("content", data.decodeToString())
            }
            assertEquals(
                FsErrorCode.ReadOnly,
                roRoot.openAt(PathFlags.NONE, "new.txt", OpenFlags.CREATE, DescriptorFlags.WRITE).errorOrNull(),
            )
            assertEquals(
                FsErrorCode.ReadOnly,
                roRoot.createDirectoryAt("subdir").errorOrNull(),
            )
            val created = rwRoot.openAt(PathFlags.NONE, "out.txt", OpenFlags.CREATE or OpenFlags.TRUNCATE, DescriptorFlags.READ or DescriptorFlags.WRITE).getOrThrow()
            created.use { d ->
                assertEquals(WitResult.Ok(Unit), d.writeViaStream(flowOf("hello".encodeToByteArray()), 0uL).await())
                assertEquals(WitResult.Ok(Unit), d.appendViaStream(flowOf(" world".encodeToByteArray())).await())
            }
            assertEquals("hello world", Files.readString(rwDir.resolve("out.txt")))
            assertSame(customDescriptor, virtualRoot)
            val (vStream, vFuture) = virtualRoot.readViaStream(0uL)
            assertEquals("virtual-bytes", vStream.toList().fold(ByteArray(0)) { a, b -> a + b }.decodeToString())
            assertEquals(WitResult.Ok(Unit), vFuture.await())
        } finally {
            dirs.forEach { it.f0.close() }
        }
    }

    @Test
    fun `WasiPathDescriptor enforces file and directory operations and rejects sandbox escapes`(@TempDir tempDir: Path) = runBlocking {
        val outsideSecret = tempDir.resolve("secret.txt")
        Files.writeString(outsideSecret, "top-secret")
        val sandboxDir = Files.createDirectories(tempDir.resolve("sandbox"))
        Files.createSymbolicLink(sandboxDir.resolve("abs-escape"), outsideSecret.toAbsolutePath())
        Files.createSymbolicLink(sandboxDir.resolve("rel-escape"), Path.of("../secret.txt"))
        Files.createSymbolicLink(sandboxDir.resolve("good-link"), Path.of("sub/note.txt"))

        val root = WasiPathDescriptor.createRoot(sandboxDir, readOnly = false)
        try {
            assertEquals(WitResult.Ok(Unit), root.createDirectoryAt("sub"))
            val note = root.openAt(PathFlags.NONE, "sub/note.txt", OpenFlags.CREATE, DescriptorFlags.READ or DescriptorFlags.WRITE).getOrThrow()
            note.use { f ->
                assertEquals(WitResult.Ok(Unit), f.writeViaStream(flowOf("abcdef".encodeToByteArray()), 0uL).await())
                assertEquals(WitResult.Ok(Unit), f.setSize(3uL))
                val st = f.stat().getOrThrow()
                assertEquals(3uL, st.size)
                assertEquals(DescriptorType.RegularFile, st.type)
                assertEquals(WitResult.Ok(Unit), f.setTimes(NewTimestamp.Now, NewTimestamp.Timestamp(Instant(1_700_000_000L, 0u))))
                assertTrue(f.isSameObject(f))
                assertTrue(f.metadataHash() is WitResult.Ok)
            }

            // Read via valid relative symlink inside sandbox
            val linked = root.openAt(PathFlags.SYMLINK_FOLLOW, "good-link", OpenFlags.NONE, DescriptorFlags.READ).getOrThrow()
            linked.use { f ->
                val (stream, future) = f.readViaStream(0uL)
                assertEquals("abc", stream.toList().fold(ByteArray(0)) { a, b -> a + b }.decodeToString())
                assertEquals(WitResult.Ok(Unit), future.await())
            }
            assertEquals(WitResult.Ok("sub/note.txt"), root.readlinkAt("good-link"))

            // Directory listing
            val (dirStream, dirFuture) = root.readDirectory()
            val names = dirStream.toList().map { it.name }.sorted()
            assertEquals(WitResult.Ok(Unit), dirFuture.await())
            assertEquals(listOf("abs-escape", "good-link", "rel-escape", "sub"), names)

            // Rename & remove operations
            assertEquals(WitResult.Ok(Unit), root.renameAt("sub/note.txt", root, "sub/renamed.txt"))
            assertEquals(WitResult.Ok(Unit), root.unlinkFileAt("sub/renamed.txt"))
            assertEquals(WitResult.Ok(Unit), root.removeDirectoryAt("sub"))

            // Sandbox escapes MUST fail with NotPermitted / Invalid
            assertEquals(FsErrorCode.NotPermitted, root.openAt(PathFlags.NONE, "/etc/passwd", OpenFlags.NONE, DescriptorFlags.READ).errorOrNull())
            assertEquals(FsErrorCode.NotPermitted, root.openAt(PathFlags.NONE, "../secret.txt", OpenFlags.NONE, DescriptorFlags.READ).errorOrNull())
            assertEquals(FsErrorCode.NotPermitted, root.openAt(PathFlags.NONE, "a\\..\\../secret.txt", OpenFlags.NONE, DescriptorFlags.READ).errorOrNull())
            assertEquals(FsErrorCode.Invalid, root.openAt(PathFlags.NONE, "bad\u0000name", OpenFlags.NONE, DescriptorFlags.READ).errorOrNull())
            assertEquals(FsErrorCode.NotPermitted, root.openAt(PathFlags.SYMLINK_FOLLOW, "abs-escape", OpenFlags.NONE, DescriptorFlags.READ).errorOrNull())
            assertEquals(FsErrorCode.NotPermitted, root.openAt(PathFlags.SYMLINK_FOLLOW, "rel-escape", OpenFlags.NONE, DescriptorFlags.READ).errorOrNull())
            assertEquals(FsErrorCode.NotPermitted, root.symlinkAt("/etc/passwd", "bad-link").errorOrNull())
            assertEquals(FsErrorCode.NotPermitted, root.symlinkAt("../secret.txt", "bad-rel-link").errorOrNull())
        } finally {
            root.close()
        }
    }
}
