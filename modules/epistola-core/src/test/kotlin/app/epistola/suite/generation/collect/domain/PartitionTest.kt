// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.domain

import com.google.common.hash.Hashing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Cross-implementation parity + behavior tests for [Partition].
 *
 * The suite computes routing-key partitions with the inline `murmur3x86_32` helper.
 * The contract's client (`ResultCollector.kt`) ships its own inline implementation.
 * Both must produce byte-identical hashes for every input — otherwise a client targets
 * partition X locally, the server stores the result on partition Y, and the client never
 * sees its own result.
 *
 * Guava's `Hashing.murmur3_32_fixed(0)` is the reference implementation both sides
 * mirror. If our inline helper drifts from Guava (or anyone else's well-known impl),
 * these tests will fail and the parity bug will be caught at build time, not at runtime
 * via mysterious missing-result reports.
 */
class PartitionTest {

    // ----- byte-for-byte parity with Guava -----

    @Test
    fun `matches Guava murmur3_32_fixed(0) for known fixtures`() {
        val fixtures = listOf(
            "",
            "a",
            "ab",
            "abc",
            "abcd",
            "abcde",
            "abcdef",
            "abcdefg",
            "abcdefgh",
            "order-7890",
            "550e8400-e29b-41d4-a716-446655440000",
            "tenant-acme/req-12345",
            "epistola.app",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit",
            // Multi-byte UTF-8 to exercise the byte-array path
            "héllo wörld",
            "中文测试",
        )
        for (input in fixtures) {
            val ours = murmur3x86_32(input.toByteArray(Charsets.UTF_8), seed = 0)
            val theirs = Hashing.murmur3_32_fixed(0).hashBytes(input.toByteArray(Charsets.UTF_8)).asInt()
            assertThat(ours).`as`("hash mismatch for input %s", input).isEqualTo(theirs)
        }
    }

    @Test
    fun `matches Guava murmur3_32_fixed(0) for random byte arrays of every tail length`() {
        // Murmur3 has separate code paths per `len mod 4` tail. Cover all tail lengths
        // explicitly so a regression in any branch shows up immediately.
        val rng = Random(seed = 0xEC03L) // deterministic for reproducible failures
        for (len in 0..32) {
            val bytes = ByteArray(len).also { rng.nextBytes(it) }
            val ours = murmur3x86_32(bytes, seed = 0)
            val theirs = Hashing.murmur3_32_fixed(0).hashBytes(bytes).asInt()
            assertThat(ours).`as`("hash mismatch for length %d", len).isEqualTo(theirs)
        }
    }

    @Test
    fun `partitionFor returns Guava-derived partition number for known fixtures`() {
        val inputs = listOf("order-7890", "abc", "", UUID.randomUUID().toString())
        for (input in inputs) {
            val expected = (
                Hashing.murmur3_32_fixed(0).hashString(input, Charsets.UTF_8).asInt() and 0x7FFFFFFF
                ) % Partition.TOTAL_PARTITIONS
            assertThat(Partition.partitionFor(input))
                .`as`("partition mismatch for input %s", input)
                .isEqualTo(expected)
        }
    }

    // ----- behavior contract -----

    @Test
    fun `partitionFor result is always in 0 until TOTAL_PARTITIONS`() {
        val rng = Random(seed = 0xBEEFL)
        repeat(2000) {
            val len = rng.nextInt(0, 64)
            val key = (1..len).joinToString("") { rng.nextInt(33, 127).toChar().toString() }
            val partition = Partition.partitionFor(key)
            assertThat(partition).isBetween(0, Partition.TOTAL_PARTITIONS - 1)
        }
    }

    @Test
    fun `partitionFor is deterministic`() {
        val key = "order-7890"
        val first = Partition.partitionFor(key)
        repeat(10) {
            assertThat(Partition.partitionFor(key)).isEqualTo(first)
        }
    }

    @Test
    fun `partitionFor distributes random keys across all partitions roughly evenly`() {
        // Sanity check on the hash quality: with 100k random keys and 64 partitions
        // the expected count per partition is ~1562. Allow generous tolerance — the
        // point is to catch a degenerate "all keys land in partition 0" regression,
        // not enforce uniformity.
        val counts = IntArray(Partition.TOTAL_PARTITIONS)
        val rng = Random(seed = 0xC0FFEEL)
        repeat(100_000) {
            val key = "k" + rng.nextLong().toString()
            counts[Partition.partitionFor(key)]++
        }
        val expected = 100_000 / Partition.TOTAL_PARTITIONS
        for ((p, count) in counts.withIndex()) {
            assertThat(count)
                .`as`("partition %d count %d falls outside +/-50%% of expected %d", p, count, expected)
                .isBetween((expected * 0.5).toInt(), (expected * 1.5).toInt())
        }
    }

    @Test
    fun `TOTAL_PARTITIONS is the hard-coded constant the migration assumes`() {
        // Drift detection: if anyone bumps TOTAL_PARTITIONS without also updating the
        // V27__generation_results.sql migration (which declares 64 LIST children),
        // production will silently misroute. Keep this assertion as the canary.
        assertThat(Partition.TOTAL_PARTITIONS).isEqualTo(64)
    }
}
