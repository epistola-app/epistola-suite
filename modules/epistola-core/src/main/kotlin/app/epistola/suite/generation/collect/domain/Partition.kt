package app.epistola.suite.generation.collect.domain

/**
 * Routing-key → partition math for the v0.3 generation result collection mechanism.
 *
 * `TOTAL_PARTITIONS` is hard-coded: changing it after deployment requires a multi-step
 * "create new partitioned table, copy data, swap" migration, so we commit to 64 up
 * front. 64 gives generous headroom for both Valtimo node count (up to 64 nodes get
 * ≥1 partition each) and per-partition query isolation in the multi-level partitioned
 * `generation_results` table.
 *
 * The hash algorithm — MurmurHash3 x86 32-bit with seed 0 — must match the contract's
 * client helper byte-for-byte. The contract ships an inline implementation in
 * `client-kotlin-spring-restclient/.../ResultCollector.kt`; we ship the same algorithm
 * here. `PartitionTest` cross-checks against a frozen set of (input, expected hash)
 * pairs so any drift in either implementation surfaces immediately.
 */
object Partition {
    const val TOTAL_PARTITIONS: Int = 64

    /**
     * Compute the partition number for a routing key.
     *
     *   partition = (murmur3_x86_32(routingKey, seed=0) and 0x7FFFFFFF) mod TOTAL_PARTITIONS
     *
     * The `and 0x7FFFFFFF` masks the sign bit so the result is always a non-negative
     * integer before the modulo. Required because `Int % positive` in Kotlin can return
     * a negative number when the dividend is negative.
     */
    fun partitionFor(routingKey: String): Int {
        val hash = murmur3x86_32(routingKey.toByteArray(Charsets.UTF_8), seed = 0)
        return (hash and 0x7FFFFFFF) % TOTAL_PARTITIONS
    }
}

/**
 * MurmurHash3 x86 32-bit with configurable seed.
 *
 * Bit-for-bit equivalent to:
 *   - Guava: `Hashing.murmur3_32_fixed(seed).hashBytes(data).asInt()`
 *   - The contract's `murmur3x86_32(data, seed)` in
 *     `client-kotlin-spring-restclient/.../ResultCollector.kt`
 *
 * Internal because consumers should call [Partition.partitionFor]; this lower-level
 * function is exposed so the cross-implementation test can drive both this and the
 * contract helper through the exact same algorithm description.
 */
@Suppress("MagicNumber", "ktlint:standard:function-naming")
internal fun murmur3x86_32(data: ByteArray, seed: Int): Int {
    val c1 = 0xcc9e2d51.toInt()
    val c2 = 0x1b873593
    var h1 = seed
    val len = data.size
    val nblocks = len / 4

    for (i in 0 until nblocks) {
        val idx = i * 4
        var k1 = (data[idx].toInt() and 0xFF) or
            ((data[idx + 1].toInt() and 0xFF) shl 8) or
            ((data[idx + 2].toInt() and 0xFF) shl 16) or
            ((data[idx + 3].toInt() and 0xFF) shl 24)

        k1 *= c1
        k1 = Integer.rotateLeft(k1, 15)
        k1 *= c2
        h1 = h1 xor k1
        h1 = Integer.rotateLeft(h1, 13)
        h1 = h1 * 5 + 0xe6546b64.toInt()
    }

    val tail = nblocks * 4
    var k1 = 0
    when (len and 3) {
        3 -> {
            k1 = k1 xor ((data[tail + 2].toInt() and 0xFF) shl 16)
            k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8)
            k1 = k1 xor (data[tail].toInt() and 0xFF)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
        2 -> {
            k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8)
            k1 = k1 xor (data[tail].toInt() and 0xFF)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
        1 -> {
            k1 = k1 xor (data[tail].toInt() and 0xFF)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
    }

    h1 = h1 xor len
    h1 = h1 xor (h1 ushr 16)
    h1 *= 0x85ebca6b.toInt()
    h1 = h1 xor (h1 ushr 13)
    h1 *= 0xc2b2ae35.toInt()
    h1 = h1 xor (h1 ushr 16)

    return h1
}
