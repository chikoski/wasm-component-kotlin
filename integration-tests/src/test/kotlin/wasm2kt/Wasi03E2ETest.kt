package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.cli.GenerateCommand
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.chicory.ChicoryComponent
import wasm2kt.tooling.Decomposer
import wasm2kt.wasi.WasiExitException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end test (#98): a component importing WASI 0.3 interfaces (`random`, `clocks`,
 * `environment`, `stdin`, `stdout`, `exit`) has bindings generated against `:runtime-wasi` via its
 * classpath [wasm2kt.generator.InterfaceManifest] and runs on Chicory with `Linker.addWasi03`.
 */
class Wasi03E2ETest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val componentWat = """
        (component
          (import "wasi:random/random@0.3.0" (instance ${'$'}random
            (export "get-random-u64" (func (result u64)))
          ))
          (import "wasi:clocks/monotonic-clock@0.3.0" (instance ${'$'}mono
            (export "now" (func (result u64)))
            (export "wait-for" (func async (param "how-long" u64)))
          ))
          (import "wasi:clocks/system-clock@0.3.0" (instance ${'$'}sys
            (type ${'$'}inst-def (record (field "seconds" s64) (field "nanoseconds" u32)))
            (export "instant" (type ${'$'}inst (eq ${'$'}inst-def)))
            (export "now" (func (result ${'$'}inst)))
          ))
          (import "wasi:cli/types@0.3.0" (instance ${'$'}cli-types
            (type ${'$'}err-def (enum "io" "illegal-byte-sequence" "pipe"))
            (export "error-code" (type ${'$'}error-code (eq ${'$'}err-def)))
          ))
          (alias export ${'$'}cli-types "error-code" (type ${'$'}err))
          (import "wasi:cli/environment@0.3.0" (instance ${'$'}env
            (type ${'$'}args (list string))
            (export "get-arguments" (func (result ${'$'}args)))
          ))
          (import "wasi:cli/stdin@0.3.0" (instance ${'$'}stdin
            (alias outer 1 ${'$'}err (type ${'$'}err-in))
            (export "error-code" (type ${'$'}err-exp (eq ${'$'}err-in)))
            (type ${'$'}s (stream u8))
            (type ${'$'}r (result (error ${'$'}err-exp)))
            (type ${'$'}f (future ${'$'}r))
            (type ${'$'}t (tuple ${'$'}s ${'$'}f))
            (export "read-via-stream" (func (result ${'$'}t)))
          ))
          (import "wasi:cli/stdout@0.3.0" (instance ${'$'}stdout
            (alias outer 1 ${'$'}err (type ${'$'}err-in))
            (export "error-code" (type ${'$'}err-exp (eq ${'$'}err-in)))
            (type ${'$'}s (stream u8))
            (type ${'$'}r (result (error ${'$'}err-exp)))
            (type ${'$'}f (future ${'$'}r))
            (export "write-via-stream" (func (param "data" ${'$'}s) (result ${'$'}f)))
          ))
          (import "wasi:cli/exit@0.3.0" (instance ${'$'}exit
            (export "exit-with-code" (func (param "status-code" u8)))
          ))
          (type ${'$'}byte-stream (stream u8))
          (type ${'$'}io-res (result (error ${'$'}err)))
          (type ${'$'}io-future (future ${'$'}io-res))

          (core module ${'$'}Mem
            (memory (export "mem") 2)
            (global ${'$'}heap (mut i32) (i32.const 4096))
            (func (export "realloc") (param ${'$'}old i32) (param ${'$'}old_size i32) (param ${'$'}align i32) (param ${'$'}new_size i32) (result i32)
              (local ${'$'}p i32)
              (local ${'$'}mask i32)
              (local.set ${'$'}mask (i32.sub (local.get ${'$'}align) (i32.const 1)))
              (local.set ${'$'}p (i32.and (i32.add (global.get ${'$'}heap) (local.get ${'$'}mask)) (i32.xor (local.get ${'$'}mask) (i32.const -1))))
              (global.set ${'$'}heap (i32.add (local.get ${'$'}p) (local.get ${'$'}new_size)))
              (local.get ${'$'}p))
          )
          (core instance ${'$'}mem (instantiate ${'$'}Mem))

          (core module ${'$'}M
            (import "" "mem" (memory 2))
            (import "" "get-random-u64" (func ${'$'}get-random-u64 (result i64)))
            (import "" "mono-now" (func ${'$'}mono-now (result i64)))
            (import "" "wait-for" (func ${'$'}wait-for (param i64) (result i32)))
            (import "" "sys-now" (func ${'$'}sys-now (param i32)))
            (import "" "get-arguments" (func ${'$'}get-arguments (param i32)))
            (import "" "stdin-read" (func ${'$'}stdin-read (param i32)))
            (import "" "stdout-write" (func ${'$'}stdout-write (param i32) (result i32)))
            (import "" "exit-with-code" (func ${'$'}exit-with-code (param i32)))
            (import "" "task.return.u64" (func ${'$'}return-u64 (param i64)))
            (import "" "stream.new" (func ${'$'}stream.new (result i64)))
            (import "" "stream.read" (func ${'$'}stream.read (param i32 i32 i32) (result i32)))
            (import "" "stream.write" (func ${'$'}stream.write (param i32 i32 i32) (result i32)))
            (import "" "stream.drop-readable" (func ${'$'}stream.drop-readable (param i32)))
            (import "" "stream.drop-writable" (func ${'$'}stream.drop-writable (param i32)))
            (import "" "future.read" (func ${'$'}future.read (param i32 i32) (result i32)))
            (import "" "future.drop-readable" (func ${'$'}future.drop-readable (param i32)))
            (import "" "subtask.drop" (func ${'$'}subtask.drop (param i32)))
            (import "" "waitable-set.new" (func ${'$'}waitable-set.new (result i32)))
            (import "" "waitable-set.wait" (func ${'$'}waitable-set.wait (param i32 i32) (result i32)))
            (import "" "waitable-set.drop" (func ${'$'}waitable-set.drop (param i32)))
            (import "" "waitable.join" (func ${'$'}waitable.join (param i32 i32)))

            (func ${'$'}await-future (param ${'$'}fh i32) (param ${'$'}ptr i32)
              (local ${'$'}ret i32)
              (local ${'$'}ws i32)
              (local ${'$'}ev i32)
              (local.set ${'$'}ret (call ${'$'}future.read (local.get ${'$'}fh) (local.get ${'$'}ptr)))
              (if (i32.eq (local.get ${'$'}ret) (i32.const -1 (; BLOCKED ;)))
                (then
                  (local.set ${'$'}ws (call ${'$'}waitable-set.new))
                  (call ${'$'}waitable.join (local.get ${'$'}fh) (local.get ${'$'}ws))
                  (local.set ${'$'}ev (i32.const 192))
                  (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (local.get ${'$'}ev)))
                  (call ${'$'}waitable.join (local.get ${'$'}fh) (i32.const 0))
                  (call ${'$'}waitable-set.drop (local.get ${'$'}ws))))
              (call ${'$'}future.drop-readable (local.get ${'$'}fh)))

            (func (export "run-checks")
              (local ${'$'}ws i32)
              (local ${'$'}ret i32)
              (local ${'$'}sub i32)
              (local ${'$'}rnd i64)
              (local ${'$'}t0 i64)
              (local ${'$'}t1 i64)
              (local ${'$'}in_s i32)
              (local ${'$'}in_f i32)
              (local ${'$'}out_ends i64)
              (local ${'$'}out_r i32)
              (local ${'$'}out_w i32)
              (local ${'$'}out_f i32)
              (local ${'$'}len i32)
              (local ${'$'}ev i32)
              (local ${'$'}arg_count i32)

              ;; 1. random + clocks
              (local.set ${'$'}rnd (call ${'$'}get-random-u64))
              (local.set ${'$'}t0 (call ${'$'}mono-now))
              (local.set ${'$'}ret (call ${'$'}wait-for (i64.const 5000000)))
              (if (i32.ne (i32.and (local.get ${'$'}ret) (i32.const 0xf)) (i32.const 2))
                (then
                  (local.set ${'$'}sub (i32.shr_u (local.get ${'$'}ret) (i32.const 4)))
                  (local.set ${'$'}ws (call ${'$'}waitable-set.new))
                  (call ${'$'}waitable.join (local.get ${'$'}sub) (local.get ${'$'}ws))
                  (loop ${'$'}w
                    (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (i32.const 256)))
                    (br_if ${'$'}w (i32.ne (i32.load (i32.const 260)) (i32.const 2))))
                  (call ${'$'}subtask.drop (local.get ${'$'}sub))
                  (call ${'$'}waitable-set.drop (local.get ${'$'}ws))))
              (local.set ${'$'}t1 (call ${'$'}mono-now))
              (call ${'$'}sys-now (i32.const 320))
              (if (i64.le_s (i64.load (i32.const 320)) (i64.const 0)) (then (unreachable)))
              (if (i64.lt_u (local.get ${'$'}t1) (local.get ${'$'}t0)) (then (unreachable)))

              ;; 2. environment arguments
              (call ${'$'}get-arguments (i32.const 352))
              (local.set ${'$'}arg_count (i32.load (i32.const 356)))
              (if (i32.ne (local.get ${'$'}arg_count) (i32.const 2)) (then (unreachable)))

              ;; 3. stdin -> buffer at 1024 -> append '!' -> stdout
              (call ${'$'}stdin-read (i32.const 384))
              (local.set ${'$'}in_s (i32.load (i32.const 384)))
              (local.set ${'$'}in_f (i32.load (i32.const 388)))
              (local.set ${'$'}len (i32.const 0))
              (local.set ${'$'}ws (call ${'$'}waitable-set.new))
              (call ${'$'}waitable.join (local.get ${'$'}in_s) (local.get ${'$'}ws))
              (block ${'$'}done
                (loop ${'$'}rd
                  (local.set ${'$'}ret (call ${'$'}stream.read (local.get ${'$'}in_s) (i32.add (i32.const 1024) (local.get ${'$'}len)) (i32.const 64)))
                  (if (i32.eq (local.get ${'$'}ret) (i32.const -1 (; BLOCKED ;)))
                    (then
                      (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (i32.const 416)))
                      (local.set ${'$'}ret (i32.load (i32.const 420)))))
                  (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.shr_u (local.get ${'$'}ret) (i32.const 4))))
                  (br_if ${'$'}done (i32.eq (i32.and (local.get ${'$'}ret) (i32.const 0xf)) (i32.const 1 (; DROPPED ;))))
                  (br ${'$'}rd)))
              (call ${'$'}waitable.join (local.get ${'$'}in_s) (i32.const 0))
              (call ${'$'}waitable-set.drop (local.get ${'$'}ws))
              (call ${'$'}stream.drop-readable (local.get ${'$'}in_s))
              (call ${'$'}await-future (local.get ${'$'}in_f) (i32.const 448))
              (if (i32.load8_u (i32.const 448)) (then (unreachable)))

              ;; append '!'
              (i32.store8 (i32.add (i32.const 1024) (local.get ${'$'}len)) (i32.const 33))
              (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.const 1)))

              (local.set ${'$'}out_ends (call ${'$'}stream.new))
              (local.set ${'$'}out_r (i32.wrap_i64 (local.get ${'$'}out_ends)))
              (local.set ${'$'}out_w (i32.wrap_i64 (i64.shr_u (local.get ${'$'}out_ends) (i64.const 32))))
              (local.set ${'$'}out_f (call ${'$'}stdout-write (local.get ${'$'}out_r)))

              (local.set ${'$'}ws (call ${'$'}waitable-set.new))
              (call ${'$'}waitable.join (local.get ${'$'}out_w) (local.get ${'$'}ws))
              (local.set ${'$'}ret (call ${'$'}stream.write (local.get ${'$'}out_w) (i32.const 1024) (local.get ${'$'}len)))
              (if (i32.eq (local.get ${'$'}ret) (i32.const -1))
                (then (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (i32.const 480)))))
              (call ${'$'}waitable.join (local.get ${'$'}out_w) (i32.const 0))
              (call ${'$'}waitable-set.drop (local.get ${'$'}ws))
              (call ${'$'}stream.drop-writable (local.get ${'$'}out_w))
              (call ${'$'}await-future (local.get ${'$'}out_f) (i32.const 512))
              (if (i32.load8_u (i32.const 512)) (then (unreachable)))

              (call ${'$'}return-u64 (local.get ${'$'}rnd)))

            (func (export "exit-with") (param ${'$'}code i32)
              (call ${'$'}exit-with-code (local.get ${'$'}code)))
          )

          (canon lower (func ${'$'}random "get-random-u64") (core func ${'$'}get-random-u64))
          (canon lower (func ${'$'}mono "now") (core func ${'$'}mono-now))
          (canon lower (func ${'$'}mono "wait-for") async (core func ${'$'}wait-for))
          (canon lower (func ${'$'}sys "now") (memory (core memory ${'$'}mem "mem")) (core func ${'$'}sys-now))
          (canon lower (func ${'$'}env "get-arguments") (memory (core memory ${'$'}mem "mem")) (realloc (core func ${'$'}mem "realloc")) (core func ${'$'}get-arguments))
          (canon lower (func ${'$'}stdin "read-via-stream") (memory (core memory ${'$'}mem "mem")) (core func ${'$'}stdin-read))
          (canon lower (func ${'$'}stdout "write-via-stream") (core func ${'$'}stdout-write))
          (canon lower (func ${'$'}exit "exit-with-code") (core func ${'$'}exit-with-code))
          (canon task.return (result u64) (core func ${'$'}return-u64))
          (canon stream.new ${'$'}byte-stream (core func ${'$'}stream.new))
          (canon stream.read ${'$'}byte-stream async (memory (core memory ${'$'}mem "mem")) (core func ${'$'}stream.read))
          (canon stream.write ${'$'}byte-stream async (memory (core memory ${'$'}mem "mem")) (core func ${'$'}stream.write))
          (canon stream.drop-readable ${'$'}byte-stream (core func ${'$'}stream.drop-readable))
          (canon stream.drop-writable ${'$'}byte-stream (core func ${'$'}stream.drop-writable))
          (canon future.read ${'$'}io-future async (memory (core memory ${'$'}mem "mem")) (core func ${'$'}future.read))
          (canon future.drop-readable ${'$'}io-future (core func ${'$'}future.drop-readable))
          (canon subtask.drop (core func ${'$'}subtask.drop))
          (canon waitable-set.new (core func ${'$'}waitable-set.new))
          (canon waitable-set.wait (memory (core memory ${'$'}mem "mem")) (core func ${'$'}waitable-set.wait))
          (canon waitable-set.drop (core func ${'$'}waitable-set.drop))
          (canon waitable.join (core func ${'$'}waitable.join))

          (core instance ${'$'}m (instantiate ${'$'}M (with "" (instance
            (export "mem" (memory ${'$'}mem "mem"))
            (export "get-random-u64" (func ${'$'}get-random-u64))
            (export "mono-now" (func ${'$'}mono-now))
            (export "wait-for" (func ${'$'}wait-for))
            (export "sys-now" (func ${'$'}sys-now))
            (export "get-arguments" (func ${'$'}get-arguments))
            (export "stdin-read" (func ${'$'}stdin-read))
            (export "stdout-write" (func ${'$'}stdout-write))
            (export "exit-with-code" (func ${'$'}exit-with-code))
            (export "task.return.u64" (func ${'$'}return-u64))
            (export "stream.new" (func ${'$'}stream.new))
            (export "stream.read" (func ${'$'}stream.read))
            (export "stream.write" (func ${'$'}stream.write))
            (export "stream.drop-readable" (func ${'$'}stream.drop-readable))
            (export "stream.drop-writable" (func ${'$'}stream.drop-writable))
            (export "future.read" (func ${'$'}future.read))
            (export "future.drop-readable" (func ${'$'}future.drop-readable))
            (export "subtask.drop" (func ${'$'}subtask.drop))
            (export "waitable-set.new" (func ${'$'}waitable-set.new))
            (export "waitable-set.wait" (func ${'$'}waitable-set.wait))
            (export "waitable-set.drop" (func ${'$'}waitable-set.drop))
            (export "waitable.join" (func ${'$'}waitable.join))
          ))))

          (func (export "run-checks") async (result u64)
            (canon lift (core func ${'$'}m "run-checks") async))
          (func (export "exit-with") (param "code" u8)
            (canon lift (core func ${'$'}m "exit-with")))
        )
    """.trimIndent()

    private fun execWasmTools(vararg args: String) {
        val process = ProcessBuilder(wasmToolsBin, *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "wasm-tools ${args.joinToString(" ")} failed:\n$output"
        }
    }

    @Test
    fun `WASI 0_3 component runs end-to-end on Chicory using runtime-wasi`(@TempDir tempDir: Path) {
        val watFile = tempDir.resolve("wasi-app.wat")
        val wasmFile = tempDir.resolve("wasi-app.wasm")
        val decomposedDir = tempDir.resolve("decomposed")
        val genDir = tempDir.resolve("gen")
        Files.writeString(watFile, componentWat)
        execWasmTools("parse", watFile.toString(), "-o", wasmFile.toString())
        execWasmTools("validate", "--features", "all", wasmFile.toString())

        Decomposer.decompose(wasmFile, decomposedDir)
        val witJson = Decomposer.witJson(decomposedDir)
        val manifestUrl = requireNotNull(javaClass.getResource("/META-INF/wasm2kt/interfaces/wasm2kt.wasi.json"))
        val wasiLibrary = when (manifestUrl.protocol) {
            "jar" -> Paths.get(java.net.URI(manifestUrl.path.substringBefore("!")))
            "file" -> Paths.get(manifestUrl.toURI()).parent.parent.parent.parent
            else -> error("Unsupported manifest URI: $manifestUrl")
        }
        val pkg = "com.example.wasiapp"
        GenerateCommand().parse(listOf("--from-json", witJson.toString(), "--library", wasiLibrary.toString(), "-o", genDir.toString(), "-p", pkg))

        // Generator should map WASI imports to runtime-wasi via META-INF/wasm2kt/interfaces/wasm2kt.wasi.json.
        val pkgDir = genDir.resolve(pkg.replace('.', '/'))
        assertTrue(!Files.exists(pkgDir.resolve("wasi")), "WASI interfaces should map to runtime-wasi instead of regenerating")

        val harnessPath = pkgDir.resolve("Harness.kt")
        Files.writeString(
            harnessPath,
            """
            package $pkg

            import kotlinx.coroutines.runBlocking
            import wasm2kt.runtime.ComponentBackendFactory
            import wasm2kt.runtime.Linker
            import wasm2kt.wasi.addWasi03
            import java.io.ByteArrayOutputStream

            fun runChecksOnChicory(component: ComponentBackendFactory, stdinText: String): String {
                val stdoutBuf = ByteArrayOutputStream()
                val linker = Linker().addWasi03 {
                    args("prog", "--ok")
                    stdin(stdinText.encodeToByteArray())
                    stdout = stdoutBuf
                }
                withSession(component, linker) {
                    runBlocking { runChecks() }
                }
                return stdoutBuf.toString(Charsets.UTF_8)
            }

            fun runExitOnChicory(component: ComponentBackendFactory, code: Int) {
                val linker = Linker().addWasi03()
                withSession(component, linker) {
                    exitWith(code.toUByte())
                }
            }
            """.trimIndent(),
        )

        val classesOut = tempDir.resolve("classes")
        Files.createDirectories(classesOut)
        val sources = Files.walk(genDir).use { s -> s.filter { it.toString().endsWith(".kt") }.map { it.toString() }.toList() }
        val exitCode = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib",
            "-classpath", System.getProperty("java.class.path"),
            "-d", classesOut.toString(),
            *sources.toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exitCode, "Generated WASI app bindings + harness must compile")

        val backendFactory = ChicoryComponent.fromDirectory(decomposedDir)
        val loader = java.net.URLClassLoader(arrayOf(classesOut.toUri().toURL()), javaClass.classLoader)
        val harnessClass = loader.loadClass("$pkg.HarnessKt")

        val runMethod = harnessClass.getDeclaredMethod("runChecksOnChicory", ComponentBackendFactory::class.java, String::class.java)
        val stdout = runMethod.invoke(null, backendFactory, "hello wasi") as String
        assertEquals("hello wasi!", stdout)

        val exitMethod = harnessClass.getDeclaredMethod("runExitOnChicory", ComponentBackendFactory::class.java, Int::class.javaPrimitiveType)
        val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            exitMethod.invoke(null, backendFactory, 42)
        }.cause
        assertTrue(thrown is WasiExitException, "Expected WasiExitException, got $thrown")
        assertEquals(42, thrown.statusCode)
    }

    @Test
    fun `WASI 0_3 filesystem mounts work end-to-end on Chicory and enforce sandboxing`(@TempDir tempDir: Path) {
        val fsComponentWat = """
            (component
              (import "wasi:filesystem/types@0.3.0" (instance ${'$'}fs-types
                (export "descriptor" (type ${'$'}desc (sub resource)))
                (type ${'$'}path-flags-def (flags "symlink-follow"))
                (export "path-flags" (type ${'$'}path-flags (eq ${'$'}path-flags-def)))
                (type ${'$'}open-flags-def (flags "create" "directory" "exclusive" "truncate"))
                (export "open-flags" (type ${'$'}open-flags (eq ${'$'}open-flags-def)))
                (type ${'$'}desc-flags-def (flags "read" "write" "file-integrity-sync" "data-integrity-sync" "requested-write-sync" "mutate-directory"))
                (export "descriptor-flags" (type ${'$'}descriptor-flags (eq ${'$'}desc-flags-def)))
                (type ${'$'}opt-str (option string))
                (type ${'$'}err-def (variant
                  (case "access") (case "already") (case "bad-descriptor") (case "busy")
                  (case "deadlock") (case "quota") (case "exist") (case "file-too-large")
                  (case "illegal-byte-sequence") (case "in-progress") (case "interrupted") (case "invalid")
                  (case "io") (case "is-directory") (case "loop") (case "too-many-links")
                  (case "message-size") (case "name-too-long") (case "no-device") (case "no-entry")
                  (case "no-lock") (case "insufficient-memory") (case "insufficient-space") (case "not-directory")
                  (case "not-empty") (case "not-recoverable") (case "unsupported") (case "no-tty")
                  (case "no-such-device") (case "overflow") (case "not-permitted") (case "pipe")
                  (case "read-only") (case "invalid-seek") (case "text-file-busy") (case "cross-device")
                  (case "other" ${'$'}opt-str)
                ))
                (export "error-code" (type ${'$'}error-code (eq ${'$'}err-def)))
                (type ${'$'}borrow-desc (borrow ${'$'}desc))
                (type ${'$'}own-desc (own ${'$'}desc))
                (type ${'$'}byte-stream (stream u8))
                (type ${'$'}io-res (result (error ${'$'}error-code)))
                (type ${'$'}io-future (future ${'$'}io-res))
                (type ${'$'}read-tuple (tuple ${'$'}byte-stream ${'$'}io-future))
                (type ${'$'}open-res (result ${'$'}own-desc (error ${'$'}error-code)))
                (export "[method]descriptor.open-at" (func async
                  (param "self" ${'$'}borrow-desc)
                  (param "path-flags" ${'$'}path-flags)
                  (param "path" string)
                  (param "open-flags" ${'$'}open-flags)
                  (param "flags" ${'$'}descriptor-flags)
                  (result ${'$'}open-res)))
                (export "[method]descriptor.read-via-stream" (func
                  (param "self" ${'$'}borrow-desc)
                  (param "offset" u64)
                  (result ${'$'}read-tuple)))
                (export "[method]descriptor.write-via-stream" (func
                  (param "self" ${'$'}borrow-desc)
                  (param "data" ${'$'}byte-stream)
                  (param "offset" u64)
                  (result ${'$'}io-future)))
              ))
              (alias export ${'$'}fs-types "descriptor" (type ${'$'}desc-t))
              (alias export ${'$'}fs-types "error-code" (type ${'$'}fs-err))
              (import "wasi:filesystem/preopens@0.3.0" (instance ${'$'}preopens
                (alias outer 1 ${'$'}desc-t (type ${'$'}desc-in))
                (export "descriptor" (type ${'$'}desc-exp (eq ${'$'}desc-in)))
                (type ${'$'}own-desc (own ${'$'}desc-exp))
                (type ${'$'}dir-pair (tuple ${'$'}own-desc string))
                (type ${'$'}dir-list (list ${'$'}dir-pair))
                (export "get-directories" (func (result ${'$'}dir-list)))
              ))
              (type ${'$'}fs-byte-stream (stream u8))
              (type ${'$'}fs-io-res (result (error ${'$'}fs-err)))
              (type ${'$'}fs-io-future (future ${'$'}fs-io-res))

              (core module ${'$'}Mem
                (memory (export "mem") 2)
                (global ${'$'}heap (mut i32) (i32.const 8192))
                (func (export "realloc") (param ${'$'}old i32) (param ${'$'}old_size i32) (param ${'$'}align i32) (param ${'$'}new_size i32) (result i32)
                  (local ${'$'}p i32)
                  (local ${'$'}mask i32)
                  (local.set ${'$'}mask (i32.sub (local.get ${'$'}align) (i32.const 1)))
                  (local.set ${'$'}p (i32.and (i32.add (global.get ${'$'}heap) (local.get ${'$'}mask)) (i32.xor (local.get ${'$'}mask) (i32.const -1))))
                  (global.set ${'$'}heap (i32.add (local.get ${'$'}p) (local.get ${'$'}new_size)))
                  (local.get ${'$'}p))
              )
              (core instance ${'$'}mem (instantiate ${'$'}Mem))

              (core module ${'$'}M
                (import "" "mem" (memory 2))
                (import "" "get-directories" (func ${'$'}get-directories (param i32)))
                (import "" "open-at" (func ${'$'}open-at (param i32 i32) (result i32)))
                (import "" "read-via-stream" (func ${'$'}read-via-stream (param i32 i64 i32)))
                (import "" "write-via-stream" (func ${'$'}write-via-stream (param i32 i32 i64) (result i32)))
                (import "" "drop-descriptor" (func ${'$'}drop-descriptor (param i32)))
                (import "" "task.return.u32" (func ${'$'}return-u32 (param i32)))
                (import "" "stream.new" (func ${'$'}stream.new (result i64)))
                (import "" "stream.read" (func ${'$'}stream.read (param i32 i32 i32) (result i32)))
                (import "" "stream.write" (func ${'$'}stream.write (param i32 i32 i32) (result i32)))
                (import "" "stream.drop-readable" (func ${'$'}stream.drop-readable (param i32)))
                (import "" "stream.drop-writable" (func ${'$'}stream.drop-writable (param i32)))
                (import "" "future.read" (func ${'$'}future.read (param i32 i32) (result i32)))
                (import "" "future.drop-readable" (func ${'$'}future.drop-readable (param i32)))
                (import "" "subtask.drop" (func ${'$'}subtask.drop (param i32)))
                (import "" "waitable-set.new" (func ${'$'}waitable-set.new (result i32)))
                (import "" "waitable-set.wait" (func ${'$'}waitable-set.wait (param i32 i32) (result i32)))
                (import "" "waitable-set.drop" (func ${'$'}waitable-set.drop (param i32)))
                (import "" "waitable.join" (func ${'$'}waitable.join (param i32 i32)))

                ;; Strings at fixed offsets:
                ;; 64..72: "input.txt" (9 bytes)
                ;; 80..89: "output.txt" (10 bytes)
                ;; 96..108: "../escape.txt" (13 bytes)
                (data (i32.const 64) "input.txt")
                (data (i32.const 80) "output.txt")
                (data (i32.const 96) "../escape.txt")

                (func ${'$'}await-subtask (param ${'$'}ret i32)
                  (local ${'$'}sub i32)
                  (local ${'$'}ws i32)
                  (if (i32.ne (i32.and (local.get ${'$'}ret) (i32.const 0xf)) (i32.const 2))
                    (then
                      (local.set ${'$'}sub (i32.shr_u (local.get ${'$'}ret) (i32.const 4)))
                      (local.set ${'$'}ws (call ${'$'}waitable-set.new))
                      (call ${'$'}waitable.join (local.get ${'$'}sub) (local.get ${'$'}ws))
                      (loop ${'$'}w
                        (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (i32.const 256)))
                        (br_if ${'$'}w (i32.ne (i32.load (i32.const 260)) (i32.const 2))))
                      (call ${'$'}subtask.drop (local.get ${'$'}sub))
                      (call ${'$'}waitable-set.drop (local.get ${'$'}ws)))))

                (func ${'$'}await-future (param ${'$'}fh i32) (param ${'$'}ptr i32)
                  (local ${'$'}ret i32)
                  (local ${'$'}ws i32)
                  (local.set ${'$'}ret (call ${'$'}future.read (local.get ${'$'}fh) (local.get ${'$'}ptr)))
                  (if (i32.eq (local.get ${'$'}ret) (i32.const -1))
                    (then
                      (local.set ${'$'}ws (call ${'$'}waitable-set.new))
                      (call ${'$'}waitable.join (local.get ${'$'}fh) (local.get ${'$'}ws))
                      (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (i32.const 320)))
                      (call ${'$'}waitable.join (local.get ${'$'}fh) (i32.const 0))
                      (call ${'$'}waitable-set.drop (local.get ${'$'}ws))))
                  (call ${'$'}future.drop-readable (local.get ${'$'}fh)))

                (func ${'$'}call-open-at
                  (param ${'$'}self i32) (param ${'$'}pflags i32) (param ${'$'}pptr i32) (param ${'$'}plen i32)
                  (param ${'$'}oflags i32) (param ${'$'}dflags i32) (param ${'$'}retptr i32)
                  (i32.store (i32.const 1536) (local.get ${'$'}self))
                  (i32.store8 (i32.const 1540) (local.get ${'$'}pflags))
                  (i32.store (i32.const 1544) (local.get ${'$'}pptr))
                  (i32.store (i32.const 1548) (local.get ${'$'}plen))
                  (i32.store8 (i32.const 1552) (local.get ${'$'}oflags))
                  (i32.store8 (i32.const 1553) (local.get ${'$'}dflags))
                  (call ${'$'}await-subtask (call ${'$'}open-at (i32.const 1536) (local.get ${'$'}retptr))))

                (func (export "exercise-fs")
                  (local ${'$'}list_ptr i32)
                  (local ${'$'}dir_count i32)
                  (local ${'$'}root_dir i32)
                  (local ${'$'}in_fd i32)
                  (local ${'$'}out_fd i32)
                  (local ${'$'}stream_r i32)
                  (local ${'$'}fut i32)
                  (local ${'$'}ws i32)
                  (local ${'$'}ret i32)
                  (local ${'$'}len i32)
                  (local ${'$'}ends i64)

                  ;; 1. get-directories -> expect 1 preopen ("/work")
                  (call ${'$'}get-directories (i32.const 512))
                  (local.set ${'$'}list_ptr (i32.load (i32.const 512)))
                  (local.set ${'$'}dir_count (i32.load (i32.const 516)))
                  (if (i32.ne (local.get ${'$'}dir_count) (i32.const 1)) (then (unreachable)))
                  (local.set ${'$'}root_dir (i32.load (local.get ${'$'}list_ptr)))

                  ;; 2. open-at "input.txt" for read (flags=1 READ)
                  (call ${'$'}call-open-at
                    (local.get ${'$'}root_dir) (i32.const 0)
                    (i32.const 64) (i32.const 9)
                    (i32.const 0) (i32.const 1)
                    (i32.const 1024))
                  (if (i32.load8_u (i32.const 1024)) (then (unreachable)))
                  (local.set ${'$'}in_fd (i32.load (i32.const 1028)))

                  ;; 3. read-via-stream at offset 0 into memory[2048..]
                  (call ${'$'}read-via-stream (local.get ${'$'}in_fd) (i64.const 0) (i32.const 1088))
                  (local.set ${'$'}stream_r (i32.load (i32.const 1088)))
                  (local.set ${'$'}fut (i32.load (i32.const 1092)))
                  (local.set ${'$'}len (i32.const 0))
                  (local.set ${'$'}ws (call ${'$'}waitable-set.new))
                  (call ${'$'}waitable.join (local.get ${'$'}stream_r) (local.get ${'$'}ws))
                  (block ${'$'}done
                    (loop ${'$'}rd
                      (local.set ${'$'}ret (call ${'$'}stream.read (local.get ${'$'}stream_r) (i32.add (i32.const 2048) (local.get ${'$'}len)) (i32.const 64)))
                      (if (i32.eq (local.get ${'$'}ret) (i32.const -1))
                        (then
                          (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (i32.const 1120)))
                          (local.set ${'$'}ret (i32.load (i32.const 1124)))))
                      (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.shr_u (local.get ${'$'}ret) (i32.const 4))))
                      (br_if ${'$'}done (i32.eq (i32.and (local.get ${'$'}ret) (i32.const 0xf)) (i32.const 1)))
                      (br ${'$'}rd)))
                  (call ${'$'}waitable.join (local.get ${'$'}stream_r) (i32.const 0))
                  (call ${'$'}waitable-set.drop (local.get ${'$'}ws))
                  (call ${'$'}stream.drop-readable (local.get ${'$'}stream_r))
                  (call ${'$'}await-future (local.get ${'$'}fut) (i32.const 1152))
                  (if (i32.load8_u (i32.const 1152)) (then (unreachable)))
                  (call ${'$'}drop-descriptor (local.get ${'$'}in_fd))

                  ;; append "!" to read content
                  (i32.store8 (i32.add (i32.const 2048) (local.get ${'$'}len)) (i32.const 33))
                  (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.const 1)))

                  ;; 4. open-at "output.txt" with CREATE|TRUNCATE (9) and WRITE (2)
                  (call ${'$'}call-open-at
                    (local.get ${'$'}root_dir) (i32.const 0)
                    (i32.const 80) (i32.const 10)
                    (i32.const 9) (i32.const 2)
                    (i32.const 1024))
                  (if (i32.load8_u (i32.const 1024)) (then (unreachable)))
                  (local.set ${'$'}out_fd (i32.load (i32.const 1028)))

                  ;; 5. write-via-stream to output.txt
                  (local.set ${'$'}ends (call ${'$'}stream.new))
                  (local.set ${'$'}stream_r (i32.wrap_i64 (local.get ${'$'}ends)))
                  (local.set ${'$'}fut (call ${'$'}write-via-stream (local.get ${'$'}out_fd) (local.get ${'$'}stream_r) (i64.const 0)))
                  (local.set ${'$'}ws (call ${'$'}waitable-set.new))
                  (call ${'$'}waitable.join (i32.wrap_i64 (i64.shr_u (local.get ${'$'}ends) (i64.const 32))) (local.get ${'$'}ws))
                  (local.set ${'$'}ret (call ${'$'}stream.write (i32.wrap_i64 (i64.shr_u (local.get ${'$'}ends) (i64.const 32))) (i32.const 2048) (local.get ${'$'}len)))
                  (if (i32.eq (local.get ${'$'}ret) (i32.const -1))
                    (then (drop (call ${'$'}waitable-set.wait (local.get ${'$'}ws) (i32.const 1216)))))
                  (call ${'$'}waitable.join (i32.wrap_i64 (i64.shr_u (local.get ${'$'}ends) (i64.const 32))) (i32.const 0))
                  (call ${'$'}waitable-set.drop (local.get ${'$'}ws))
                  (call ${'$'}stream.drop-writable (i32.wrap_i64 (i64.shr_u (local.get ${'$'}ends) (i64.const 32))))
                  (call ${'$'}await-future (local.get ${'$'}fut) (i32.const 1248))
                  (if (i32.load8_u (i32.const 1248)) (then (unreachable)))
                  (call ${'$'}drop-descriptor (local.get ${'$'}out_fd))

                  ;; 6. open-at "../escape.txt" MUST fail with error-code::not-permitted (30)
                  (call ${'$'}call-open-at
                    (local.get ${'$'}root_dir) (i32.const 0)
                    (i32.const 96) (i32.const 13)
                    (i32.const 0) (i32.const 1)
                    (i32.const 1024))
                  (if (i32.ne (i32.load8_u (i32.const 1024)) (i32.const 1)) (then (unreachable)))
                  (if (i32.ne (i32.load8_u (i32.const 1028)) (i32.const 30)) (then (unreachable)))

                  (call ${'$'}return-u32 (local.get ${'$'}len)))
              )

              (canon lower (func ${'$'}preopens "get-directories") (memory (core memory ${'$'}mem "mem")) (realloc (core func ${'$'}mem "realloc")) (core func ${'$'}get-directories))
              (canon lower (func ${'$'}fs-types "[method]descriptor.open-at") async (memory (core memory ${'$'}mem "mem")) (realloc (core func ${'$'}mem "realloc")) (core func ${'$'}open-at))
              (canon lower (func ${'$'}fs-types "[method]descriptor.read-via-stream") (memory (core memory ${'$'}mem "mem")) (core func ${'$'}read-via-stream))
              (canon lower (func ${'$'}fs-types "[method]descriptor.write-via-stream") (core func ${'$'}write-via-stream))
              (canon resource.drop ${'$'}desc-t (core func ${'$'}drop-descriptor))
              (canon task.return (result u32) (core func ${'$'}return-u32))
              (canon stream.new ${'$'}fs-byte-stream (core func ${'$'}stream.new))
              (canon stream.read ${'$'}fs-byte-stream async (memory (core memory ${'$'}mem "mem")) (core func ${'$'}stream.read))
              (canon stream.write ${'$'}fs-byte-stream async (memory (core memory ${'$'}mem "mem")) (core func ${'$'}stream.write))
              (canon stream.drop-readable ${'$'}fs-byte-stream (core func ${'$'}stream.drop-readable))
              (canon stream.drop-writable ${'$'}fs-byte-stream (core func ${'$'}stream.drop-writable))
              (canon future.read ${'$'}fs-io-future async (memory (core memory ${'$'}mem "mem")) (realloc (core func ${'$'}mem "realloc")) (core func ${'$'}future.read))
              (canon future.drop-readable ${'$'}fs-io-future (core func ${'$'}future.drop-readable))
              (canon subtask.drop (core func ${'$'}subtask.drop))
              (canon waitable-set.new (core func ${'$'}waitable-set.new))
              (canon waitable-set.wait (memory (core memory ${'$'}mem "mem")) (core func ${'$'}waitable-set.wait))
              (canon waitable-set.drop (core func ${'$'}waitable-set.drop))
              (canon waitable.join (core func ${'$'}waitable.join))

              (core instance ${'$'}m (instantiate ${'$'}M (with "" (instance
                (export "mem" (memory ${'$'}mem "mem"))
                (export "get-directories" (func ${'$'}get-directories))
                (export "open-at" (func ${'$'}open-at))
                (export "read-via-stream" (func ${'$'}read-via-stream))
                (export "write-via-stream" (func ${'$'}write-via-stream))
                (export "drop-descriptor" (func ${'$'}drop-descriptor))
                (export "task.return.u32" (func ${'$'}return-u32))
                (export "stream.new" (func ${'$'}stream.new))
                (export "stream.read" (func ${'$'}stream.read))
                (export "stream.write" (func ${'$'}stream.write))
                (export "stream.drop-readable" (func ${'$'}stream.drop-readable))
                (export "stream.drop-writable" (func ${'$'}stream.drop-writable))
                (export "future.read" (func ${'$'}future.read))
                (export "future.drop-readable" (func ${'$'}future.drop-readable))
                (export "subtask.drop" (func ${'$'}subtask.drop))
                (export "waitable-set.new" (func ${'$'}waitable-set.new))
                (export "waitable-set.wait" (func ${'$'}waitable-set.wait))
                (export "waitable-set.drop" (func ${'$'}waitable-set.drop))
                (export "waitable.join" (func ${'$'}waitable.join))
              ))))

              (func (export "exercise-fs") async (result u32)
                (canon lift (core func ${'$'}m "exercise-fs") async))
            )
        """.trimIndent()

        val watFile = tempDir.resolve("fs-app.wat")
        val wasmFile = tempDir.resolve("fs-app.wasm")
        val decomposedDir = tempDir.resolve("decomposed")
        val genDir = tempDir.resolve("gen")
        val workDir = Files.createDirectories(tempDir.resolve("work"))
        Files.writeString(tempDir.resolve("escape.txt"), "do-not-read")
        Files.writeString(workDir.resolve("input.txt"), "hello fs")

        Files.writeString(watFile, fsComponentWat)
        execWasmTools("parse", watFile.toString(), "-o", wasmFile.toString())
        execWasmTools("validate", "--features", "all", wasmFile.toString())

        Decomposer.decompose(wasmFile, decomposedDir)
        val witJson = Decomposer.witJson(decomposedDir)
        val manifestUrl = requireNotNull(javaClass.getResource("/META-INF/wasm2kt/interfaces/wasm2kt.wasi.json"))
        val wasiLibrary = when (manifestUrl.protocol) {
            "jar" -> Paths.get(java.net.URI(manifestUrl.path.substringBefore("!")))
            "file" -> Paths.get(manifestUrl.toURI()).parent.parent.parent.parent
            else -> error("Unsupported manifest URI: $manifestUrl")
        }
        val pkg = "com.example.wasifs"
        GenerateCommand().parse(listOf("--from-json", witJson.toString(), "--library", wasiLibrary.toString(), "-o", genDir.toString(), "-p", pkg))

        val pkgDir = genDir.resolve(pkg.replace('.', '/'))
        val harnessPath = pkgDir.resolve("FsHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package $pkg

            import kotlinx.coroutines.runBlocking
            import wasm2kt.runtime.ComponentBackendFactory
            import wasm2kt.runtime.Linker
            import wasm2kt.wasi.addWasi03
            import java.nio.file.Path

            fun runFsOnChicory(component: ComponentBackendFactory, workDir: Path): Int {
                val linker = Linker().addWasi03 {
                    mounts {
                        readWrite("/work", workDir)
                    }
                }
                return withSession(component, linker) {
                    runBlocking { exerciseFs().toInt() }
                }
            }
            """.trimIndent(),
        )

        val classesOut = tempDir.resolve("classes")
        Files.createDirectories(classesOut)
        val sources = Files.walk(genDir).use { s -> s.filter { it.toString().endsWith(".kt") }.map { it.toString() }.toList() }
        val exitCode = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib",
            "-classpath", System.getProperty("java.class.path"),
            "-d", classesOut.toString(),
            *sources.toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exitCode, "Generated WASI fs bindings + harness must compile")

        val backendFactory = ChicoryComponent.fromDirectory(decomposedDir)
        val loader = java.net.URLClassLoader(arrayOf(classesOut.toUri().toURL()), javaClass.classLoader)
        val harnessClass = loader.loadClass("$pkg.FsHarnessKt")
        val runMethod = harnessClass.getDeclaredMethod("runFsOnChicory", ComponentBackendFactory::class.java, Path::class.java)
        val writtenLen = runMethod.invoke(null, backendFactory, workDir) as Int

        assertEquals("hello fs!".length, writtenLen)
        assertEquals("hello fs!", Files.readString(workDir.resolve("output.txt")))
    }
}
