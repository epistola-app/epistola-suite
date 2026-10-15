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
 * number of faces ([maxFaces]).
 *
 * This replaces iText's own font cache, which [FontCache] used to fill by parsing with
 * `cached = true`: a static map keyed by font content that never evicts, so every face a JVM ever
 * rendered or validated stayed in memory. Parsing on every render is no answer either: an Inter
 * letter renders three times slower and allocates ten times more.
 *
 * A parsed face's heap cost does not follow its file size. iText expands the kerning tables, so a
 * 400 KB Inter face holds about 25 MB and a 270 KB JetBrains Mono face about 1 MB. Each entry is
 * therefore weighed by what its parse allocated ([FontWeigher]), within ~1.3× of what it retains.
 *
 * ## Eviction: a segmented LRU
 *
 * A newly parsed face always enters the *probation* segment, so a render finds again the faces it
 * has just parsed. A face that a *different* render asks for moves to the *protected* segment, which
 * may take [PROTECTED_SHARE] of each limit; past that, its least recently used face drops back to
 * probation instead of out. Eviction takes probation's least recently used face first and touches
 * protected faces only when probation holds nothing else, so a burst of one-off fonts cannot push out
 * the fonts many renders use.
 *
 * Promotion counts renders, not lookups, because one render looks a face up several times (band
 * measuring, then each layout pass): each render takes its own [Scope], and only a lookup from a
 * scope other than the one that parsed the face, or last promoted it, counts as reuse.
 *
 * (Caffeine's W-TinyLFU was tried and rejected: it admits a new entry only when it is used more often
 * than what it would replace, so a freshly parsed face — always needed again within the same render —
 * was refused and parsed anew, live, on every render.)
 *
 * Entries are keyed by the SHA-256 of the font content; callers that know it pass it (core stores it
 * per face), so it is computed only for bytes that arrive without one. A face's measured weight is
 * remembered by that key after the face is evicted, so each content is measured once per JVM. A face heavier than [maxBytes]
 * is parsed and returned but not kept. Thread-safe: renders share one instance, and renders missing
 * the same face at once wait for a single parse.
 */
