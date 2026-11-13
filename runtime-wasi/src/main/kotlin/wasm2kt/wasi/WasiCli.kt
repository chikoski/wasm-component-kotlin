package wasm2kt.wasi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.Linker
import wasm2kt.wasi.wasi.cli.environment.Environment
import wasm2kt.wasi.wasi.cli.environment.add
import wasm2kt.wasi.wasi.cli.exit.Exit
import wasm2kt.wasi.wasi.cli.exit.add
import wasm2kt.wasi.wasi.cli.stderr.Stderr
import wasm2kt.wasi.wasi.cli.stderr.add
import wasm2kt.wasi.wasi.cli.stdin.Stdin
import wasm2kt.wasi.wasi.cli.stdin.add
import wasm2kt.wasi.wasi.cli.stdout.Stdout
import wasm2kt.wasi.wasi.cli.stdout.add
import wasm2kt.wasi.wasi.cli.terminal_input.TerminalInput
import wasm2kt.wasi.wasi.cli.terminal_input.TerminalInputInterface
import wasm2kt.wasi.wasi.cli.terminal_input.add
import wasm2kt.wasi.wasi.cli.terminal_output.TerminalOutput
import wasm2kt.wasi.wasi.cli.terminal_output.TerminalOutputInterface
import wasm2kt.wasi.wasi.cli.terminal_output.add
import wasm2kt.wasi.wasi.cli.terminal_stderr.TerminalStderr
import wasm2kt.wasi.wasi.cli.terminal_stderr.add
import wasm2kt.wasi.wasi.cli.terminal_stdin.TerminalStdin
import wasm2kt.wasi.wasi.cli.terminal_stdin.add
import wasm2kt.wasi.wasi.cli.terminal_stdout.TerminalStdout
import wasm2kt.wasi.wasi.cli.terminal_stdout.add
import wasm2kt.wasi.wasi.cli.types.ErrorCode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneId

/**
 * Thrown when a component invokes `wasi:cli/exit.exit` or `wasi:cli/exit.exit-with-code`.
 * Extends [ComponentTrapException] so the runtime terminates the call and surfaces [statusCode].
 */
class WasiExitException(val statusCode: Int) : ComponentTrapException("WASI exit with status code $statusCode")

/** Host implementation of `wasi:cli/environment@0.3.0`. */
class WasiEnvironment(
    private val environment: List<Pair<String, String>> = emptyList(),
    private val arguments: List<String> = emptyList(),
    private val initialCwd: String? = null,
) : Environment {
    override fun getEnvironment(): List<Tuple2<String, String>> =
        environment.map { (k, v) -> Tuple2(k, v) }

    override fun getArguments(): List<String> = arguments

    override fun getInitialCwd(): String? = initialCwd
}

/** Host implementation of `wasi:cli/exit@0.3.0`. */
class WasiExit : Exit {
    override fun exit(status: WitResult<Unit, Unit>): Nothing =
        throw WasiExitException(if (status is WitResult.Ok) 0 else 1)

    override fun exitWithCode(statusCode: UByte): Nothing =
        throw WasiExitException(statusCode.toInt())
}

/** Host implementation of `wasi:cli/stdin@0.3.0`. */
class WasiStdin(
    private val input: () -> InputStream = { ByteArrayInputStream(ByteArray(0)) },
) : Stdin {
    constructor(stream: InputStream) : this({ stream })
    constructor(bytes: ByteArray) : this({ ByteArrayInputStream(bytes) })

    override fun readViaStream(): Tuple2<Flow<ByteArray>, Deferred<WitResult<Unit, ErrorCode>>> {
        val stream = flow {
            val streamIn = input()
            val buffer = ByteArray(8192)
            while (true) {
                val n = streamIn.read(buffer)
                if (n < 0) break
                if (n > 0) emit(buffer.copyOf(n))
            }
        }
        return Tuple2(stream, CompletableDeferred(WitResult.Ok(Unit)))
    }
}

private object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

@OptIn(DelicateCoroutinesApi::class)
private fun writeStreamToOutput(
    output: OutputStream,
    data: Flow<ByteArray>,
): Deferred<WitResult<Unit, ErrorCode>> = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
    try {
        data.collect { bytes ->
            if (bytes.isNotEmpty()) {
                output.write(bytes)
                output.flush()
            }
        }
        WitResult.Ok(Unit)
    } catch (_: IOException) {
        WitResult.Err(ErrorCode.IO)
    }
}

/** Host implementation of `wasi:cli/stdout@0.3.0`. */
class WasiStdout(
    private val output: OutputStream = NullOutputStream,
) : Stdout {
    override fun writeViaStream(`data`: Flow<ByteArray>): Deferred<WitResult<Unit, ErrorCode>> =
        writeStreamToOutput(output, `data`)
}

/** Host implementation of `wasi:cli/stderr@0.3.0`. */
class WasiStderr(
    private val output: OutputStream = NullOutputStream,
) : Stderr {
    override fun writeViaStream(`data`: Flow<ByteArray>): Deferred<WitResult<Unit, ErrorCode>> =
        writeStreamToOutput(output, `data`)
}

/** Host implementation of `wasi:cli/terminal-input@0.3.0`. */
class WasiTerminalInput : TerminalInputInterface

/** Host implementation of `wasi:cli/terminal-output@0.3.0`. */
class WasiTerminalOutput : TerminalOutputInterface

