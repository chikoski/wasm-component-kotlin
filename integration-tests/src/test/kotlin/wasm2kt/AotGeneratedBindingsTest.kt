package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.tooling.aot.AotComponentBuild
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * End-to-end World DSL tests driven through the Phase 1 AOT backend (`AotComponentBuild`),
 * exercising arithmetic, guest resources (`counter`), complex WIT value types & host imports
 * (`roundtrip`), host-defined resources (`host-res`), and multi-memory string transcoding
 * (`composed-str`).
 */
class AotGeneratedBindingsTest {
    private fun fixture(name: String): Path =
        Paths.get(requireNotNull(javaClass.getResource("/fixtures/$name.wasm")).toURI())

    private fun buildCompileAndRun(
        tempDir: Path,
        fixtureName: String,
        pkg: String,
        harnessSource: String,
    ): String {
        val output = AotComponentBuild.run(
            component = fixture(fixtureName),
            componentName = fixtureName,
            packageName = pkg,
            workDir = tempDir.resolve("build"),
        )
        val harnessPath = tempDir.resolve("Harness.kt")
        Files.writeString(harnessPath, harnessSource.trimIndent())

        val compiledDir = tempDir.resolve("compiled-kotlin")
        Files.createDirectories(compiledDir)
        val sources = generatedSources(output.kotlinDir) + listOf(harnessPath.toString())
        val classpath = listOf(System.getProperty("java.class.path"), output.classesDir.toString()).joinToString(File.pathSeparator)
        val exit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
            "-classpath", classpath,
            "-d", compiledDir.toString(),
            *sources.toTypedArray(),
        )
        assertEquals(ExitCode.OK, exit, "AOT bindings and harness for $fixtureName must compile")

