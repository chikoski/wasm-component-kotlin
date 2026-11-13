package wasm2kt.wasi

import kotlinx.coroutines.delay
import wasm2kt.runtime.Linker
import wasm2kt.wasi.wasi.clocks.monotonic_clock.Mark
import wasm2kt.wasi.wasi.clocks.monotonic_clock.MonotonicClock
import wasm2kt.wasi.wasi.clocks.monotonic_clock.add
import wasm2kt.wasi.wasi.clocks.system_clock.Instant
import wasm2kt.wasi.wasi.clocks.system_clock.SystemClock
import wasm2kt.wasi.wasi.clocks.system_clock.add
import wasm2kt.wasi.wasi.clocks.timezone.Timezone
import wasm2kt.wasi.wasi.clocks.timezone.add
import wasm2kt.wasi.wasi.clocks.types.Duration
import java.time.Clock
import java.time.ZoneId
import java.time.Instant as JavaInstant

/** Host implementation of `wasi:clocks/monotonic-clock@0.3.0`. */
class WasiMonotonicClock(
    private val nanoTime: () -> Long = System::nanoTime,
    private val resolutionNanos: Duration = 1uL,
) : MonotonicClock {
    private val origin: Long = nanoTime()

    override fun now(): Mark = (nanoTime() - origin).coerceAtLeast(0L).toULong()

    override fun getResolution(): Duration = resolutionNanos

    override suspend fun waitUntil(`when`: Mark) {
        val current = now()
        if (`when` > current) {
            waitFor(`when` - current)
        }
    }

    override suspend fun waitFor(howLong: Duration) {
        if (howLong == 0uL) return
        val millis = (howLong / 1_000_000uL).coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
        val remNanos = (howLong % 1_000_000uL).toInt()
        delay(if (millis == 0L && remNanos > 0) 1L else millis)
    }
}

/** Host implementation of `wasi:clocks/system-clock@0.3.0`. */
class WasiSystemClock(
    private val clock: Clock = Clock.systemUTC(),
    private val resolutionNanos: Duration = 1_000_000uL,
) : SystemClock {
    override fun now(): Instant {
        val instant = clock.instant()
        return Instant(seconds = instant.epochSecond, nanoseconds = instant.nano.toUInt())
    }

    override fun getResolution(): Duration = resolutionNanos
}

/** Host implementation of `wasi:clocks/timezone@0.3.0`. */
class WasiTimezone(
    private val zoneSupplier: () -> ZoneId = ZoneId::systemDefault,
) : Timezone {
    override fun ianaId(): String? {
        val id = zoneSupplier().id
        return if (isIanaIdentifier(id)) id else null
    }

    override fun utcOffset(`when`: Instant): Long? = runCatching {
        val instant = JavaInstant.ofEpochSecond(`when`.seconds, `when`.nanoseconds.toLong())
        zoneSupplier().rules.getOffset(instant).totalSeconds.toLong() * 1_000_000_000L
    }.getOrNull()

    override fun toDebugString(): String = zoneSupplier().id

    private fun isIanaIdentifier(id: String): Boolean {
        if (id.isEmpty()) return false
        if (id == "UTC" || id == "GMT") return true
        return '/' in id && !id.startsWith("+") && !id.startsWith("-")
    }
}

/** Registers `wasi:clocks/monotonic-clock`, `wasi:clocks/system-clock`, and `wasi:clocks/timezone`. */
fun Linker.addWasiClocks(
    monotonicClock: MonotonicClock = WasiMonotonicClock(),
    systemClock: SystemClock = WasiSystemClock(),
    timezone: Timezone = WasiTimezone(),
): Linker = apply {
    add(monotonicClock)
    add(systemClock)
    add(timezone)
}
