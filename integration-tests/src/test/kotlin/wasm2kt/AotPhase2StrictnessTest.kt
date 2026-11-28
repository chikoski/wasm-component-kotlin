package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.tooling.aot.AotComponentBuild
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * Canonical ABI strictness (ADR-0007), synchronous resource lifetime (ADR-0008),
 * `LinearMemory` bulk boundaries, and ADR-0014 mapped import bridge checks running directly
 * through the Phase 2 static AOT World DSL (`DirectCanonicalAbi` + `<Name>Component`).
 */
class AotPhase2StrictnessTest {
    private fun fixture(name: String): Path =
        Paths.get(requireNotNull(javaClass.getResource("/fixtures/$name.wasm")).toURI())

    private fun buildCompileAndRun(
        tempDir: Path,
        fixtureName: String,
        pkg: String,
        harnessSource: String,
        libraryClasspath: List<Path> = emptyList(),
    ): String {
        val output = AotComponentBuild.run(
            component = fixture(fixtureName),
            componentName = fixtureName,
            packageName = pkg,
            workDir = tempDir.resolve("build-$fixtureName"),
            libraryClasspath = libraryClasspath,
        )
        val safeName = fixtureName.replace('-', '_')
        val harnessPath = tempDir.resolve("Harness_$safeName.kt")
        Files.writeString(harnessPath, harnessSource.trimIndent())

        val compiledDir = tempDir.resolve("compiled-kotlin-$fixtureName")
        Files.createDirectories(compiledDir)
        val sources = generatedSources(output.kotlinDir) + listOf(harnessPath.toString())
        val cpEntries = listOf(System.getProperty("java.class.path"), output.classesDir.toString()) +
            libraryClasspath.map { it.toString() }
        val exit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
            "-classpath", cpEntries.joinToString(File.pathSeparator),
            "-d", compiledDir.toString(),
            *sources.toTypedArray(),
        )
        assertEquals(ExitCode.OK, exit, "Phase 2 strictness bindings and harness for $fixtureName must compile")