        val urls = arrayOf(
            compiledDir.toUri().toURL(),
            output.classesDir.toUri().toURL(),
            output.resourcesDir.toUri().toURL(),
        )
        return URLClassLoader(urls, javaClass.classLoader).use { loader ->
            loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("run").invoke(null) as String
        }
    }

    @Test
    fun `arithmetic world runs on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.arithmetic"
        val out = buildCompileAndRun(
            tempDir,
            "arithmetic",
            pkg,
            """
            package $pkg

            fun run(): String =
                withSession(ArithmeticComponent) {
                    "add=${'$'}{add(20, 7)},sub=${'$'}{sub(50, 8)},mul=${'$'}{mul(-6, -7)},div=${'$'}{div(84, 2)}"
                }
            """,
        )
        assertEquals("add=27,sub=42,mul=42,div=42", out)
    }

    @Test
    fun `counter resources run on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.counter"
        val out = buildCompileAndRun(
            tempDir,
            "counter",
            pkg,
            """
            package $pkg

            import $pkg.exports.example.counter.counter_api.Counter

            fun run(): String =
                withSession(CounterComponent) {
                    Counter(10).use { c1 ->
                        c1.increment()
                        c1.increment()
                        c1.add(5)
                        val v1 = c1.getValue()
                        val c2 = Counter(25)
                        c2.increment()
                        Counter.merge(c1, c2).use { merged ->
                            "v1=${'$'}v1,v1After=${'$'}{c1.getValue()},c2Closed=${'$'}{c2.isClosed},merged=${'$'}{merged.getValue()}"
                        }
                    }
                }
            """,
        )
        assertEquals("v1=17,v1After=17,c2Closed=true,merged=43", out)
    }

    @Test
    fun `roundtrip types and host interface imports run on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.roundtrip"
        val out = buildCompileAndRun(
            tempDir,
            "roundtrip",
            pkg,
            """
            package $pkg

            import $pkg.example.roundtrip.env_provider.EnvProvider
            import $pkg.exports.example.roundtrip.math_api.scale

            fun run(): String {
                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )
                return withSession(RoundtripComponent, imports) {
                    val scaled = scale(7)
                    val tag = describeCode("ERR", 404u)
                    val initial = ItemSummary(
                        count = 10u,
                        perms = Permissions.READ or Permissions.EXECUTE,
                        prio = Priority.LOW,
                    )
                    val afterAdd = transformSummary(initial, Action.AddCount(5u))
                    val afterPrio = transformSummary(afterAdd, Action.SetPriority(Priority.HIGH))
                    val afterReset = transformSummary(afterPrio, Action.Reset)
                    check(afterPrio.perms.read && !afterPrio.perms.write && afterPrio.perms.execute)
                    check(afterReset.count == 0u && afterReset.perms == Permissions.NONE && afterReset.prio == Priority.LOW)
                    val even = (checkEven(84) as WitResult.Ok).value.toString() + ":" + (checkEven(-7) as WitResult.Err).error
                    val ping = "${'$'}{pingResult(true) is WitResult.Ok}:${'$'}{pingResult(false) is WitResult.Err}"
                    val optNone = nestedOption(WitOption.None)
                    val optSomeNone = nestedOption(WitOption.Some(null))
                    val optSomeVal = nestedOption(WitOption.Some(23))
                    val opt = listOf(
                        optNone is WitOption.None,
                        optSomeNone is WitOption.Some && optSomeNone.value == null,
                        (optSomeVal as WitOption.Some).value == 123,
                    ).joinToString(",")
                    check(checkEven(84).getOrThrow() == 42u)
                    check(checkEven(-7).isErr && checkEven(84).isOk)
                    val u64 = echoU64(ULong.MAX_VALUE) == ULong.MAX_VALUE
                    "scaled=${'$'}scaled,tag=${'$'}tag,count=${'$'}{afterPrio.count},prio=${'$'}{afterPrio.prio},even=${'$'}even,ping=${'$'}ping,opt=${'$'}opt,u64Max=${'$'}u64"
                }
            }
            """,
        )
        assertEquals("scaled=42,tag=ERR#404,count=15,prio=HIGH,even=42:-7,ping=true:true,opt=true,true,true,u64Max=true", out)
    }

    @Test
    fun `host resources behave identically on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.hostres"
        val out = buildCompileAndRun(
            tempDir,
            "host-res",
            pkg,
            """
            package $pkg

            import $pkg.example.host_res.store.Blob
            import $pkg.example.host_res.store.Store
            import $pkg.exports.example.host_res.guest_api.Counter

            class HostBlob(val name: String, var bytes: ByteArray, private val log: MutableList<String>) : Blob {
                override fun size(): UInt = bytes.size.toUInt()
                override fun append(data: ByteArray) { bytes += data }
                override fun read(): ByteArray = bytes
                override fun close() { log += "close:${'$'}name" }
            }

            class HostTicket(private val value: UInt, private val log: MutableList<String>) : Ticket {
                override fun id(): UInt = value
                override fun close() { log += "close-ticket:${'$'}value" }
            }

            fun run(): String {
                val log = mutableListOf<String>()
                var onLog: (() -> Unit)? = null
                var created = 0
                val store = object : Store {
                    override fun newBlob(init: ByteArray): Blob =
                        HostBlob("g${'$'}{created++}", init, log).also { log += "new:${'$'}{it.name}:${'$'}{init.size}" }
                    override fun blobMerge(a: Blob, b: Blob): Blob {
                        a as HostBlob; b as HostBlob
                        log += "merge:${'$'}{a.name}+${'$'}{b.name}"
                        b.close()
                        return HostBlob("m", a.bytes + b.bytes, log)
                    }
                    override fun log(msg: String) { log += "log:${'$'}msg"; onLog?.invoke() }
                    override fun find(name: String): Blob? =
                        if (name == "none") null else HostBlob("found-${'$'}name", byteArrayOf(1, 2), log)
                }
                val worldImports = object : Imports {
                    override fun newTicket(id: UInt): Ticket = HostTicket(id, log).also { log += "new-ticket:${'$'}id" }
                }
                val imports = imports(exampleHostResStore = { store }, world = { worldImports })
                withSession(HostResComponent, imports) {
                    fun check(label: String, ok: Boolean) { log += if (ok) "ok:${'$'}label" else "FAILED:${'$'}label" }
                    check("makeAndMeasure", makeAndMeasure(byteArrayOf(1, 2), byteArrayOf(3)) == 3u)
                    val kept = keep(byteArrayOf(9, 9, 9)) as HostBlob
                    check("keep", kept.name == "g1" && kept.size() == 3u)
                    check("measure(kept)", measure(kept) == 3u)
                    check("consume(kept)", consume(kept) == 3u)
                    val local = HostBlob("local", byteArrayOf(1), log)
                    check("measure(local)", measure(local) == 1u)
                    check("measure(local) again", measure(local) == 1u)
                    check("consume(local)", consume(local) == 1u)
                    check("mergeNew", mergeNew(byteArrayOf(1), byteArrayOf(2, 3)).contentEquals(byteArrayOf(1, 2, 3)))
                    val found = lookup("x") as HostBlob?
                    check("lookup", found?.name == "found-x")
                    check("lookup(none)", lookup("none") == null)
                    val e = HostBlob("e", byteArrayOf(5), log)
                    check("echoBlob(ok)", echoBlob(e).getOrNull() === e)
                    check("echoBlob(err)", echoBlob(HostBlob("z", ByteArray(0), log)).errorOrNull() == "empty")
                    check("ticketId", ticketId(7u) == 7u)
                    val counter = Counter(5)
                    onLog = { log += if (runCatching { counter.close() }.isFailure) "drop-in-call:failed" else "drop-in-call:ok" }
                    counter.poke()
                    onLog = null
                }
                return log.joinToString(",")
            }
            """,
        )
        val expected = listOf(
            "new:g0:2", "close:g0", "ok:makeAndMeasure",
            "new:g1:3", "ok:keep", "ok:measure(kept)", "close:g1", "ok:consume(kept)",
            "ok:measure(local)", "ok:measure(local) again", "close:local", "ok:consume(local)",
            "new:g2:1", "new:g3:2", "merge:g2+g3", "close:g3", "close:m", "close:g2", "ok:mergeNew",
            "ok:lookup", "ok:lookup(none)", "ok:echoBlob(ok)", "close:z", "ok:echoBlob(err)",
            "new-ticket:7", "close-ticket:7", "ok:ticketId",
            "log:poke", "drop-in-call:failed",
        ).joinToString(",")
        assertEquals(expected, out)
    }

    @Test
    fun `composed string transcoders run on AOT World DSL`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.composedstr"
        val out = buildCompileAndRun(
            tempDir,
            "composed-str",
            pkg,
            """
            package $pkg

            fun run(): String =
                withSession(ComposedStrComponent) {
                    val sample = "hello-aé日🎌".repeat(25)
                    val list = listOf(10u, 20u, 30u, 42u)
                    val echoed = listOf(
                        echoUtf8Utf8(sample),
                        echoUtf8Utf16(sample),
                        echoUtf8Compact(sample),
                        echoUtf16Utf8(sample),
                        echoUtf16Utf16(sample),
                        echoUtf16Compact(sample),
                        echoCompactUtf8(sample),
                        echoCompactUtf16(sample),
                        echoCompactCompact(sample),
                    ).all { it == sample }
                    "${'$'}echoed,${'$'}{sum(list)},${'$'}{echoList(list)}"
                }
            """,
        )
        assertEquals("true,102,[10, 20, 30, 42]", out)
    }

    @Test
    fun `ADR-0014 mapped external library interfaces run seamlessly on Phase 2 AOT`(@TempDir tempDir: Path) {
        val wasmToolsBin = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
            ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
            ?: "wasm-tools"
        val libPkg = "com.example.aot.mappedlib"
        val appPkg = "com.example.aot.mappedapp"
        val sameNameWit = Paths.get(requireNotNull(javaClass.getResource("/fixtures/same-name.wit")).toURI())

        val libSrc = tempDir.resolve("lib-src")
        val libClasses = tempDir.resolve("lib-classes")
        wasm2kt.cli.GenerateCommand().parse(
            listOf("--wasm-tools-path", wasmToolsBin, "--interfaces-only", "-o", libSrc.toString(), "--resources-output", libClasses.toString(), "-p", libPkg, sameNameWit.toString()),
        )
        val libPkgDir = libSrc.resolve(libPkg.replace('.', '/'))
        Files.writeString(
            libPkgDir.resolve("Hosts.kt"),
            """
            package $libPkg

            import $libPkg.example.fs.types.ErrorCode as FsErrorCode
            import $libPkg.example.fs.types.Types as FsTypes
            import $libPkg.example.http.types.ErrorCode as HttpErrorCode
            import $libPkg.example.http.types.Tally
            import $libPkg.example.http.types.Types as HttpTypes

            class CountingTally(private var value: UInt) : Tally {
                override fun get(): UInt { value += 10u; return value }
                override fun close() {}
            }
            class LibraryHttpTypes : HttpTypes {
                override fun newTally(start: UInt): Tally = CountingTally(start)
                override fun describe(e: HttpErrorCode): String = "host-http:${'$'}{e.code}"
            }
            class LibraryFsTypes : FsTypes {
                override fun describe(e: FsErrorCode): String = when (e) {
                    is FsErrorCode.Missing -> "host-fs:missing:${'$'}{e.value}"
                    FsErrorCode.Denied -> "host-fs:denied"
                }
            }
            """.trimIndent(),
        )
        val libSources = generatedSources(libSrc)
        val libCp = System.getProperty("java.class.path")
        Files.createDirectories(libClasses)
        val libExit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
            "-classpath", libCp,
            "-d", libClasses.toString(),
            *libSources.toTypedArray(),
        )
        assertEquals(ExitCode.OK, libExit, "Library sources must compile")

        val aotOut = AotComponentBuild.run(
            component = fixture("same-name"),
            componentName = "same-name",
            packageName = appPkg,
            workDir = tempDir.resolve("app-aot"),
            libraryClasspath = listOf(libClasses),
        )
        val harnessPath = tempDir.resolve("MappedHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package $appPkg

            import $libPkg.LibraryFsTypes
            import $libPkg.LibraryHttpTypes
            import $libPkg.example.fs.types.add as addFs
            import $libPkg.example.http.types.add as addHttp
            import $appPkg.exports.example.fs.types.ErrorCode as GuestFsErrorCode
            import $appPkg.exports.example.fs.types.describe as describeFs
            import $appPkg.exports.example.http.types.ErrorCode as GuestHttpErrorCode
            import $appPkg.exports.example.http.types.Tally as GuestTally
            import $appPkg.exports.example.http.types.describe as describeHttp

            context(_: Session)
            private fun exercise(): String {
                val run = run(7u)
                val http = describeHttp(GuestHttpErrorCode(3u))
                val fs = describeFs(GuestFsErrorCode.Missing("x")) + "," + describeFs(GuestFsErrorCode.Denied)
                val tally = GuestTally(5u).use { it.get(); it.get() }
                return "${'$'}run;${'$'}http;${'$'}fs;tally=${'$'}tally"
            }

            fun run(): String {
                val directImports = imports(
                    exampleHttpTypes = { LibraryHttpTypes() },
                    exampleFsTypes = { LibraryFsTypes() },
                )
                val directOut = withSession(SameNameComponent, directImports) {
                    exercise()
                }
                val baseFactory = wasm2kt.runtime.ImportsFactory {
                    wasm2kt.runtime.Linker().apply {
                        addHttp(LibraryHttpTypes())
                        addFs(LibraryFsTypes())
                    }
                }
                val viaBaseOut = withSession(SameNameComponent, imports(base = baseFactory)) {
                    exercise()
                }
                check(directOut == viaBaseOut) { "Mismatch: ${'$'}directOut vs ${'$'}viaBaseOut" }
                return directOut
            }
            """.trimIndent(),
        )
        val appClasses = tempDir.resolve("app-classes")
        Files.createDirectories(appClasses)
        val appCp = listOf(
            System.getProperty("java.class.path"),
            libClasses.toString(),
            aotOut.classesDir.toString(),
        ).joinToString(File.pathSeparator)
        val appExit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
            "-classpath", appCp,
            "-d", appClasses.toString(),
            *(generatedSources(aotOut.kotlinDir) + harnessPath.toString()).toTypedArray(),
        )
        assertEquals(ExitCode.OK, appExit, "Mapped Phase 2 AOT app must compile")

        val urls = arrayOf(
            appClasses.toUri().toURL(),
            libClasses.toUri().toURL(),
            aotOut.classesDir.toUri().toURL(),
            aotOut.resourcesDir.toUri().toURL(),
        )
        val result = URLClassLoader(urls, javaClass.classLoader).use { loader ->
            loader.loadClass("$appPkg.MappedHarnessKt").getDeclaredMethod("run").invoke(null) as String
        }
        assertEquals(
            "host-http:7|host-fs:missing:f7|host-fs:denied|tally=17;guest-http:3;guest-fs:missing:x,guest-fs:denied;tally=7",
            result,
        )
    }

    @Test
    fun `Phase 2 AOT enforces exception, poison, rollback, and reentrancy boundaries`(@TempDir tempDir: Path) {
        val pkgAbi = "com.example.aot.bindings.abiedge"
        val abiOut = buildCompileAndRun(
            tempDir.resolve("abi"),
            "abi-edge",
            pkgAbi,
            """
            package $pkgAbi

            import wasm2kt.runtime.ComponentTrapException

            fun run(): String {
                var reentrantSession: Session? = null
                var innerReentrantEx: Throwable? = null
                val checks = mutableListOf<String>()

                fun expectTrappedAndPoisoned(label: String, block: context(Session) () -> Unit) {
                    withSession(AbiEdgeComponent, imports(world = { object : Imports { override fun ping() {} } })) {
                        val ex = kotlin.runCatching { block() }.exceptionOrNull()
                        val after = kotlin.runCatching { charFrom(0x41u) }.exceptionOrNull()
                        check(ex is ComponentTrapException) { "${'$'}label: expected ComponentTrapException, got ${'$'}ex" }
                        check(after is ComponentTrapException) { "${'$'}label: expected poison after trap, got ${'$'}after" }
                        checks += "${'$'}label=true"
                    }
                }

                // 1. Guest lift traps (invalid scalar, invalid discriminants, OOB/bad UTF-8/UTF-16, bad realloc, may_leave) -> poison
                expectTrappedAndPoisoned("charTrap") { charFrom(0xD800u) }
                expectTrappedAndPoisoned("optionDiscTrap") { optionFrom(2u) }
                expectTrappedAndPoisoned("resultDiscTrap") { resultFrom(2u) }
                expectTrappedAndPoisoned("oobStringTrap") { stringAt(0u, 1000000u) }
                expectTrappedAndPoisoned("badUtf8Trap") { stringAt(0x100u, 3u) }
                expectTrappedAndPoisoned("badUtf16Trap") { utf16At(0x310u, 2u) }
                expectTrappedAndPoisoned("reallocAlignTrap") {
                    setMode(1u)
                    listLen(listOf(1u, 2u))
                }
                expectTrappedAndPoisoned("reallocOobTrap") {
                    setMode(2u)
                    listLen(listOf(1u, 2u))
                }
                expectTrappedAndPoisoned("mayLeaveTrap") {
                    setMode(3u)
                    listLen(listOf(1u, 2u))
                }

                // 2. Reentrant call during host import traps directly AND poisons
                openSession(
                    AbiEdgeComponent,
                    imports(world = {
                        object : Imports {
                            override fun ping() {
                                innerReentrantEx = reentrantSession?.let { s ->
                                    kotlin.runCatching { context(s) { charFrom(0x41u) } }.exceptionOrNull()
                                }
                            }
                        }
                    }),
                ).use { session ->
                    reentrantSession = session
                    context(session) {
                        val outer = kotlin.runCatching { callPing() }.exceptionOrNull()
                        check(innerReentrantEx is ComponentTrapException) { "Expected direct ComponentTrapException inside reentrant ping, got ${'$'}innerReentrantEx" }
                        check(outer == null) { "Expected caught inner trap not to escape ping, got ${'$'}outer" }
                        val after = kotlin.runCatching { charFrom(0x41u) }.exceptionOrNull()
                        check(after is ComponentTrapException) { "Expected poison after reentrant trap, got ${'$'}after" }
                        checks += "reentrant=true"
                    }
                }

                // 3. Host import exception preserves original type AND poisons
                reentrantSession = null
                withSession(
                    AbiEdgeComponent,
                    imports(world = {
                        object : Imports {
                            override fun ping() {
                                throw UnsupportedOperationException("host-boom")
                            }
                        }
                    }),
                ) {
                    val e = kotlin.runCatching { callPing() }.exceptionOrNull()
                    check(e is UnsupportedOperationException && e.message == "host-boom") { "Host exception altered: ${'$'}e" }
                    val after = kotlin.runCatching { charFrom(0x41u) }.exceptionOrNull()
                    check(after is ComponentTrapException) { "Expected poison after host exception, got ${'$'}after" }
                    checks += "hostExPreserved=true"
                }

                return checks.joinToString(",")
            }
            """,
        )
        assertEquals(
            "charTrap=true,optionDiscTrap=true,resultDiscTrap=true,oobStringTrap=true,badUtf8Trap=true,badUtf16Trap=true,reallocAlignTrap=true,reallocOobTrap=true,mayLeaveTrap=true,reentrant=true,hostExPreserved=true",
            abiOut,
        )

        // 4. Host invalid values (surrogates in strings) rejected with IAE WITHOUT poisoning
        val pkgTypes = "com.example.aot.bindings.types"
        val typesOut = buildCompileAndRun(
            tempDir.resolve("types"),
            "types",
            pkgTypes,
            """
            package $pkgTypes

            fun run(): String {
                withSession(TypesComponent) {
                    val badHigh = kotlin.runCatching { echoName("\uD800") }.exceptionOrNull()
                    check(badHigh is IllegalArgumentException) { "Expected IAE for lone high surrogate, got ${'$'}badHigh" }
                    val badLow = kotlin.runCatching { echoName("a\uDC00") }.exceptionOrNull()
                    check(badLow is IllegalArgumentException) { "Expected IAE for lone low surrogate, got ${'$'}badLow" }
                    // Session remains completely usable!
                    check(echoName("ok") == "ok")
                    val flags = echoFlagged(Flagged("k", 9u, "v"))
                    check(flags == Flagged("k", 9u, "v"))
                    val astral = CodePoint.of("\uD83D\uDE00")
                    check(echoChar(astral) == astral)
                }
                return "ok"
            }
            """,
        )
        assertEquals("ok", typesOut)

        // 5. host-res: closed guest resource throws ISE without poisoning; borrowed vs unborrowed drop-in-call boundaries
        val pkgHostRes = "com.example.aot.bindings.hostres_edge"
        val hrOut = buildCompileAndRun(
            tempDir.resolve("hostres_edge"),
            "host-res",
            pkgHostRes,
            """
            package $pkgHostRes

            import $pkgHostRes.exports.example.host_res.guest_api.Counter
            import $pkgHostRes.example.host_res.store.Blob
            import $pkgHostRes.example.host_res.store.Store
            import wasm2kt.runtime.ComponentTrapException

            class MemBlob(var data: ByteArray) : Blob {
                override fun size(): UInt = data.size.toUInt()
                override fun append(more: ByteArray) { data += more }
                override fun read(): ByteArray = data
            }
            class SimpleTicket(private val id: UInt) : Ticket {
                override fun id(): UInt = id
            }

            fun run(): String {
                var onLog: (() -> Unit)? = null
                val imp = imports(
                    exampleHostResStore = {
                        object : Store {
                            override fun newBlob(init: ByteArray): Blob = MemBlob(init)
                            override fun blobMerge(a: Blob, b: Blob): Blob = MemBlob((a as MemBlob).data + (b as MemBlob).data)
                            override fun log(msg: String) { onLog?.invoke() }
                            override fun find(name: String): Blob? = null
                        }
                    },
                    world = { object : Imports { override fun newTicket(id: UInt) = SimpleTicket(id) } },
                )
                withSession(HostResComponent, imp) {
                    val b = MemBlob(byteArrayOf(1, 2, 3))
                    check(consume(b) == 3u)
                    // Closed guest resource throws IllegalStateException WITHOUT poisoning!
                    val c0 = Counter(10)
                    c0.close()
                    val useClosedErr = kotlin.runCatching { c0.value() }.exceptionOrNull()
                    check(useClosedErr is IllegalStateException) { "Expected ISE on closed Counter, got ${'$'}useClosedErr" }
                    val cFresh = Counter(7)
                    check(cFresh.value() == 7)
                    cFresh.close()

                    // Dropping currently borrowed guest resource mid-poke throws ISE WITHOUT poisoning;
                    // dropping an unborrowed guest resource with a destructor mid-poke traps & poisons.
                    val c = Counter(5)
                    val cUnborrowed = Counter(8)
                    var borrowedDropErr: Throwable? = null
                    var unborrowedDropErr: Throwable? = null
                    onLog = {
                        borrowedDropErr = kotlin.runCatching { c.close() }.exceptionOrNull()
                        unborrowedDropErr = kotlin.runCatching { cUnborrowed.close() }.exceptionOrNull()
                    }
                    kotlin.runCatching { c.poke() }
                    onLog = null
                    check(borrowedDropErr is IllegalStateException) { "Expected ISE on borrowed drop-in-call, got ${'$'}borrowedDropErr" }
                    check(unborrowedDropErr is ComponentTrapException) { "Expected ComponentTrapException on unborrowed dtor drop-in-call, got ${'$'}unborrowedDropErr" }
                    val afterDropTrap = kotlin.runCatching { Counter(1) }.exceptionOrNull()
                    check(afterDropTrap is ComponentTrapException) { "Expected poison after dtor drop-in-call trap, got ${'$'}afterDropTrap" }
                }
                return "ok"
            }
            """,
        )
        assertEquals("ok", hrOut)

        val pkgRes = "com.example.aot.bindings.resedge"
        val resOut = buildCompileAndRun(
            tempDir.resolve("resedge"),
            "res-edge",
            pkgRes,
            """
            package $pkgRes

            import $pkgRes.exports.example.res_edge.api.*

            fun run(): String {
                openSession(ResEdgeComponent).use { s1 ->
                    openSession(ResEdgeComponent).use { s2 ->
                        val c1 = context(s1) { make(11u) }
                        val c2 = context(s2) { make(22u) }
                        // Cross-session rejected BEFORE guest call, c1 stays usable
                        val cross = kotlin.runCatching { context(s2) { peek(c1) } }.exceptionOrNull()
                        check(cross is IllegalStateException) { "Expected cross-session ISE, got ${'$'}cross" }
                        check(context(s1) { peek(c1) } == 11u)
                        // Passing same owned resource twice is rejected without poisoning, c1 stays usable
                        val dbl = kotlin.runCatching { context(s1) { take2(c1, c1) } }.exceptionOrNull()
                        check(dbl is IllegalStateException) { "Expected double-move ISE, got ${'$'}dbl" }
                        check(context(s1) { peek(c1) } == 11u)
                        val h1 = context(s1) { take(c1) }
                        check(context(s1) { repRaw(h1) } == 11u)
                        check(c1.isClosed)
                        val h2 = context(s2) { take(c2) }
                        check(context(s2) { repRaw(h2) } == 22u)
                    }
                }
                return "ok"
            }
            """,
        )
        assertEquals("ok", resOut)
    }
}
