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
}