/** Host implementation of `wasi:cli/terminal-stdin@0.3.0`. */
class WasiTerminalStdin(
    private val terminal: () -> TerminalInput? = { null },
) : TerminalStdin {
    override fun getTerminalStdin(): TerminalInput? = terminal()
}

/** Host implementation of `wasi:cli/terminal-stdout@0.3.0`. */
class WasiTerminalStdout(
    private val terminal: () -> TerminalOutput? = { null },
) : TerminalStdout {
    override fun getTerminalStdout(): TerminalOutput? = terminal()
}

/** Host implementation of `wasi:cli/terminal-stderr@0.3.0`. */
class WasiTerminalStderr(
    private val terminal: () -> TerminalOutput? = { null },
) : TerminalStderr {
    override fun getTerminalStderr(): TerminalOutput? = terminal()
}

/** Configuration bundle for WASI 0.3 core interfaces (`wasi:random`, `wasi:clocks`, `wasi:cli`). */
class Wasi03 private constructor(
    val random: wasm2kt.wasi.wasi.random.random.Random,
    val insecureRandom: wasm2kt.wasi.wasi.random.insecure.Insecure,
    val insecureSeed: wasm2kt.wasi.wasi.random.insecure_seed.InsecureSeed,
    val monotonicClock: wasm2kt.wasi.wasi.clocks.monotonic_clock.MonotonicClock,
    val systemClock: wasm2kt.wasi.wasi.clocks.system_clock.SystemClock,
    val timezone: wasm2kt.wasi.wasi.clocks.timezone.Timezone,
    val environment: Environment,
    val exit: Exit,
    val stdin: Stdin,
    val stdout: Stdout,
    val stderr: Stderr,
    val terminalInput: TerminalInputInterface,
    val terminalOutput: TerminalOutputInterface,
    val terminalStdin: TerminalStdin,
    val terminalStdout: TerminalStdout,
    val terminalStderr: TerminalStderr,
) {
    /** Registers all configured WASI 0.3 host implementations on [linker]. */
    fun registerOn(linker: Linker): Linker = linker.apply {
        addWasiRandom(random = random, insecure = insecureRandom, insecureSeed = insecureSeed)
        addWasiClocks(monotonicClock = monotonicClock, systemClock = systemClock, timezone = timezone)
        addWasiCli(
            environment = environment,
            exit = exit,
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            terminalInput = terminalInput,
            terminalOutput = terminalOutput,
            terminalStdin = terminalStdin,
            terminalStdout = terminalStdout,
            terminalStderr = terminalStderr,
        )
    }

    /** Builder for [Wasi03] host configuration. */
    class Builder {
        var environment: List<Pair<String, String>> = emptyList()
        var arguments: List<String> = emptyList()
        var initialCwd: String? = null
        var stdin: () -> InputStream = { ByteArrayInputStream(ByteArray(0)) }
        var stdout: OutputStream = NullOutputStream
        var stderr: OutputStream = NullOutputStream
        var secureRandom: SecureRandom = SecureRandom()
        var clock: Clock = Clock.systemUTC()
        var zoneSupplier: () -> ZoneId = ZoneId::systemDefault

        fun env(vararg entries: Pair<String, String>) {
            environment = entries.toList()
        }

        fun args(vararg values: String) {
            arguments = values.toList()
        }

        fun stdin(stream: InputStream) {
            stdin = { stream }
        }

        fun stdin(bytes: ByteArray) {
            stdin = { ByteArrayInputStream(bytes) }
        }

        fun build(): Wasi03 = Wasi03(
            random = WasiRandom(secureRandom),
            insecureRandom = WasiInsecureRandom(),
            insecureSeed = WasiInsecureSeed(),
            monotonicClock = WasiMonotonicClock(),
            systemClock = WasiSystemClock(clock),
            timezone = WasiTimezone(zoneSupplier),
            environment = WasiEnvironment(environment, arguments, initialCwd),
            exit = WasiExit(),
            stdin = WasiStdin(stdin),
            stdout = WasiStdout(stdout),
            stderr = WasiStderr(stderr),
            terminalInput = WasiTerminalInput(),
            terminalOutput = WasiTerminalOutput(),
            terminalStdin = WasiTerminalStdin(),
            terminalStdout = WasiTerminalStdout(),
            terminalStderr = WasiTerminalStderr(),
        )
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

/** Registers the `wasi:cli` interfaces on this [Linker]. */
fun Linker.addWasiCli(
    environment: Environment = WasiEnvironment(),
    exit: Exit = WasiExit(),
    stdin: Stdin = WasiStdin(),
    stdout: Stdout = WasiStdout(),
    stderr: Stderr = WasiStderr(),
    terminalInput: TerminalInputInterface = WasiTerminalInput(),
    terminalOutput: TerminalOutputInterface = WasiTerminalOutput(),
    terminalStdin: TerminalStdin = WasiTerminalStdin(),
    terminalStdout: TerminalStdout = WasiTerminalStdout(),
    terminalStderr: TerminalStderr = WasiTerminalStderr(),
): Linker = apply {
    add(environment)
    add(exit)
    add(stdin)
    add(stdout)
    add(stderr)
    add(terminalInput)
    add(terminalOutput)
    add(terminalStdin)
    add(terminalStdout)
    add(terminalStderr)
}

/** Registers WASI 0.3 core interfaces (`random`, `clocks`, `cli`) on this [Linker]. */
fun Linker.addWasi03(configure: Wasi03.Builder.() -> Unit = {}): Linker =
    Wasi03.builder().apply(configure).build().registerOn(this)
