// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.io.font.FontProgram
import com.itextpdf.io.font.FontProgramFactory
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Parsed fonts shared across renders, bounded by both an estimated heap size ([maxBytes]) and a
 * number of faces ([maxFaces]). Eviction is a [SegmentedLruCache] with one [Scope] per render, so a
 * face counts as reused only when a second render asks for it.
 *
 * This replaces iText's own font cache, which [FontCache] used to fill by parsing with
 * `cached = true`: a static map keyed by font content that never evicts, so every face a JVM ever
 * rendered or validated stayed in memory. Parsing on every render is no answer either: an Inter
 * letter renders three times slower and allocates ten times more.
 *
 * A parsed face's heap cost does not follow its file size: iText expands the kerning tables, so a
 * 400 KB Inter face holds about 25 MB and a 270 KB JetBrains Mono face about 1 MB. Each face is
 * weighed by what parsing it allocated ([FontParser.MEASURING]), within ~1.3× of what it keeps, and
 * that weight is remembered by content after eviction, so each content is measured once per JVM.
 *
 * Faces are keyed by the SHA-256 of their content; callers that know it pass it (core stores it per
 * face), so it is computed only for bytes that arrive without one. Thread-safe: renders share one
 * instance, and renders missing the same face at once wait for a single parse.
 *
 * (Caffeine's W-TinyLFU was tried and dropped: it admits a new entry only when it is used more often
 * than what it would replace, so a freshly parsed face — always needed again within the same render
 * — was refused and parsed anew on every render.)
 */