class FontProgramCache(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxFaces: Int = DEFAULT_MAX_FACES,
    private val weigher: FontWeigher = FontWeigher.ALLOCATED_BYTES,
    /** Parses without measuring, for content whose weight is already known. */
    private val parser: (ByteArray) -> FontProgram = { FontProgramFactory.createFont(it, false) },
) {
    init {
        require(maxBytes >= 0) { "maxBytes must not be negative, was $maxBytes" }
        require(maxFaces >= 0) { "maxFaces must not be negative, was $maxFaces" }
    }

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

    private class Entry(val program: FontProgram, val weight: Long, var lastScope: Any)

    private class Segment {
        /** Insertion-ordered; [touch] re-inserts, so iteration starts at the least recently used. */
        val entries = LinkedHashMap<String, Entry>()
        var bytes = 0L

        fun add(key: String, entry: Entry) {
            entries[key] = entry
            bytes += entry.weight
        }

        fun remove(key: String): Entry? = entries.remove(key)?.also { bytes -= it.weight }

        fun touch(key: String, entry: Entry) {
            entries.remove(key)
            entries[key] = entry
        }

        fun leastRecent(except: String? = null): String? = entries.keys.firstOrNull { it != except }
    }

    private val enabled = maxBytes > 0 && maxFaces > 0
    private val protectedMaxBytes = (maxBytes * PROTECTED_SHARE).toLong()
    private val protectedMaxFaces = (maxFaces * PROTECTED_SHARE).toInt()

    // Guarded by `this`.
    private val probation = Segment()
    private val protectedSegment = Segment()
    private val parsing = HashMap<String, CompletableFuture<Entry>>()

    /**
     * Measured weights by content hash, kept after their face is evicted: content never changes for a
     * hash, so a face re-parsed later needs no second measurement. Tiny per entry, and bounded.
     */
    private val knownWeights = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > MAX_KNOWN_WEIGHTS
    }
    private var hits = 0L
    private var misses = 0L
    private var promotions = 0L
    private var byteLimitEvictions = 0L
    private var faceLimitEvictions = 0L

    private fun programFor(scope: Scope, bytes: ByteArray, contentHash: String?): FontProgram {
        if (!enabled) return parser(bytes)
        val key = contentHash ?: contentHash(bytes)

        val pending: CompletableFuture<Entry>
        val parsesHere: Boolean
        synchronized(this) {
            lookUp(key, scope)?.let {
                hits++
                return it.program
            }
            val inFlight = parsing[key]
            parsesHere = inFlight == null
            pending = inFlight ?: CompletableFuture<Entry>().also { parsing[key] = it }
            if (parsesHere) misses++ else hits++
        }
        if (!parsesHere) return pending.join().program

        // Parse outside the lock: it takes milliseconds, and renders needing other faces should not wait.
        val entry = try {
            val known = synchronized(this) { knownWeights[key] }
            if (known != null) {
                Entry(parser(bytes), known, scope)
            } else {
                weigher.parse(bytes).let { (program, weight) -> Entry(program, weight, scope) }
            }
        } catch (e: Throwable) {
            synchronized(this) { parsing.remove(key) }
            pending.completeExceptionally(e)
            throw e
        }
        synchronized(this) {
            parsing.remove(key)
            knownWeights[key] = entry.weight
            admit(key, entry)
        }
        pending.complete(entry)
        return entry.program
    }

    private fun lookUp(key: String, scope: Scope): Entry? {
        protectedSegment.entries[key]?.let { entry ->
            protectedSegment.touch(key, entry)
            entry.lastScope = scope
            return entry
        }
        val entry = probation.entries[key] ?: return null
        if (entry.lastScope === scope) {
            probation.touch(key, entry)
        } else {
            probation.remove(key)
            protectedSegment.add(key, entry)
            entry.lastScope = scope
            promotions++
            demoteOverProtectedShare()
        }
        return entry
    }

    private fun admit(key: String, entry: Entry) {
        if (entry.weight > maxBytes) {
            byteLimitEvictions++
            return
        }
        probation.add(key, entry)
        evictOverLimits(keep = key)
    }

    private fun demoteOverProtectedShare() {
        while (protectedSegment.entries.size > protectedMaxFaces || protectedSegment.bytes > protectedMaxBytes) {
            val key = protectedSegment.leastRecent() ?: return
            probation.add(key, protectedSegment.remove(key)!!)
        }
    }

    private fun evictOverLimits(keep: String) {
        while (true) {
            val overFaces = faces > maxFaces
            val overBytes = bytes > maxBytes
            if (!overFaces && !overBytes) return
            val fromProbation = probation.leastRecent(except = keep)
            val victim = fromProbation ?: protectedSegment.leastRecent() ?: return
            (if (fromProbation != null) probation else protectedSegment).remove(victim)
            if (overFaces) faceLimitEvictions++ else byteLimitEvictions++
        }
    }

    /** Current number of cached faces. */
    val faces: Int get() = synchronized(this) { probation.entries.size + protectedSegment.entries.size }

    /** Current estimated heap held by cached faces, in bytes. */
    val bytes: Long get() = synchronized(this) { probation.bytes + protectedSegment.bytes }

    /** Faces in the protected segment: those more than one render has used. */
    val protectedFaces: Int get() = synchronized(this) { protectedSegment.entries.size }

    /** Estimated heap held by the protected segment, in bytes. */
    val protectedBytes: Long get() = synchronized(this) { protectedSegment.bytes }

    /** Lookups, promotions and evictions since this cache was created, for metrics. */
    fun stats(): Stats = synchronized(this) { Stats(hits, misses, promotions, byteLimitEvictions, faceLimitEvictions) }

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
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024
        const val DEFAULT_MAX_FACES: Int = 64

        /** Measured weights remembered after eviction; ~100 bytes each. */
        private const val MAX_KNOWN_WEIGHTS = 4096

        /** The share of each limit the protected segment may take. */
        const val PROTECTED_SHARE = 0.8

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

        /** Threads that may parse at once when the caller is a virtual thread; also caps the parsing memory spike. */
        private const val PARSE_THREADS = 2

        /**
         * Platform threads for parses called from virtual threads, reused across parses. Daemon threads,
         * so the pool never holds the JVM open.
         */
        private val parseThreads: ExecutorService by lazy {
            Executors.newFixedThreadPool(PARSE_THREADS, Thread.ofPlatform().daemon(true).name("font-parse-", 0).factory())
        }

        /**
         * Weighs a face by the bytes its parse allocated, within ~1.3× of what it retains. The JVM
         * reports allocation only for platform threads, so a parse called from a virtual thread (as
         * renders are) runs on one of [PARSE_THREADS] pooled platform threads; this happens only on a
         * cache miss. Falls back to [FILE_SIZE_MULTIPLIER] × file size where the JVM cannot measure.
         */
        val ALLOCATED_BYTES = FontWeigher { bytes ->
            if (Thread.currentThread().isVirtual) onPlatformThread { measuredParse(bytes) } else measuredParse(bytes)
        }

        private fun measuredParse(bytes: ByteArray): Pair<FontProgram, Long> {
            val threads = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
                ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
            val before = threads?.currentThreadAllocatedBytes?.takeIf { it >= 0 }
            val program = FontProgramFactory.createFont(bytes, false)
            val allocated = before?.let { threads.currentThreadAllocatedBytes - it }
            return program to (allocated?.takeIf { it > 0 } ?: (bytes.size * FILE_SIZE_MULTIPLIER))
        }

        private fun <T> onPlatformThread(block: () -> T): T = try {
            parseThreads.submit(Callable(block)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
