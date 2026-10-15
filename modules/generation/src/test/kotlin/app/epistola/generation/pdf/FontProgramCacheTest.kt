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

/** The font side of the cache; the eviction policy itself is [SegmentedLruCacheTest]'s. */
class FontProgramCacheTest {

    private val base = javaClass.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()

    /** Distinct content per [n], so each is its own cache entry; trailing bytes do not affect parsing. */
    private fun face(n: Int): ByteArray = base + ByteArray(n + 1) { n.toByte() }

    /** Parses for real, weighing measured faces at [weight], and counts parses and measurements. */
    private class CountingParser(private val weight: Long = 1) : FontParser {
        val parses = AtomicInteger()
        val measured = AtomicInteger()
        override fun parse(bytes: ByteArray, measure: Boolean): ParsedFont {
            parses.incrementAndGet()
            if (measure) measured.incrementAndGet()
            return ParsedFont(FontProgramFactory.createFont(bytes, false), weight.takeIf { measure })
        }
    }

    @Test
    fun `the same content is parsed once, across renders`() {
        val parser = CountingParser()
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, parser = parser)

        val first = cache.scope().program(face(1))
        val again = cache.scope().program(face(1).copyOf())

        assertSame(first, again)
        assertEquals(1, parser.parses.get())
    }

    @Test
    fun `a render finds the face it just parsed`() {
        val parser = CountingParser(weight = 30)
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, parser = parser)
        repeat(5) { cache.scope().let { render -> (1..3).forEach { render.program(face(it)) } } }
        val before = parser.parses.get()

        val render = cache.scope()
        render.program(face(9))
        render.program(face(9))

        assertEquals(1, parser.parses.get() - before)
    }

    @Test
    fun `a face is measured once, even when it is evicted and parsed again`() {
        val parser = CountingParser()
        val cache = FontProgramCache(maxBytes = 1_000, maxFaces = 1, parser = parser)

        cache.scope().program(face(1))
        cache.scope().program(face(2)) // evicts face 1
        cache.scope().program(face(1))

        assertEquals(3, parser.parses.get())
        assertEquals(2, parser.measured.get(), "face 1 was re-parsed with its remembered weight")
    }

    @Test
    fun `a supplied content hash is the cache key`() {
        val parser = CountingParser()
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, parser = parser)
        val bytes = face(1)

        cache.scope().program(bytes)
        cache.scope().program(bytes, FontProgramCache.contentHash(bytes))
        assertEquals(1, parser.parses.get(), "the computed and the supplied hash name the same entry")

        cache.scope().program(bytes, "another-hash")
        assertEquals(2, parser.parses.get(), "a supplied hash is trusted, not recomputed")
    }

    @Test
    fun `zero faces turns the cache off, and nothing is measured`() {
        val parser = CountingParser()
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 0, parser = parser)

        cache.scope().program(face(1))
        cache.scope().program(face(1))

        assertEquals(2, parser.parses.get())
        assertEquals(0, parser.measured.get())
        assertEquals(0, cache.faces)
    }

    @Test
    fun `renders missing the same face at once parse it once`() {
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parses = AtomicInteger()
        val slowParser = FontParser { bytes, _ ->
            parses.incrementAndGet()
            parsing.countDown()
            release.await(5, TimeUnit.SECONDS)
            ParsedFont(FontProgramFactory.createFont(bytes, false), 10L)
        }
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, parser = slowParser)
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
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10) { bytes, _ ->
            if (attempts.incrementAndGet() == 1) error("corrupt")
            ParsedFont(FontProgramFactory.createFont(bytes, false), 1L)
        }

        assertFailsWith<IllegalStateException> { cache.scope().program(face(1)) }
        cache.scope().program(face(1))

        assertEquals(2, attempts.get())
        assertEquals(1, cache.faces)
    }

    @Test
    fun `stats count hits, misses, promotions and evictions per limit`() {
        val byFaces = FontProgramCache(maxBytes = 1_000, maxFaces = 1, parser = CountingParser())
        val render = byFaces.scope()
        render.program(face(1))
        render.program(face(1))
        render.program(face(2))

        assertEquals(FontProgramCache.Stats(hits = 1, misses = 2, promotions = 0, byteLimitEvictions = 0, faceLimitEvictions = 1), byFaces.stats())

        val byBytes = FontProgramCache(maxBytes = 100, maxFaces = 10, parser = CountingParser(weight = 60))
        byBytes.scope().program(face(1))
        byBytes.scope().program(face(2))

        assertEquals(FontProgramCache.Stats(hits = 0, misses = 2, promotions = 0, byteLimitEvictions = 1, faceLimitEvictions = 0), byBytes.stats())
    }

    @Test
    fun `the measuring parser reports what a parse allocates, not the file size`() {
        val weight = FontParser.MEASURING.parse(base, measure = true).weight!!

        // Liberation Sans is ~400 KB on disk; parsed it allocates several MB, far under the 64x fallback.
        assertTrue(weight > base.size && weight < base.size * 64L, "weight $weight for a ${base.size}-byte file")
    }

    @Test
    fun `the measuring parser measures on a virtual thread too, on reused platform threads`() {
        // The JVM reports no allocation for virtual threads, and renders run on them.
        val weights = (1..5).map {
            var weight = 0L
            Thread.ofVirtual().start { weight = FontParser.MEASURING.parse(base, measure = true).weight!! }.join()
            weight
        }

        weights.forEach { assertTrue(it > base.size && it < base.size * 64L, "weight $it for a ${base.size}-byte file, fallback ${base.size * 64L}") }
        val parseThreads = Thread.getAllStackTraces().keys.count { it.name.startsWith("font-parse-") }
        assertTrue(parseThreads in 1..2, "$parseThreads parse threads for five parses")
    }
}