class FontProgramCache(
    maxBytes: Long = DEFAULT_MAX_BYTES,
    maxFaces: Int = DEFAULT_MAX_FACES,
    private val parser: FontParser = FontParser.MEASURING,
) {
    /** One render's view of the cache: lookups through the same scope count as one use. */
    inner class Scope internal constructor() {
        /**
         * The parsed font for [bytes], from the cache when the same content was parsed before.
         * [contentHash] must be the lowercase hex SHA-256 of [bytes]; it is computed when null.
         */
        fun program(bytes: ByteArray, contentHash: String? = null): FontProgram = programFor(this, bytes, contentHash)
    }

    /** A scope for one render; every [FontCache] of that render shares it. */
    fun scope(): Scope = Scope()

    // All state below is guarded by `this`.
    private val lru = SegmentedLruCache<String, FontProgram>(maxBytes, maxFaces)
    private val parsing = HashMap<String, CompletableFuture<FontProgram>>()
    private var hits = 0L
    private var misses = 0L

    /** Measured weights by content hash, kept after eviction: content never changes for a hash. Bounded. */
    private val knownWeights = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > MAX_KNOWN_WEIGHTS
    }

    val maxBytes: Long get() = lru.maxWeight
    val maxFaces: Int get() = lru.maxEntries

    private fun programFor(scope: Scope, bytes: ByteArray, contentHash: String?): FontProgram {
        if (maxBytes == 0L || maxFaces == 0) return parser.parse(bytes, measure = false).program
        val key = contentHash ?: contentHash(bytes)

        var parsedElsewhere: CompletableFuture<FontProgram>? = null
        val parsedHere = CompletableFuture<FontProgram>()
        var knownWeight: Long? = null
        synchronized(this) {
            lru.get(key, scope)?.let {
                hits++
                return it
            }
            parsedElsewhere = parsing[key]
            if (parsedElsewhere != null) {
                hits++
            } else {
                misses++
                parsing[key] = parsedHere
                knownWeight = knownWeights[key]
            }
        }
        parsedElsewhere?.let { return it.join() }

        // Parse outside the lock: it takes milliseconds, and renders needing other faces should not wait.
        val parsed = try {
            parser.parse(bytes, measure = knownWeight == null)
        } catch (e: Throwable) {
            synchronized(this) { parsing.remove(key) }
            parsedHere.completeExceptionally(e)
            throw e
        }
        val weight = knownWeight ?: checkNotNull(parsed.weight) { "a measured parse must report its weight" }
        synchronized(this) {
            parsing.remove(key)
            knownWeights[key] = weight
            lru.put(key, parsed.program, weight, scope)
        }
        parsedHere.complete(parsed.program)
        return parsed.program
    }

    /** Current number of cached faces. */
    val faces: Int get() = synchronized(this) { lru.size }

    /** Current estimated heap held by cached faces, in bytes. */
    val bytes: Long get() = synchronized(this) { lru.weight }

    /** Faces in the protected segment: those more than one render has used. */
    val protectedFaces: Int get() = synchronized(this) { lru.protectedSize }

    /** Estimated heap held by the protected segment, in bytes. */
    val protectedBytes: Long get() = synchronized(this) { lru.protectedWeight }

    /** Lookups, promotions and evictions since this cache was created, for metrics. */
    fun stats(): Stats = synchronized(this) {
        Stats(hits, misses, lru.promotions, lru.weightLimitEvictions, lru.entryLimitEvictions)
    }

    /**
     * Cumulative counts: lookups served from the cache (or a parse already under way) or parsed,
     * faces promoted to the protected segment, and faces evicted per limit. A face too heavy to keep
     * counts as evicted by the byte limit.
     */
    data class Stats(
        val hits: Long,
        val misses: Long,
        val promotions: Long,
        val byteLimitEvictions: Long,
        val faceLimitEvictions: Long,
    )

    companion object {
        /**
         * Room for every bundled face (~260 MB measured) with headroom: at 256 MB a node cycling through
         * all bundled families evicted each face just before it was needed again, live.
         */
        const val DEFAULT_MAX_BYTES: Long = 384L * 1024 * 1024
        const val DEFAULT_MAX_FACES: Int = 64

        /** Measured weights remembered after eviction; ~100 bytes each. */
        private const val MAX_KNOWN_WEIGHTS = 4096

        /** Lowercase hex SHA-256 of [bytes], the key a face is cached under. */
        fun contentHash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/** A parsed font, with the heap it holds when the parse was measured. */
class ParsedFont(val program: FontProgram, val weight: Long?)

/** Parses font bytes outside iText's never-evicting font cache, measuring the result when asked. */
fun interface FontParser {
    fun parse(bytes: ByteArray, measure: Boolean): ParsedFont

    companion object {
        /** Used when the JVM cannot report allocation; covers the worst bundled face (~65×). */
        private const val FILE_SIZE_MULTIPLIER = 64L

        /** Threads that may parse at once for virtual-thread callers; also caps the parsing memory spike. */
        private const val PARSE_THREADS = 2

        /** Pooled daemon platform threads for measured parses called from virtual threads. */
        private val parseThreads: ExecutorService by lazy {
            Executors.newFixedThreadPool(PARSE_THREADS, Thread.ofPlatform().daemon(true).name("font-parse-", 0).factory())
        }

        /**
         * Measures a face by the bytes its parse allocated, within ~1.3× of what it keeps. The JVM
         * reports allocation only for platform threads, so a measured parse called from a virtual
         * thread (as renders are) runs on one of [PARSE_THREADS] pooled platform threads. Falls back
         * to [FILE_SIZE_MULTIPLIER] × file size where the JVM cannot measure at all.
         */
        val MEASURING = FontParser { bytes, measure ->
            when {
                !measure -> ParsedFont(FontProgramFactory.createFont(bytes, false), weight = null)
                Thread.currentThread().isVirtual -> onPlatformThread { measuredParse(bytes) }
                else -> measuredParse(bytes)
            }
        }

        private fun measuredParse(bytes: ByteArray): ParsedFont {
            val threads = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
                ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
            val before = threads?.currentThreadAllocatedBytes?.takeIf { it >= 0 }
            val program = FontProgramFactory.createFont(bytes, false)
            val allocated = before?.let { threads.currentThreadAllocatedBytes - it }
            return ParsedFont(program, allocated?.takeIf { it > 0 } ?: (bytes.size * FILE_SIZE_MULTIPLIER))
        }

        private fun <T> onPlatformThread(block: () -> T): T = try {
            parseThreads.submit(Callable(block)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
