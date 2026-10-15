// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/**
 * A segmented LRU bounded by both a total weight ([maxWeight]) and an entry count ([maxEntries]).
 *
 * A new entry always enters the *probation* segment. An entry looked up from a different *scope*
 * than the one that last used it moves to the *protected* segment, which may take [protectedShare]
 * of each limit; past that, protected's least recently used entry drops back to probation instead of
 * out. Eviction takes probation's least recently used entry first and touches protected entries only
 * when probation holds nothing but the entry being added. A burst of entries used once therefore
 * cannot push out entries several scopes use.
 *
 * A scope is any object identifying one unit of work (for fonts: one render), so that repeated
 * lookups within that unit count as a single use. An entry heavier than [maxWeight] is not kept.
 *
 * Not thread-safe: callers synchronize.
 */
internal class SegmentedLruCache<K : Any, V : Any>(
    val maxWeight: Long,
    val maxEntries: Int,
    protectedShare: Double = 0.8,
) {
    init {
        require(maxWeight >= 0) { "maxWeight must not be negative, was $maxWeight" }
        require(maxEntries >= 0) { "maxEntries must not be negative, was $maxEntries" }
        require(protectedShare in 0.0..1.0) { "protectedShare must be between 0 and 1, was $protectedShare" }
    }

    private class Entry<V>(val value: V, val weight: Long, var lastScope: Any)

    /** Insertion-ordered; re-inserting on use keeps the least recently used entry first. */
    private class Segment<K, V> {
        val entries = LinkedHashMap<K, Entry<V>>()
        var weight = 0L

        fun add(key: K, entry: Entry<V>) {
            entries[key] = entry
            weight += entry.weight
        }

        fun remove(key: K): Entry<V>? = entries.remove(key)?.also { weight -= it.weight }

        fun touch(key: K, entry: Entry<V>) {
            entries.remove(key)
            entries[key] = entry
        }

        fun leastRecent(except: K? = null): K? = entries.keys.firstOrNull { it != except }
    }

    private val protectedMaxWeight = (maxWeight * protectedShare).toLong()
    private val protectedMaxEntries = (maxEntries * protectedShare).toInt()
    private val probation = Segment<K, V>()
    private val protectedSegment = Segment<K, V>()

    var promotions = 0L
        private set
    var weightLimitEvictions = 0L
        private set
    var entryLimitEvictions = 0L
        private set

    val size: Int get() = probation.entries.size + protectedSegment.entries.size
    val weight: Long get() = probation.weight + protectedSegment.weight
    val protectedSize: Int get() = protectedSegment.entries.size
    val protectedWeight: Long get() = protectedSegment.weight

    /** The value for [key], marking it used by [scope]; promotes it when another scope used it last. */
    fun get(key: K, scope: Any): V? {
        protectedSegment.entries[key]?.let { entry ->
            protectedSegment.touch(key, entry)
            entry.lastScope = scope
            return entry.value
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
        return entry.value
    }

    /** Adds [value] to probation as used by [scope], evicting to stay within both limits. */
    fun put(key: K, value: V, weight: Long, scope: Any) {
        if (maxEntries == 0) return
        if (weight > maxWeight) {
            weightLimitEvictions++
            return
        }
        probation.remove(key)
        protectedSegment.remove(key)
        probation.add(key, Entry(value, weight, scope))
        evictOverLimits(keep = key)
    }

    private fun demoteOverProtectedShare() {
        while (protectedSegment.entries.size > protectedMaxEntries || protectedSegment.weight > protectedMaxWeight) {
            val key = protectedSegment.leastRecent() ?: return
            probation.add(key, protectedSegment.remove(key)!!)
        }
    }

    private fun evictOverLimits(keep: K) {
        while (true) {
            val overEntries = size > maxEntries
            if (!overEntries && weight <= maxWeight) return
            val fromProbation = probation.leastRecent(except = keep)
            val victim = fromProbation ?: protectedSegment.leastRecent() ?: return
            (if (fromProbation != null) probation else protectedSegment).remove(victim)
            if (overEntries) entryLimitEvictions++ else weightLimitEvictions++
        }
    }
}