        val urls = (
            listOf(compiledDir, output.classesDir, output.resourcesDir) + libraryClasspath
            ).map { it.toUri().toURL() }.toTypedArray()
        return URLClassLoader(urls, javaClass.classLoader).use { loader ->
            loader.loadClass("$pkg.Harness_${safeName}Kt").getDeclaredMethod("run").invoke(null) as String
        }
    }

    @Test
    fun `Phase 2 abi-edge traps on bad char, enforces may_enter re-entry, and preserves host exceptions`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.p2strict.abiedge"
        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "abi-edge",
            pkg = pkg,
            harnessSource = """
            package $pkg

            import wasm2kt.runtime.ComponentTrapException

            fun run(): String {
                // 1. Guest lifting invalid char traps and poisons instance.
                val s1 = openSession(AbiEdgeComponent, imports(world = { object : Imports { override fun ping() {} } }))
                s1.use { sess ->
                    var liftTrap = false
                    var unusableAfter = false
                    context(sess) {
                        try {
                            charFrom(0xD800u)
                        } catch (e: ComponentTrapException) {
                            liftTrap = true
                        }
                        try {
                            charFrom(0x41u)
                        } catch (e: ComponentTrapException) {
                            unusableAfter = "unusable" in (e.message ?: "")
                        }
                    }
                    check(liftTrap && unusableAfter) { "liftTrap=${'$'}liftTrap, unusableAfter=${'$'}unusableAfter" }
                }

                // 1b. Guest lifting invalid UTF-8 string traps and poisons instance.
                val s1b = openSession(AbiEdgeComponent, imports(world = { object : Imports { override fun ping() {} } }))
                s1b.use { sess ->
                    var utf8Trap = false
                    var unusableAfter = false
                    context(sess) {
                        try {
                            stringAt(0x100u, 3u)
                        } catch (_: ComponentTrapException) {
                            utf8Trap = true
                        }
                        try {
                            charFrom(0x41u)
                        } catch (e: ComponentTrapException) {
                            unusableAfter = "unusable" in (e.message ?: "")
                        }
                    }
                    check(utf8Trap && unusableAfter) { "utf8Trap=${'$'}utf8Trap, unusableAfter=${'$'}unusableAfter" }
                }

                // 2. Re-entry during direct Phase 2 host import traps (may_enter) and poisons instance.
                lateinit var reenterSess: Session
                var reenterErr: Throwable? = null
                reenterSess = openSession(AbiEdgeComponent, imports(world = {
                    object : Imports {
                        override fun ping() {
                            context(reenterSess) {
                                try {
                                    charFrom(0x41u)
                                } catch (t: Throwable) {
                                    reenterErr = t
                                }
                            }
                        }
                    }
                }))
                reenterSess.use { sess ->
                    context(sess) {
                        callPing()
                        val err = reenterErr
                        check(err is ComponentTrapException && "reenter" in (err.message ?: "")) { "Unexpected reenter error: ${'$'}err" }
                        var unusableAfter = false
                        try {
                            charFrom(0x41u)
                        } catch (_: ComponentTrapException) {
                            unusableAfter = true
                        }
                        check(unusableAfter) { "Session must be poisoned after re-entry trap" }
                    }
                }

                // 3. Host import exception preserves original type and poisons instance.
                val s3 = openSession(AbiEdgeComponent, imports(world = {
                    object : Imports {
                        override fun ping() {
                            throw UnsupportedOperationException("boom")
                        }
                    }
                }))
                s3.use { sess ->
                    context(sess) {
                        var caughtHost = false
                        try {
                            callPing()
                        } catch (e: UnsupportedOperationException) {
                            caughtHost = e.message == "boom"
                        }
                        check(caughtHost) { "Host exception must preserve UnsupportedOperationException" }
                        var poisonedAfterHost = false
                        try {
                            charFrom(0x41u)
                        } catch (_: ComponentTrapException) {
                            poisonedAfterHost = true
                        }
                        check(poisonedAfterHost) { "Session must be poisoned after host import exception" }
                    }
                }

                return "ok"
            }
            """,
        )
        assertEquals("ok", out)
    }

    @Test
    fun `Phase 2 types rejects invalid UTF-16 without poisoning and preserves bulk LinearMemory copies`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.p2strict.types"
        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "types",
            pkg = pkg,
            harnessSource = """
            package $pkg

            fun run(): String =
                withSession(TypesComponent) {
                    var rejectedCharSurrogate = false
                    try {
                        echoChar(CodePoint(0xD800))
                    } catch (_: IllegalArgumentException) {
                        rejectedCharSurrogate = true
                    }
                    check(rejectedCharSurrogate) { "Invalid char surrogate must throw IllegalArgumentException" }
                    check(echoChar(CodePoint('A'.code)) == CodePoint('A'.code)) { "Session must stay usable after char IAE" }

                    var rejectedSurrogate = false
                    try {
                        echoName("a\udc00")
                    } catch (_: IllegalArgumentException) {
                        rejectedSurrogate = true
                    }
                    check(rejectedSurrogate) { "Invalid UTF-16 surrogate must throw IllegalArgumentException" }
                    check(echoName("ok") == "ok") { "Session must stay usable after IllegalArgumentException" }

                    val lengths = intArrayOf(0, 7, 8, 255, 256, 257, 65535, 65536, 65537, 131072, 131073, 150000)
                    for (len in lengths) {
                        val payload = ByteArray(len) { i -> ((i * 31 + len) and 0xFF).toByte() }
                        val echoed = echoBytes(payload)
                        check(payload.contentEquals(echoed)) { "echoBytes mismatch at length ${'$'}len" }

                        val text = String(CharArray(len) { i -> ('a'.code + (i % 26)).toChar() })
                        val echoedStr = echoName(text)
                        check(echoedStr == text) { "echoName mismatch at length ${'$'}len" }
                    }
                    "ok"
                }
            """,
        )
        assertEquals("ok", out)
    }

    @Test
    fun `Phase 2 enforces composed resource transfer and borrowed handle lifetime rules`(@TempDir tempDir: Path) {
        val composedPkg = "com.example.aot.p2strict.composedres"
        val composedOut = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "composed-res",
            pkg = composedPkg,
            harnessSource = """
            package $composedPkg

            fun run(): String =
                withSession(ComposedResComponent) {
                    val a = g()
                    val b = g()
                    check(a == 1u && b == 1u) { "Expected g()=1u twice, got ${'$'}a, ${'$'}b" }
                    "ok"
                }
            """,
        )
        assertEquals("ok", composedOut)

        val hostResPkg = "com.example.aot.p2strict.hostres"
        val hostResOut = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "host-res",
            pkg = hostResPkg,
            harnessSource = """
            package $hostResPkg

            import $hostResPkg.exports.example.host_res.guest_api.Counter as GuestCounter
            import $hostResPkg.example.host_res.store.Blob
            import $hostResPkg.example.host_res.store.Store

            class DummyBlob(private val bytes: ByteArray) : Blob {
                override fun size(): UInt = bytes.size.toUInt()
                override fun append(other: ByteArray) {}
                override fun read(): ByteArray = bytes
                override fun close() {}
            }

            class DummyTicket(private val idVal: UInt) : Ticket {
                override fun id(): UInt = idVal
                override fun close() {}
            }

            fun run(): String {
                lateinit var borrowedGuestCounter: GuestCounter
                var dropDuringBorrowErr: Throwable? = null
                val store = object : Store {
                    override fun newBlob(init: ByteArray): Blob = DummyBlob(init)
                    override fun blobMerge(a: Blob, b: Blob): Blob {
                        b.close()
                        return DummyBlob(a.read() + b.read())
                    }
                    override fun log(msg: String) {
                        dropDuringBorrowErr = runCatching { borrowedGuestCounter.close() }.exceptionOrNull()
                    }
                    override fun find(name: String): Blob? = null
                }
                val worldImports = object : Imports {
                    override fun newTicket(id: UInt): Ticket = DummyTicket(id)
                }
                val imp = imports(exampleHostResStore = { store }, world = { worldImports })
                val firstResult = withSession(HostResComponent, imp) {
                    val c = GuestCounter(5)
                    borrowedGuestCounter = c
                    c.poke()
                    val err = dropDuringBorrowErr
                    check(err is IllegalStateException) { "Dropping guest resource while borrowed in poke() must throw ISE, got ${'$'}err" }
                    check(!c.isClosed) { "Failed close while borrowed must leave isClosed == false" }
                    check(c.value() == 6) { "Expected counter value 6, got ${'$'}{c.value()}" }
                    c.close()
                    check(c.isClosed) { "Close after borrow must mark isClosed == true" }

                    c.close()
                    // Calling after close must throw without poisoning fresh handles.
                    var useAfterCloseFailed = false
                    try {
                        c.value()
                    } catch (_: IllegalStateException) {
                        useAfterCloseFailed = true
                    }
                    val fresh = GuestCounter(10)
                    val freshVal = fresh.value()
                    fresh.close()
                    check(useAfterCloseFailed && freshVal == 10) {
                        "useAfterCloseFailed=${'$'}useAfterCloseFailed, freshVal=${'$'}freshVal"
                    }
                    "ok"
                }
                check(firstResult == "ok")

                // Closing a guest resource after session closure throws once, sets isClosed, and repeat close is a no-op.
                lateinit var leaked: GuestCounter
                withSession(HostResComponent, imp) {
                    leaked = GuestCounter(7)
                }
                val firstAfterSessionClose = runCatching { leaked.close() }.exceptionOrNull()
                check(
                    firstAfterSessionClose is IllegalStateException &&
                        firstAfterSessionClose !is wasm2kt.runtime.ComponentTrapException &&
                        "already closed" in (firstAfterSessionClose.message ?: "") &&
                        leaked.isClosed,
                ) {
                    "Expected IllegalStateException('...already closed') and isClosed=true, got ${'$'}firstAfterSessionClose / isClosed=${'$'}{leaked.isClosed}"
                }
                leaked.close()
                return "ok"
            }
            """,
        )
        assertEquals("ok", hostResOut)
    }

    @Test
    fun `Phase 2 mapped ADR-0014 library imports enforce host exception poisoning and re-entry trap`(@TempDir tempDir: Path) {
        val libPkg = "com.example.aot.p2strict.lib"
        val appPkg = "com.example.aot.p2strict.mapped"
        val sameNameWit = Paths.get(requireNotNull(javaClass.getResource("/fixtures/same-name.wit")).toURI())
        val wasmToolsBin = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
            ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
            ?: "wasm-tools"

        val libSrc = tempDir.resolve("lib-src")
        val libClasses = tempDir.resolve("lib-classes")
        wasm2kt.cli.GenerateCommand().parse(
            listOf("--wasm-tools-path", wasmToolsBin, "--interfaces-only", "-o", libSrc.toString(), "--resources-output", libClasses.toString(), "-p", libPkg, sameNameWit.toString()),
        )
        val libSources = generatedSources(libSrc)
        Files.createDirectories(libClasses)
        val libExit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
            "-classpath", System.getProperty("java.class.path"),
            "-d", libClasses.toString(),
            *libSources.toTypedArray(),
        )
        assertEquals(ExitCode.OK, libExit, "Interface library must compile")

        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "same-name",
            pkg = appPkg,
            libraryClasspath = listOf(libClasses),
            harnessSource = """
            package $appPkg

            import $libPkg.example.fs.types.ErrorCode as FsErrorCode
            import $libPkg.example.fs.types.Types as FsTypes
            import $libPkg.example.http.types.ErrorCode as HttpErrorCode
            import $libPkg.example.http.types.Tally
            import $libPkg.example.http.types.Types as HttpTypes
            import wasm2kt.runtime.ComponentTrapException

            class DummyTally(private var v: UInt) : Tally {
                override fun get(): UInt { v += 1u; return v }
                override fun close() {}
            }

            fun run(): String {
                // 1. Host exception from mapped library import preserves type and poisons session.
                val failingImports = imports(
                    exampleHttpTypes = {
                        object : HttpTypes {
                            override fun newTally(start: UInt): Tally = DummyTally(start)
                            override fun describe(e: HttpErrorCode): String {
                                throw UnsupportedOperationException("mapped-boom")
                            }
                        }
                    },
                    exampleFsTypes = {
                        object : FsTypes {
                            override fun describe(e: FsErrorCode): String = "fs"
                        }
                    },
                )
                val s1 = openSession(SameNameComponent, failingImports)
                s1.use { sess ->
                    context(sess) {
                        var caught = false
                        try {
                            run(1u)
                        } catch (e: UnsupportedOperationException) {
                            caught = e.message == "mapped-boom"
                        }
                        check(caught) { "Mapped host import must preserve UnsupportedOperationException" }
                        var poisoned = false
                        try {
                            run(1u)
                        } catch (_: ComponentTrapException) {
                            poisoned = true
                        }
                        check(poisoned) { "Session must be poisoned after mapped host import exception" }
                    }
                }

                // 2. Re-entry through mapped library import traps with ComponentTrapException and poisons session.
                lateinit var reenterSess: Session
                var innerErr: Throwable? = null
                reenterSess = openSession(
                    SameNameComponent,
                    imports(
                        exampleHttpTypes = {
                            object : HttpTypes {
                                override fun newTally(start: UInt): Tally = DummyTally(start)
                                override fun describe(e: HttpErrorCode): String {
                                    context(reenterSess) {
                                        try {
                                            run(2u)
                                        } catch (t: Throwable) {
                                            innerErr = t
                                        }
                                    }
                                    return "after"
                                }
                            }
                        },
                        exampleFsTypes = {
                            object : FsTypes {
                                override fun describe(e: FsErrorCode): String = "fs"
                            }
                        },
                    ),
                )
                reenterSess.use { sess ->
                    context(sess) {
                        run(1u)
                        val err = innerErr
                        check(err is ComponentTrapException) { "Expected ComponentTrapException on mapped re-entry, got ${'$'}err" }
                        var poisoned = false
                        try {
                            run(1u)
                        } catch (_: ComponentTrapException) {
                            poisoned = true
                        }
                        check(poisoned) { "Session must be poisoned after mapped re-entry trap" }
                    }
                }
                return "ok"
            }
            """,
        )
        assertEquals("ok", out)
    }
}
