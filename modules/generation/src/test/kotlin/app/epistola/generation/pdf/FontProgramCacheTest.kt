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

    /** Weighs every face at [weight] and counts parses. */
    private class FixedWeigher(private val weight: Long) : FontWeigher {
        val parses = AtomicInteger()
        override fun parse(bytes: ByteArray) = FontProgramFactory.createFont(bytes, false).also { parses.incrementAndGet() } to weight
    }

    @Test
    fun `the same content is parsed once`() {
        val weigher = FixedWeigher(10)
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, weigher = weigher)

        val first = cache.program(face(1))
        val again = cache.program(face(1).copyOf())

        assertSame(first, again)
        assertEquals(1, weigher.parses.get())
    }

    @Test
    fun `small faces are held to the face limit`() {
        // Each weighs 1 byte, far under the byte limit; the face limit is what binds.
        val cache = FontProgramCache(maxBytes = 1_000, maxFaces = 3, weigher = FixedWeigher(1))

        repeat(10) { cache.program(face(it)) }

        assertTrue(cache.faces <= 3, "kept ${cache.faces} faces, limit 3")
    }

    @Test
    fun `heavy faces are held to the byte limit`() {
        // Each weighs 40 of 100 bytes; the face limit (10) would allow more.
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, weigher = FixedWeigher(40))

        repeat(10) { cache.program(face(it)) }

        assertTrue(cache.bytes <= 100, "kept ${cache.bytes} bytes, limit 100")
        assertEquals(2, cache.faces)
    }

    @Test
    fun `small faces count at their measured weight`() {
        // 60 + 9 x 1 = 69 bytes in 10 faces: within both limits, so every face stays.
        val weights = ArrayDeque(listOf(60L) + List(9) { 1L })
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10) { bytes -> FontProgramFactory.createFont(bytes, false) to weights.removeFirst() }

        repeat(10) { cache.program(face(it)) }

        assertEquals(10, cache.faces)
        assertEquals(69, cache.bytes)
    }

    @Test
    fun `a supplied content hash is the cache key`() {
        val weigher = FixedWeigher(1)
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 10, weigher = weigher)
        val bytes = face(1)

        cache.program(bytes)
        cache.program(bytes, FontProgramCache.contentHash(bytes))
        assertEquals(1, weigher.parses.get(), "the computed and the supplied hash name the same entry")

        cache.program(bytes, "another-hash")
        assertEquals(2, weigher.parses.get(), "a supplied hash is trusted, not recomputed")
    }

    @Test
    fun `a face heavier than the byte limit is returned but not kept`() {
        val weigher = FixedWeigher(50)
        val cache = FontProgramCache(maxBytes = 25, maxFaces = 10, weigher = weigher)

        cache.program(face(1))
        cache.program(face(1))

        assertEquals(0, cache.faces)
        assertEquals(2, weigher.parses.get())
    }

    @Test
    fun `zero faces turns the cache off`() {
        val weigher = FixedWeigher(1)
        val cache = FontProgramCache(maxBytes = 100, maxFaces = 0, weigher = weigher)

        cache.program(face(1))
        cache.program(face(1))

        assertEquals(2, weigher.parses.get())
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
            val first = executor.submit<Any> { cache.program(face(1)) }
            parsing.await(5, TimeUnit.SECONDS)
            val others = (1..3).map { executor.submit<Any> { cache.program(face(1)) } }
            release.countDown()

            val programs = (listOf(first) + others).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, parses.get())
            programs.forEach { assertSame(programs.first(), it) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `negative limits are refused`() {
        assertFailsWith<IllegalArgumentException> { FontProgramCache(maxBytes = -1) }
        assertFailsWith<IllegalArgumentException> { FontProgramCache(maxFaces = -1) }
    }

    @Test
    fun `the allocation weigher reports what a parse allocates, not the file size`() {
        val (_, weight) = FontWeigher.ALLOCATED_BYTES.parse(base)

        // Liberation Sans is ~400 KB on disk; parsed it allocates several MB.
        assertTrue(weight > base.size, "weight $weight should exceed the file size ${base.size}")
    }
}
