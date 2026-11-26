package wasm2kt

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.generator.WitToKotlinPoetConverter
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.aot.AotComponentBuild
import wasm2kt.tooling.aot.AotComponentCodegen
import wasm2kt.tooling.aot.AotComponentCompiler
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * End-to-end differential tests comparing the Phase 2 direct static AOT World DSL against both
 * Wasmtime and Phase 1 (`AotComponentCodegen.generateComponentOnly`) across all 8 synchronous
 * component fixtures (`arithmetic`, `types`, `roundtrip`, `heavy`, `counter`, `host-res`,
 * `composed-str`, `composed-res`), including guest/host resources, compound values, and
 * multi-memory string transcoding.
 */
class AotPhase2DifferentialTest {
    private fun fixture(name: String): Path =
        Paths.get(requireNotNull(javaClass.getResource("/fixtures/$name.wasm")).toURI())

    @Test
    fun `Phase 2 Direct World DSL matches Wasmtime and Phase 1 across all synchronous fixtures`(@TempDir tempDir: Path) {
        val arith = AotComponentBuild.run(fixture("arithmetic"), "arithmetic", "diff.p2.arithmetic", tempDir.resolve("p2-arith"))
        val types = AotComponentBuild.run(fixture("types"), "types", "diff.p2.types", tempDir.resolve("p2-types"))
        val rt = AotComponentBuild.run(fixture("roundtrip"), "roundtrip", "diff.p2.roundtrip", tempDir.resolve("p2-rt"))
        val ct = AotComponentBuild.run(fixture("counter"), "counter", "diff.p2.counter", tempDir.resolve("p2-ct"))
        val hr = AotComponentBuild.run(fixture("host-res"), "host-res", "diff.p2.host_res", tempDir.resolve("p2-hr"))
        val cs = AotComponentBuild.run(fixture("composed-str"), "composed-str", "diff.p2.composed_str", tempDir.resolve("p2-cs"))
        val cr = AotComponentBuild.run(fixture("composed-res"), "composed-res", "diff.p2.composed_res", tempDir.resolve("p2-cr"))
        val hv = AotComponentBuild.run(fixture("heavy"), "heavy", "diff.p2.heavy", tempDir.resolve("p2-hv"))

        // Reference Wasmtime + Phase 1 bindings for all 8 synchronous fixtures
        val refWork = tempDir.resolve("ref")
        val refKt = refWork.resolve("kotlin")
        val refCls = refWork.resolve("classes")
        val refRes = refWork.resolve("resources")
        val refNames = listOf("arithmetic", "types", "roundtrip", "heavy", "counter", "host-res", "composed-str", "composed-res")
        for (n in refNames) {
            val dec = refWork.resolve("dec-$n")
            Decomposer.decompose(fixture(n), dec)
            val pkg = "diff.ref." + n.replace('-', '_')
            val internalPkg = "$pkg.wasm2kt.p1"
            WitToKotlinPoetConverter(WitResolve.parse(Files.readString(Decomposer.witJson(dec))), pkg, emitWasmtimeSessions = true)
                .generate().forEach { it.writeTo(refKt) }
            val compiled = AotComponentCompiler.compile(dec, refCls, refRes, internalPkg)
            AotComponentCodegen.generateComponentOnly(refKt, "$n-p1", pkg, compiled, internalPkg)
        }

        val harness = tempDir.resolve("DiffHarness.kt")
        Files.writeString(
            harness,
            """
            package diff.harness

            import java.nio.file.Path
            import diff.p2.roundtrip.example.roundtrip.env_provider.EnvProvider as P2Env
            import diff.ref.roundtrip.example.roundtrip.env_provider.EnvProvider as RefEnv
            import diff.p2.host_res.example.host_res.store.*
            import diff.p2.composed_res.*
            import diff.p2.composed_str.*

            fun runAll(fixturesDir: Path) {
                // 1. arithmetic: P2 vs Phase 1 vs Wasmtime
                val p2Arith = diff.p2.arithmetic.withSession(diff.p2.arithmetic.ArithmeticComponent) {
                    listOf(
                        diff.p2.arithmetic.add(Int.MIN_VALUE, 1),
                        diff.p2.arithmetic.sub(10, -5),
                        diff.p2.arithmetic.mul(7, -8),
                        diff.p2.arithmetic.div(100, -4),
                    )
                }
                val p1Arith = diff.ref.arithmetic.withSession(diff.ref.arithmetic.ArithmeticP1Component) {
                    listOf(
                        diff.ref.arithmetic.add(Int.MIN_VALUE, 1),
                        diff.ref.arithmetic.sub(10, -5),
                        diff.ref.arithmetic.mul(7, -8),
                        diff.ref.arithmetic.div(100, -4),
                    )
                }
                val wtArith = diff.ref.arithmetic.withSession(fixturesDir.resolve("arithmetic.wasm")) {
                    listOf(
                        diff.ref.arithmetic.add(Int.MIN_VALUE, 1),
                        diff.ref.arithmetic.sub(10, -5),
                        diff.ref.arithmetic.mul(7, -8),
                        diff.ref.arithmetic.div(100, -4),
                    )
                }
                check(p2Arith == p1Arith && p2Arith == wtArith) { "arithmetic mismatch: ${'$'}p2Arith vs ${'$'}p1Arith vs ${'$'}wtArith" }

                // 2. types: strings, empty & 64KiB byte arrays, char (astral), record, variant, nested WitOption, option<record>
                val bytes64k = ByteArray(65536) { (it * 31).toByte() }
                fun runTypesP2(): List<Any?> = diff.p2.types.withSession(diff.p2.types.TypesComponent) {
                    listOf(
                        diff.p2.types.echoName(null),
                        diff.p2.types.echoName(""),
                        diff.p2.types.echoName("aé日\uD83D\uDE00"),
                        diff.p2.types.maybeLen(null),
                        diff.p2.types.maybeLen("hello"),
                        diff.p2.types.echoBytes(ByteArray(0)).toList(),
                        diff.p2.types.reverseBytes(byteArrayOf(1, 2, 3, -1)).toList(),
                        diff.p2.types.echoBytes(bytes64k).toList(),
                        diff.p2.types.nextChar(diff.p2.types.CodePoint.of("\uD83D\uDE00")).value,
                        diff.p2.types.echoBlob(diff.p2.types.Blob("n", byteArrayOf(7, 8), 99u)).let { Triple(it.name, it.data.toList(), it.tag) },
                        diff.p2.types.echoPacket(diff.p2.types.Packet.Empty) == diff.p2.types.Packet.Empty,
                        (diff.p2.types.echoPacket(diff.p2.types.Packet.Text("hi")) as diff.p2.types.Packet.Text).value,
                        (diff.p2.types.echoPacket(diff.p2.types.Packet.Bytes(byteArrayOf(4, 5))) as diff.p2.types.Packet.Bytes).value.toList(),
                        diff.p2.types.compact(listOf(1, null, -2, null, 3)),
                        diff.p2.types.echoOptionalList(listOf(null, 42, null)),
                        diff.p2.types.nested(diff.p2.types.WitOption.None).toString(),
                        diff.p2.types.nested(diff.p2.types.WitOption.Some(null)).toString(),
                        diff.p2.types.nested(diff.p2.types.WitOption.Some("xyz")).toString(),
                        diff.p2.types.echoFlagged(null),
                        diff.p2.types.echoFlagged(diff.p2.types.Flagged("x", 5u, "y"))?.let { Triple(it.option, it.value, it.note) },
                    )
                }
                fun runTypesRef(open: () -> diff.ref.types.Session): List<Any?> = open().use { s ->
                    context(s) {
                        listOf(
                            diff.ref.types.echoName(null),
                            diff.ref.types.echoName(""),
                            diff.ref.types.echoName("aé日\uD83D\uDE00"),
                            diff.ref.types.maybeLen(null),
                            diff.ref.types.maybeLen("hello"),
                            diff.ref.types.echoBytes(ByteArray(0)).toList(),
                            diff.ref.types.reverseBytes(byteArrayOf(1, 2, 3, -1)).toList(),
                            diff.ref.types.echoBytes(bytes64k).toList(),
                            diff.ref.types.nextChar(diff.ref.types.CodePoint.of("\uD83D\uDE00")).value,
                            diff.ref.types.echoBlob(diff.ref.types.Blob("n", byteArrayOf(7, 8), 99u)).let { Triple(it.name, it.data.toList(), it.tag) },
                            diff.ref.types.echoPacket(diff.ref.types.Packet.Empty) == diff.ref.types.Packet.Empty,
                            (diff.ref.types.echoPacket(diff.ref.types.Packet.Text("hi")) as diff.ref.types.Packet.Text).value,
                            (diff.ref.types.echoPacket(diff.ref.types.Packet.Bytes(byteArrayOf(4, 5))) as diff.ref.types.Packet.Bytes).value.toList(),
                            diff.ref.types.compact(listOf(1, null, -2, null, 3)),
                            diff.ref.types.echoOptionalList(listOf(null, 42, null)),
                            diff.ref.types.nested(diff.ref.types.WitOption.None).toString(),
                            diff.ref.types.nested(diff.ref.types.WitOption.Some(null)).toString(),
                            diff.ref.types.nested(diff.ref.types.WitOption.Some("xyz")).toString(),
                            diff.ref.types.echoFlagged(null),
                            diff.ref.types.echoFlagged(diff.ref.types.Flagged("x", 5u, "y"))?.let { Triple(it.option, it.value, it.note) },
                        )
                    }
                }
                val tP2 = runTypesP2()
                val tP1 = runTypesRef { diff.ref.types.openSession(diff.ref.types.TypesP1Component) }
                val tWt = runTypesRef { diff.ref.types.openSession(fixturesDir.resolve("types.wasm")) }
                check(tP2 == tP1 && tP2 == tWt) { "types mismatch:\nP2=${'$'}tP2\nP1=${'$'}tP1\nWT=${'$'}tWt" }

                // 3. roundtrip: P2 vs Phase 1 vs Wasmtime
                val p2Rt = diff.p2.roundtrip.withSession(
                    diff.p2.roundtrip.RoundtripComponent,
                    diff.p2.roundtrip.imports(exampleRoundtripEnvProvider = {
                        object : P2Env {
                            override fun getMultiplier(): Int = -9
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix:${'$'}code"
                        }
                    }),
                ) {
                    val rec = diff.p2.roundtrip.ItemSummary(100u, diff.p2.roundtrip.Permissions.WRITE or diff.p2.roundtrip.Permissions.EXECUTE, diff.p2.roundtrip.Priority.HIGH)
                    val outRec = diff.p2.roundtrip.transformSummary(rec, diff.p2.roundtrip.Action.SetPriority(diff.p2.roundtrip.Priority.MEDIUM))
                    listOf(
                        diff.p2.roundtrip.exports.example.roundtrip.math_api.scale(11),
                        diff.p2.roundtrip.describeCode("W", 7u),
                        outRec.count, outRec.perms.raw, outRec.prio.name,
                        diff.p2.roundtrip.echoU64(0uL), diff.p2.roundtrip.echoU64(ULong.MAX_VALUE),
                    )
                }
                fun runRoundtripRef(open: () -> diff.ref.roundtrip.Session): List<Any> = open().use { s ->
                    context(s) {
                        val rec = diff.ref.roundtrip.ItemSummary(100u, diff.ref.roundtrip.Permissions.WRITE or diff.ref.roundtrip.Permissions.EXECUTE, diff.ref.roundtrip.Priority.HIGH)
                        val outRec = diff.ref.roundtrip.transformSummary(rec, diff.ref.roundtrip.Action.SetPriority(diff.ref.roundtrip.Priority.MEDIUM))
                        listOf(
                            diff.ref.roundtrip.exports.example.roundtrip.math_api.scale(11),
                            diff.ref.roundtrip.describeCode("W", 7u),
                            outRec.count, outRec.perms.raw, outRec.prio.name,
                            diff.ref.roundtrip.echoU64(0uL), diff.ref.roundtrip.echoU64(ULong.MAX_VALUE),
                        )
                    }
                }
                val rtImports = diff.ref.roundtrip.imports(exampleRoundtripEnvProvider = {
                    object : RefEnv {
                        override fun getMultiplier(): Int = -9
                        override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix:${'$'}code"
                    }
                })
                val p1Rt = runRoundtripRef { diff.ref.roundtrip.openSession(diff.ref.roundtrip.RoundtripP1Component, rtImports) }
                val wtRt = runRoundtripRef { diff.ref.roundtrip.openSession(fixturesDir.resolve("roundtrip.wasm"), rtImports) }
                check(p2Rt == p1Rt && p2Rt == wtRt) { "roundtrip mismatch: ${'$'}p2Rt vs ${'$'}p1Rt vs ${'$'}wtRt" }

                // 4. heavy: P2 vs Phase 1 vs Wasmtime
                val text = "running walked running "
                val json = "[{\"id\":1,\"ok\":true}]"
                val raw = byteArrayOf(5, 1, 9, 2, 0)
                val p2Hv = diff.p2.heavy.withSession(diff.p2.heavy.HeavyComponent) {
                    listOf(
                        diff.p2.heavy.lcgSum(13u, 5000u),
                        diff.p2.heavy.regexCount("[a-z]+ing", text).getOrNull(),
                        diff.p2.heavy.jsonRoundtrip(json).getOrNull(),
                        diff.p2.heavy.sortBytes(raw).toList(),
                    )
                }
                fun runHeavyRef(open: () -> diff.ref.heavy.Session): List<Any?> = open().use { s ->
                    context(s) {
                        listOf(
                            diff.ref.heavy.lcgSum(13u, 5000u),
                            diff.ref.heavy.regexCount("[a-z]+ing", text).getOrNull(),
                            diff.ref.heavy.jsonRoundtrip(json).getOrNull(),
                            diff.ref.heavy.sortBytes(raw).toList(),
                        )
                    }
                }
                val p1Hv = runHeavyRef { diff.ref.heavy.openSession(diff.ref.heavy.HeavyP1Component) }
                val wtHv = runHeavyRef { diff.ref.heavy.openSession(fixturesDir.resolve("heavy.wasm")) }
                check(p2Hv == p1Hv && p2Hv == wtHv) { "heavy mismatch: ${'$'}p2Hv vs ${'$'}p1Hv vs ${'$'}wtHv" }

                // 5. counter (guest resources): P2 vs Phase 1 vs Wasmtime
                val p2Ct = diff.p2.counter.withSession(diff.p2.counter.CounterComponent) {
                    val a = diff.p2.counter.exports.example.counter.counter_api.Counter(10)
                    val b = diff.p2.counter.exports.example.counter.counter_api.Counter(30)
                    a.increment(); b.add(2)
                    val merged = diff.p2.counter.exports.example.counter.counter_api.Counter.merge(a, b)
                    check(b.isClosed)
                    val vals = listOf(a.getValue(), merged.getValue())
                    a.close(); merged.close()
                    vals
                }
                fun runCounterRef(open: () -> diff.ref.counter.Session): List<Int> = open().use { s ->
                    context(s) {
                        val a = diff.ref.counter.exports.example.counter.counter_api.Counter(10)
                        val b = diff.ref.counter.exports.example.counter.counter_api.Counter(30)
                        a.increment(); b.add(2)
                        val merged = diff.ref.counter.exports.example.counter.counter_api.Counter.merge(a, b)
                        check(b.isClosed)
                        val vals = listOf(a.getValue(), merged.getValue())
                        a.close(); merged.close()
                        vals
                    }
                }
                val p1Ct = runCounterRef { diff.ref.counter.openSession(diff.ref.counter.CounterP1Component) }
                val wtCt = runCounterRef { diff.ref.counter.openSession(fixturesDir.resolve("counter.wasm")) }
                check(p2Ct == p1Ct && p2Ct == wtCt) { "counter mismatch: ${'$'}p2Ct vs ${'$'}p1Ct vs ${'$'}wtCt" }

                // 6. host-res (host & guest resources): P2 vs Phase 1 vs Wasmtime
                val p2HrLog = mutableListOf<String>()
                class P2Blob(var data: ByteArray) : diff.p2.host_res.example.host_res.store.Blob {
                    override fun size(): UInt = data.size.toUInt()
                    override fun append(more: ByteArray) { data += more }
                    override fun read(): ByteArray = data
                    override fun close() { p2HrLog += "drop:${'$'}{data.size}" }
                }
                class P2Ticket(private val id: UInt) : diff.p2.host_res.Ticket {
                    override fun id(): UInt = id
                    override fun close() { p2HrLog += "drop-ticket:${'$'}id" }
                }
                val p2HrOut = diff.p2.host_res.withSession(
                    diff.p2.host_res.HostResComponent,
                    diff.p2.host_res.imports(
                        exampleHostResStore = {
                            object : diff.p2.host_res.example.host_res.store.Store {
                                override fun newBlob(init: ByteArray): diff.p2.host_res.example.host_res.store.Blob = P2Blob(init)
                                override fun blobMerge(a: diff.p2.host_res.example.host_res.store.Blob, b: diff.p2.host_res.example.host_res.store.Blob): diff.p2.host_res.example.host_res.store.Blob =
                                    P2Blob((a as P2Blob).data + (b as P2Blob).data)
                                override fun log(msg: String) { p2HrLog += "log:${'$'}msg" }
                                override fun find(name: String): diff.p2.host_res.example.host_res.store.Blob? =
                                    if (name == "none") null else P2Blob(name.toByteArray())
                            }
                        },
                        world = { object : diff.p2.host_res.Imports { override fun newTicket(id: UInt) = P2Ticket(id) } },
                    ),
                ) {
                    val kept = diff.p2.host_res.keep(byteArrayOf(7, 8))
                    val c = diff.p2.host_res.exports.example.host_res.guest_api.Counter(4)
                    c.poke()
                    listOf(
                        diff.p2.host_res.makeAndMeasure(byteArrayOf(1), byteArrayOf(2, 3)),
                        diff.p2.host_res.consume(kept),
                        diff.p2.host_res.mergeNew(byteArrayOf(4), byteArrayOf(5, 6)).toList(),
                        diff.p2.host_res.lookup("none") == null,
                        diff.p2.host_res.ticketId(9u),
                        c.value(),
                        p2HrLog.toList(),
                    ).also { c.close() }
                }
                fun runHostResRef(open: (wasm2kt.runtime.ImportsFactory) -> diff.ref.host_res.Session): List<Any?> {
                    val log = mutableListOf<String>()
                    class RBlob(var data: ByteArray) : diff.ref.host_res.example.host_res.store.Blob {
                        override fun size(): UInt = data.size.toUInt()
                        override fun append(more: ByteArray) { data += more }
                        override fun read(): ByteArray = data
                        override fun close() { log += "drop:${'$'}{data.size}" }
                    }
                    class RTicket(private val id: UInt) : diff.ref.host_res.Ticket {
                        override fun id(): UInt = id
                        override fun close() { log += "drop-ticket:${'$'}id" }
                    }
                    val imp = diff.ref.host_res.imports(
                        exampleHostResStore = {
                            object : diff.ref.host_res.example.host_res.store.Store {
                                override fun newBlob(init: ByteArray): diff.ref.host_res.example.host_res.store.Blob = RBlob(init)
                                override fun blobMerge(a: diff.ref.host_res.example.host_res.store.Blob, b: diff.ref.host_res.example.host_res.store.Blob): diff.ref.host_res.example.host_res.store.Blob =
                                    RBlob((a as RBlob).data + (b as RBlob).data)
                                override fun log(msg: String) { log += "log:${'$'}msg" }
                                override fun find(name: String): diff.ref.host_res.example.host_res.store.Blob? =
                                    if (name == "none") null else RBlob(name.toByteArray())
                            }
                        },
                        world = { object : diff.ref.host_res.Imports { override fun newTicket(id: UInt) = RTicket(id) } },
                    )
                    return open(imp).use { s ->
                        context(s) {
                            val kept = diff.ref.host_res.keep(byteArrayOf(7, 8))
                            val c = diff.ref.host_res.exports.example.host_res.guest_api.Counter(4)
                            c.poke()
                            listOf(
                                diff.ref.host_res.makeAndMeasure(byteArrayOf(1), byteArrayOf(2, 3)),
                                diff.ref.host_res.consume(kept),
                                diff.ref.host_res.mergeNew(byteArrayOf(4), byteArrayOf(5, 6)).toList(),
                                diff.ref.host_res.lookup("none") == null,
                                diff.ref.host_res.ticketId(9u),
                                c.value(),
                                log.toList(),
                            ).also { c.close() }
                        }
                    }
                }
                val p1HrOut = runHostResRef { diff.ref.host_res.openSession(diff.ref.host_res.HostResP1Component, it) }
                val wtHrOut = runHostResRef { diff.ref.host_res.openSession(fixturesDir.resolve("host-res.wasm"), it) }
                check(p2HrOut == p1HrOut && p2HrOut == wtHrOut) { "host-res mismatch: ${'$'}p2HrOut vs ${'$'}p1HrOut vs ${'$'}wtHrOut" }

                // 7. composed-res: P2 vs Phase 1 vs Wasmtime
                val p2Cr = diff.p2.composed_res.withSession(diff.p2.composed_res.ComposedResComponent) { diff.p2.composed_res.g() }
                val p1Cr = diff.ref.composed_res.withSession(diff.ref.composed_res.ComposedResP1Component) { diff.ref.composed_res.g() }
                val wtCr = diff.ref.composed_res.withSession(fixturesDir.resolve("composed-res.wasm")) { diff.ref.composed_res.g() }
                check(p2Cr == p1Cr && p2Cr == wtCr) { "composed-res mismatch: ${'$'}p2Cr vs ${'$'}p1Cr vs ${'$'}wtCr" }

                // 8. composed-str (all 9 encoding combinations + list): P2 vs Phase 1 vs Wasmtime
                val sample = "aé日\uD83D\uDE00"
                val nums = listOf(10u, 20u, 30u, 42u)
                val p2Cs = diff.p2.composed_str.withSession(diff.p2.composed_str.ComposedStrComponent) {
                    listOf(
                        diff.p2.composed_str.echoUtf8Utf8(sample),
                        diff.p2.composed_str.echoUtf8Utf16(sample),
                        diff.p2.composed_str.echoUtf8Compact(sample),
                        diff.p2.composed_str.echoUtf16Utf8(sample),
                        diff.p2.composed_str.echoUtf16Utf16(sample),
                        diff.p2.composed_str.echoUtf16Compact(sample),
                        diff.p2.composed_str.echoCompactUtf8(sample),
                        diff.p2.composed_str.echoCompactUtf16(sample),
                        diff.p2.composed_str.echoCompactCompact(sample),
                        diff.p2.composed_str.sum(nums),
                        diff.p2.composed_str.echoList(nums),
                    )
                }
                fun runCsRef(open: () -> diff.ref.composed_str.Session): List<Any> = open().use { s ->
                    context(s) {
                        listOf(
                            diff.ref.composed_str.echoUtf8Utf8(sample),
                            diff.ref.composed_str.echoUtf8Utf16(sample),
                            diff.ref.composed_str.echoUtf8Compact(sample),
                            diff.ref.composed_str.echoUtf16Utf8(sample),
                            diff.ref.composed_str.echoUtf16Utf16(sample),
                            diff.ref.composed_str.echoUtf16Compact(sample),
                            diff.ref.composed_str.echoCompactUtf8(sample),
                            diff.ref.composed_str.echoCompactUtf16(sample),
                            diff.ref.composed_str.echoCompactCompact(sample),
                            diff.ref.composed_str.sum(nums),
                            diff.ref.composed_str.echoList(nums),
                        )
                    }
                }
                val p1Cs = runCsRef { diff.ref.composed_str.openSession(diff.ref.composed_str.ComposedStrP1Component) }
                val wtCs = runCsRef { diff.ref.composed_str.openSession(fixturesDir.resolve("composed-str.wasm")) }
                check(p2Cs == p1Cs && p2Cs == wtCs) { "composed-str mismatch: ${'$'}p2Cs vs ${'$'}p1Cs vs ${'$'}wtCs" }
            }
            """.trimIndent(),
        )

        val p2Outputs = listOf(arith, types, rt, ct, hr, cs, cr, hv)
        val ktFiles = (p2Outputs.flatMap { generatedSources(it.kotlinDir) } + generatedSources(refKt) + harness.toString())
        val compiledOut = tempDir.resolve("compiled")
        Files.createDirectories(compiledOut)
        val cp = (listOf(System.getProperty("java.class.path"), refCls.toString()) + p2Outputs.map { it.classesDir.toString() })
            .joinToString(File.pathSeparator)
        val exit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib",
            "-classpath", cp,
            "-d", compiledOut.toString(),
            *ktFiles.toTypedArray(),
        )
        assertEquals(ExitCode.OK, exit, "Phase 2 differential test sources must compile")

        val urls = (listOf(compiledOut, refCls, refRes) + p2Outputs.flatMap { listOf(it.classesDir, it.resourcesDir) })
            .map { it.toUri().toURL() }
            .toTypedArray()
        val fixturesDir = fixture("arithmetic").parent
        URLClassLoader(urls, javaClass.classLoader).use { loader ->
            loader.loadClass("diff.harness.DiffHarnessKt").getDeclaredMethod("runAll", Path::class.java).invoke(null, fixturesDir)
        }
    }
}
