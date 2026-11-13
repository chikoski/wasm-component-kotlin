package wasm2kt.wasi

import wasm2kt.runtime.Linker
import wasm2kt.wasi.wasi.random.insecure.Insecure
import wasm2kt.wasi.wasi.random.insecure.add
import wasm2kt.wasi.wasi.random.insecure_seed.InsecureSeed
import wasm2kt.wasi.wasi.random.insecure_seed.add
import wasm2kt.wasi.wasi.random.random.Random
import wasm2kt.wasi.wasi.random.random.add
import java.security.SecureRandom
import kotlin.random.Random as KotlinRandom

private const val MAX_RANDOM_CHUNK = 65536

/** Host implementation of `wasi:random/random@0.3.0` backed by [SecureRandom]. */
class WasiRandom(
    private val random: SecureRandom = SecureRandom(),
) : Random {
    override fun getRandomBytes(maxLen: ULong): ByteArray {
        if (maxLen == 0uL) return ByteArray(0)
        val len = minOf(maxLen, MAX_RANDOM_CHUNK.toULong()).toInt()
        return ByteArray(len).also(random::nextBytes)
    }

    override fun getRandomU64(): ULong = random.nextLong().toULong()
}

/** Host implementation of `wasi:random/insecure@0.3.0`. */
class WasiInsecureRandom(
    private val random: KotlinRandom = KotlinRandom.Default,
) : Insecure {
    override fun getInsecureRandomBytes(maxLen: ULong): ByteArray {
        if (maxLen == 0uL) return ByteArray(0)
        val len = minOf(maxLen, MAX_RANDOM_CHUNK.toULong()).toInt()
        return random.nextBytes(len)
    }

    override fun getInsecureRandomU64(): ULong = random.nextLong().toULong()
}

/** Host implementation of `wasi:random/insecure-seed@0.3.0`. */
class WasiInsecureSeed(
    private val seed: Pair<ULong, ULong> = KotlinRandom.Default.let { it.nextLong().toULong() to it.nextLong().toULong() },
) : InsecureSeed {
    override fun getInsecureSeed(): Tuple2<ULong, ULong> = Tuple2(seed.first, seed.second)
}

/** Registers `wasi:random/random`, `wasi:random/insecure`, and `wasi:random/insecure-seed`. */
fun Linker.addWasiRandom(
    random: Random = WasiRandom(),
    insecure: Insecure = WasiInsecureRandom(),
    insecureSeed: InsecureSeed = WasiInsecureSeed(),
): Linker = apply {
    add(random)
    add(insecure)
    add(insecureSeed)
}
