// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SegmentedLruCacheTest {

    /** A distinct object per unit of work, as a render passes its own scope. */
    private fun scope() = Any()

    /** Looks [key] up as [scope]; on a miss, stores it at [weight] the way the font cache does after parsing. */
    private fun SegmentedLruCache<String, String>.use(key: String, scope: Any, weight: Long = 1): Boolean {
        if (get(key, scope) != null) return true
        put(key, "value of $key", weight, scope)
        return false
    }

    @Test
    fun `a new entry is kept, even when busier entries fill the cache`() {
        // Caffeine's W-TinyLFU refused such a newcomer for lack of history.
        val cache = SegmentedLruCache<String, String>(maxWeight = 100, maxEntries = 10)
        repeat(5) { scope().let { s -> listOf("a", "b", "c").forEach { cache.use(it, s, weight = 30) } } }

        val render = scope()
        cache.use("new", render, weight = 30)

        assertEquals("value of new", cache.get("new", render))
    }

    @Test
    fun `a burst of entries used once does not evict entries several scopes use`() {
        val cache = SegmentedLruCache<String, String>(maxWeight = 1_000, maxEntries = 5)
        repeat(2) { scope().let { s -> listOf("popular-1", "popular-2").forEach { cache.use(it, s) } } }
        assertEquals(2, cache.protectedSize)

        (1..20).forEach { cache.use("one-off-$it", scope()) }

        val render = scope()
        assertEquals("value of popular-1", cache.get("popular-1", render))
        assertEquals("value of popular-2", cache.get("popular-2", render))
        assertEquals(5, cache.size)
    }

    @Test
    fun `lookups within one scope do not promote`() {
        val cache = SegmentedLruCache<String, String>(maxWeight = 1_000, maxEntries = 5)
        val render = scope()

        repeat(4) { cache.use("a", render) }
        assertEquals(0, cache.protectedSize)

        cache.use("a", scope())
        assertEquals(1, cache.protectedSize)
        assertEquals(1, cache.promotions)
    }

    @Test
    fun `the protected segment keeps to its share by demoting, not evicting`() {
        // 80% of 5 entries: at most 4 protected.
        val cache = SegmentedLruCache<String, String>(maxWeight = 1_000, maxEntries = 5)
        repeat(2) { scope().let { s -> (1..5).forEach { cache.use("e$it", s) } } }

        assertEquals(4, cache.protectedSize)
        assertEquals(5, cache.size, "the demoted entry is still cached")
    }

    @Test
    fun `a protected entry nobody uses is demoted, then evicted`() {
        val cache = SegmentedLruCache<String, String>(maxWeight = 1_000, maxEntries = 3)
        // 80% of 3 entries: at most 2 protected.
        repeat(2) { scope().let { s -> cache.use("stale", s) } }
        repeat(2) { scope().let { s -> listOf("x", "y").forEach { cache.use(it, s) } } }
        repeat(5) { cache.use("one-off-$it", scope()) }

        assertNull(cache.get("stale", scope()), "demoted when x and y were promoted, then evicted from probation")
        assertEquals(3, cache.size)
    }

    @Test
    fun `entries are held to the entry limit`() {
        val cache = SegmentedLruCache<String, String>(maxWeight = 1_000, maxEntries = 3)

        repeat(10) { cache.use("e$it", scope()) }

        assertEquals(3, cache.size)
        assertEquals(7, cache.entryLimitEvictions)
    }

    @Test
    fun `entries are held to the weight limit, at their own weight`() {
        val cache = SegmentedLruCache<String, String>(maxWeight = 100, maxEntries = 10)

        repeat(10) { cache.use("e$it", scope(), weight = 40) }
        assertEquals(2, cache.size)
        assertEquals(80, cache.weight)
        assertEquals(8, cache.weightLimitEvictions)

        // Light entries take only their weight: 60 + 9 x 1 fits in 100 with 10 entries.
        val mixed = SegmentedLruCache<String, String>(maxWeight = 100, maxEntries = 10)
        mixed.use("heavy", scope(), weight = 60)
        repeat(9) { mixed.use("light-$it", scope(), weight = 1) }
        assertEquals(10, mixed.size)
        assertEquals(69, mixed.weight)
    }

    @Test
    fun `an entry heavier than the weight limit is not kept`() {
        val cache = SegmentedLruCache<String, String>(maxWeight = 25, maxEntries = 10)

        cache.use("big", scope(), weight = 50)

        assertEquals(0, cache.size)
        assertEquals(1, cache.weightLimitEvictions)
    }

    @Test
    fun `a limit of zero entries keeps nothing`() {
        val cache = SegmentedLruCache<String, String>(maxWeight = 100, maxEntries = 0)

        cache.use("a", scope())

        assertEquals(0, cache.size)
    }

    @Test
    fun `negative limits are refused`() {
        assertFailsWith<IllegalArgumentException> { SegmentedLruCache<String, String>(maxWeight = -1, maxEntries = 1) }
        assertFailsWith<IllegalArgumentException> { SegmentedLruCache<String, String>(maxWeight = 1, maxEntries = -1) }
    }
}
