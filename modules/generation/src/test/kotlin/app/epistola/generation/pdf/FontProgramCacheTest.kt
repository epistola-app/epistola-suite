// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.io.font.FontProgramFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontProgramCacheTest {

    private val base = javaClass.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()

    /** Distinct content per [n], so each is its own cache entry; trailing bytes do not affect parsing. */
    private fun face(n: Int): ByteArray = base + ByteArray(n + 1) { n.toByte() }

    /** Weighs every face at [weight]; counts measured parses and all parses, plain ones included. */
    private class FixedWeigher(private val weight: Long) : FontWeigher {
        val parses = AtomicInteger()
        val measured = AtomicInteger()
        override fun parse(bytes: ByteArray) = FontProgramFactory.createFont(bytes, false).also {
            parses.incrementAndGet()
            measured.incrementAndGet()
        } to weight
        val parser: (ByteArray) -> com.itextpdf.io.font.FontProgram = { FontProgramFactory.createFont(it, false).also { parses.incrementAndGet() } }
    }

    private fun cache(maxBytes: Long, maxFaces: Int, weigher: FixedWeigher) = FontProgramCache(maxBytes, maxFaces, weigher, weigher.parser)

    @Test
    fun `the same content is parsed once, across renders`() {
        val weigher = FixedWeigher(10)
        val cache = cache(100, 10, weigher)

        val first = cache.scope().program(face(1))
        val again = cache.scope().program(face(1).copyOf())

        assertSame(first, again)
        assertEquals(1, weigher.parses.get())
    }

    @Test
    fun `a render finds the face it just parsed, even in a full cache of busier faces`() {
        // Caffeine's W-TinyLFU refused such a newcomer for lack of history, so it was parsed again
        // within the same render.
        val weigher = FixedWeigher(30)
        val cache = cache(100, 10, weigher)
        repeat(5) { cache.scope().let { render -> (1..3).forEach { render.program(face(it)) } } }
        val parsesBefore = weigher.parses.get()

        val render = cache.scope()
        render.program(face(9))
        render.program(face(9))

        assertEquals(1, weigher.parses.get() - parsesBefore)
    }

    @Test
    fun `a burst of one-off fonts does not evict faces several renders use`() {
        val weigher = FixedWeigher(1)
        val cache = cache(1_000, 5, weigher)
        val popular = listOf(face(1), face(2))
        repeat(2) { cache.scope().let { render -> popular.forEach { render.program(it) } } }
        assertEquals(2, cache.protectedFaces)

        (10..30).forEach { cache.scope().program(face(it)) }
        val parsesBefore = weigher.parses.get()
        cache.scope().let { render -> popular.forEach { render.program(it) } }

        assertEquals(0, weigher.parses.get() - parsesBefore, "the popular faces were still cached")
        assertEquals(5, cache.faces)
    }

    @Test
    fun `lookups within one render do not promote a face`() {
        val cache = cache(1_000, 5, FixedWeigher(1))
        val render = cache.scope()

        repeat(4) { render.program(face(1)) }
        assertEquals(0, cache.protectedFaces)

        cache.scope().program(face(1))
        assertEquals(1, cache.protectedFaces)
        assertEquals(1, cache.stats().promotions)
    }

    @Test
    fun `the protected segment keeps to its share by demoting, not evicting`() {
        // 80% of 5 faces: at most 4 protected.
        val weigher = FixedWeigher(1)
        val cache = cache(1_000, 5, weigher)
        repeat(2) { cache.scope().let { render -> (1..5).forEach { render.program(face(it)) } } }

        assertEquals(4, cache.protectedFaces)
        assertEquals(5, cache.faces, "the demoted face is still cached")
        assertEquals(5, weigher.parses.get())
    }

    @Test
    fun `small faces are held to the face limit`() {
        val cache = cache(1_000, 3, FixedWeigher(1))

        repeat(10) { cache.scope().program(face(it)) }

        assertEquals(3, cache.faces)
    }

    @Test
    fun `heavy faces are held to the byte limit`() {
        val cache = cache(100, 10, FixedWeigher(40))

        repeat(10) { cache.scope().program(face(it)) }

        assertEquals(2, cache.faces)
        assertEquals(80, cache.bytes)
    }

    @Test
    fun `small faces count at their measured weight`() {
        // 60 + 9 x 1 = 69 bytes in 10 faces: within both limits, so every face stays.
        val weights = ArrayDeque(listOf(60L) + List(9) { 1L })
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, weigher = FontWeigher { bytes -> FontProgramFactory.createFont(bytes, false) to weights.removeFirst() })

        repeat(10) { cache.scope().program(face(it)) }

        assertEquals(10, cache.faces)
        assertEquals(69, cache.bytes)
    }

    @Test
    fun `a face is measured once, even when it is evicted and parsed again`() {
        val weigher = FixedWeigher(1)
        val cache = cache(1_000, 1, weigher)

        cache.scope().program(face(1))
        cache.scope().program(face(2)) // evicts face 1
        cache.scope().program(face(1))

        assertEquals(3, weigher.parses.get())
        assertEquals(2, weigher.measured.get(), "face 1 was re-parsed with its remembered weight")
    }

    @Test
    fun `a supplied content hash is the cache key`() {
        val weigher = FixedWeigher(1)
        val cache = cache(100, 10, weigher)
        val bytes = face(1)

        cache.scope().program(bytes)
        cache.scope().program(bytes, FontProgramCache.contentHash(bytes))
        assertEquals(1, weigher.parses.get(), "the computed and the supplied hash name the same entry")

        cache.scope().program(bytes, "another-hash")
        assertEquals(2, weigher.parses.get(), "a supplied hash is trusted, not recomputed")
    }

    @Test
    fun `a face heavier than the byte limit is returned but not kept`() {
        val weigher = FixedWeigher(50)
        val cache = cache(25, 10, weigher)

        cache.scope().program(face(1))
        cache.scope().program(face(1))

        assertEquals(0, cache.faces)
        assertEquals(2, weigher.parses.get())
        assertEquals(1, weigher.measured.get())
        assertEquals(2, cache.stats().byteLimitEvictions)
    }

    @Test
    fun `zero faces turns the cache off`() {
        val weigher = FixedWeigher(1)
        val cache = cache(100, 0, weigher)

        cache.scope().program(face(1))
        cache.scope().program(face(1))

        assertEquals(2, weigher.parses.get())
        assertEquals(0, weigher.measured.get(), "with the cache off nothing needs a weight")
        assertEquals(0, cache.faces)
    }

    @Test
    fun `renders missing the same face at once parse it once`() {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parses = AtomicInteger()
        val slowWeigher = FontWeigher { bytes ->
            parses.incrementAndGet()
            parsing.countDown()
            release.await(5, TimeUnit.SECONDS)
            FontProgramFactory.createFont(bytes, false) to 10L
        }
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, weigher = slowWeigher)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val first = executor.submit<Any> { cache.scope().program(face(1)) }
            parsing.await(5, TimeUnit.SECONDS)
            val others = (1..3).map { executor.submit<Any> { cache.scope().program(face(1)) } }
            release.countDown()

            val programs = (listOf(first) + others).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, parses.get())
            programs.forEach { assertSame(programs.first(), it) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a failed parse is not cached and the next lookup parses again`() {
        val attempts = AtomicInteger()
        val cache = FontProgramCache(
            maxBytes = 100,
            maxFaces = 10,
            weigher = FontWeigher { bytes ->
                if (attempts.incrementAndGet() == 1) error("corrupt")
                FontProgramFactory.createFont(bytes, false) to 1L
            },
        )

        assertFailsWith<IllegalStateException> { cache.scope().program(face(1)) }
        cache.scope().program(face(1))

        assertEquals(2, attempts.get())
        assertEquals(1, cache.faces)
    }

    @Test
    fun `stats count hits, misses, promotions and evictions per limit`() {
        val byFaces = cache(1_000, 1, FixedWeigher(1))
        val render = byFaces.scope()
        render.program(face(1))
        render.program(face(1))
        render.program(face(2))

        assertEquals(FontProgramCache.Stats(hits = 1, misses = 2, promotions = 0, byteLimitEvictions = 0, faceLimitEvictions = 1), byFaces.stats())

        val byBytes = cache(100, 10, FixedWeigher(60))
        byBytes.scope().program(face(1))
        byBytes.scope().program(face(2))

        assertEquals(FontProgramCache.Stats(hits = 0, misses = 2, promotions = 0, byteLimitEvictions = 1, faceLimitEvictions = 0), byBytes.stats())
    }

    @Test
    fun `negative limits are refused`() {
        assertFailsWith<IllegalArgumentException> { FontProgramCache(maxBytes = -1) }
        assertFailsWith<IllegalArgumentException> { FontProgramCache(maxFaces = -1) }
    }

    @Test
    fun `the allocation weigher reports what a parse allocates, not the file size`() {
        val (_, weight) = FontWeigher.ALLOCATED_BYTES.parse(base)

        // Liberation Sans is ~400 KB on disk; parsed it allocates several MB, far under the 64x fallback.
        assertTrue(weight > base.size && weight < base.size * 64L, "weight $weight for a ${base.size}-byte file")
    }

    @Test
    fun `the allocation weigher measures on a virtual thread too, on reused platform threads`() {
        // The JVM reports no allocation for virtual threads, and renders run on them.
        val weights = (1..5).map {
            var weight = 0L
            Thread.ofVirtual().start { weight = FontWeigher.ALLOCATED_BYTES.parse(base).second }.join()
            weight
        }

        weights.forEach { assertTrue(it > base.size && it < base.size * 64L, "weight $it for a ${base.size}-byte file, fallback ${base.size * 64L}") }
        val parseThreads = Thread.getAllStackTraces().keys.count { it.name.startsWith("font-parse-") }
        assertTrue(parseThreads in 1..2, "$parseThreads parse threads for five parses")
    }
}
