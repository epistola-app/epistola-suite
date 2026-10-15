// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.itextpdf.io.font.FontProgram
import com.itextpdf.io.font.FontProgramFactory
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsed fonts shared across renders, bounded by both an estimated heap size ([maxBytes]) and a
 * number of faces ([maxFaces]).
 *
 * This replaces iText's own font cache, which [FontCache] used to fill by parsing with
 * `cached = true`: a static map keyed by font content that never evicts, so every face a JVM ever
 * rendered or validated stayed in memory. Parsing on every render is no answer either: an Inter
 * letter renders three times slower and allocates ten times more.
 *
 * A parsed face's heap cost does not follow its file size. iText expands the kerning tables, so a
 * 400 KB Inter face holds about 25 MB and a 270 KB JetBrains Mono face about 1 MB. Each entry is
 * therefore weighed by what its parse allocated ([FontWeigher]), an upper bound on what it retains.
 *
 * Caffeine enforces [maxBytes] on the measured weights; [maxFaces] is enforced here, by evicting the
 * entries Caffeine ranks coldest once a newly parsed face takes the count over. Eviction is
 * Caffeine's W-TinyLFU, which keeps faces used often as well as recently, so a one-off font does not
 * push out the fonts most templates use. Under concurrent renders the count can pass [maxFaces] for
 * the moment between an insert and its check; the byte limit holds throughout.
 *
 * Entries are keyed by the SHA-256 of the font content: callers that know it pass it (core stores
 * it per face), so it is computed only for bytes that arrive without one.
 *
 * A face heavier than [maxBytes] is parsed and returned but not kept. Thread-safe: renders share one
 * instance, and renders missing the same face at once wait for a single parse.
 */
class FontProgramCache(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxFaces: Int = DEFAULT_MAX_FACES,
    private val weigher: FontWeigher = FontWeigher.ALLOCATED_BYTES,
) {
    init {
        require(maxBytes >= 0) { "maxBytes must not be negative, was $maxBytes" }
        require(maxFaces >= 0) { "maxFaces must not be negative, was $maxFaces" }
    }

    private class Entry(val program: FontProgram, val weight: Long)

    private val enabled = maxBytes > 0 && maxFaces > 0

    private val cache: Cache<String, Entry> = Caffeine.newBuilder()
        .maximumWeight(maxBytes)
        .weigher<String, Entry> { _, entry -> entry.weight.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt() }
        // Evict on the calling thread, so the limits hold as soon as a render adds a face.
        .executor(Runnable::run)
        .recordStats()
        .build()

    private val faceLimitEvictions = AtomicLong()

    /**
     * The parsed font for [bytes], from the cache when the same content was parsed before.
     * [contentHash] must be the lowercase hex SHA-256 of [bytes]; it is computed when null.
     */
    fun program(bytes: ByteArray, contentHash: String? = null): FontProgram {
        if (!enabled) return weigher.parse(bytes).first
        var parsed = false
        val entry = cache.get(contentHash ?: contentHash(bytes)) {
            parsed = true
            weigher.parse(bytes).let { (program, weight) -> Entry(program, weight) }
        }
        if (parsed) evictOverFaceLimit()
        return entry.program
    }

    /** Current number of cached faces. */
    val faces: Int get() = cache.also { it.cleanUp() }.estimatedSize().toInt()

    /** Current estimated heap held by cached faces, in bytes. */
    val bytes: Long get() = cache.also { it.cleanUp() }.asMap().values.sumOf { it.weight }

    private fun evictOverFaceLimit() {
        cache.cleanUp()
        val excess = cache.estimatedSize() - maxFaces
        if (excess <= 0) return
        cache.policy().eviction().ifPresent { eviction ->
            val coldest = eviction.coldest(excess.toInt()).keys
            cache.invalidateAll(coldest)
            faceLimitEvictions.addAndGet(coldest.size.toLong())
        }
    }

    /** Lookups and evictions since this cache was created, for metrics. */
    fun stats(): Stats = cache.stats().let { caffeine ->
        Stats(
            hits = caffeine.hitCount(),
            misses = caffeine.missCount(),
            byteLimitEvictions = caffeine.evictionCount(),
            faceLimitEvictions = faceLimitEvictions.get(),
        )
    }

    /** Cumulative counts: lookups served from the cache or parsed, and faces evicted per limit. */
    data class Stats(
        val hits: Long,
        val misses: Long,
        val byteLimitEvictions: Long,
        val faceLimitEvictions: Long,
    )

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024
        const val DEFAULT_MAX_FACES: Int = 64

        /** Lowercase hex SHA-256 of [bytes], the key a face is cached under. */
        fun contentHash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/** Parses font bytes, uncached by iText, and estimates the heap the parsed font holds. */
fun interface FontWeigher {
    fun parse(bytes: ByteArray): Pair<FontProgram, Long>

    companion object {
        /** Used when the JVM cannot report per-thread allocation; covers the worst bundled face (~65×). */
        private const val FILE_SIZE_MULTIPLIER = 64L

        /**
         * Weighs a face by the bytes its parse allocated on this thread, which bounds what it retains
         * from above. Falls back to [FILE_SIZE_MULTIPLIER] × file size where the JVM cannot measure.
         */
        val ALLOCATED_BYTES = FontWeigher { bytes ->
            val threads = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
                ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
            val threadId = Thread.currentThread().threadId()
            val before = threads?.getThreadAllocatedBytes(threadId)
            val program = FontProgramFactory.createFont(bytes, false)
            val allocated = before?.let { threads.getThreadAllocatedBytes(threadId) - it }
            program to (allocated?.takeIf { it > 0 } ?: (bytes.size * FILE_SIZE_MULTIPLIER))
        }
    }
}
