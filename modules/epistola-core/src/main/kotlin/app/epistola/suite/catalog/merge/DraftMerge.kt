// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

/** How one side changed a resource since the merge base; null when it did not. */
enum class MergeChange { ADDED, MODIFIED, REMOVED }

/** What merging a release into a draft does with one resource (ADR 0032, section 5). */
enum class MergeOutcome {
    /** Neither side changed it. */
    UNCHANGED,

    /** Only the draft changed it: the draft's stays. */
    OURS,

    /** Only the release changed it (added, modified or removed): the release's is taken. */
    THEIRS,

    /** Both made the same change: nothing to do. */
    SAME,

    /** Both changed it differently: the person chooses a side. */
    CONFLICT,
}

/** Which side a conflict is resolved to. */
enum class MergeSide { OURS, THEIRS }

/**
 * One resource of a merge. [ours] is how the draft changed it since the base, [theirs] how the
 * release did; [inTheirs] whether the release has it (so taking theirs writes it, else removes it).
 */
data class MergeResource(
    val type: String,
    val key: String,
    val name: String?,
    val outcome: MergeOutcome,
    val ours: MergeChange?,
    val theirs: MergeChange?,
    val inTheirs: Boolean,
) {
    /** `type/key`, as fingerprints and resolutions name it. */
    val contentKey: String get() = "$type/$key"
}

/**
 * The three-way merge of a catalog per resource, from a fingerprint per resource on each side: the
 * merge base (the closest release both descend from, [mergeBase]), ours (the draft) and theirs
 * (the release merged in). Keys are `type/key`; a side without a key does not have that resource.
 */
object DraftMerge {

    /**
     * The releases a draft already contains: its base and every release merged into it, with every
     * release each of those descends from. [baseOf] gives a release's base.
     */
    fun contained(draftBase: String?, merged: Collection<String>, baseOf: (String) -> String?): Set<String> {
        val seen = linkedSetOf<String>()
        val todo = ArrayDeque(listOfNotNull(draftBase) + merged)
        while (todo.isNotEmpty()) {
            val release = todo.removeFirst()
            if (seen.add(release)) baseOf(release)?.let(todo::addLast)
        }
        return seen
    }

    /**
     * The merge base of a draft and [release]: the closest release on [release]'s line (itself, its
     * base, that one's base, …) that the draft already [contained]. Not the draft's own base: work
     * started from 1.0.0 and released as 1.0.1 does not remove what the draft's base 1.1.0 added
     * since 1.0.0. Null when they share no release.
     */
    fun mergeBase(release: String, contained: Set<String>, baseOf: (String) -> String?): String? {
        val seen = mutableSetOf<String>()
        var current: String? = release
        while (current != null && seen.add(current)) {
            if (current in contained) return current
            current = baseOf(current)
        }
        return null
    }

    fun classify(
        base: Map<String, String>,
        ours: Map<String, String>,
        theirs: Map<String, String>,
        names: Map<String, String> = emptyMap(),
    ): List<MergeResource> = (base.keys + ours.keys + theirs.keys).sorted().map { key ->
        val b = base[key]
        val o = ours[key]
        val t = theirs[key]
        val outcome = when {
            o == t -> if (o == b) MergeOutcome.UNCHANGED else MergeOutcome.SAME
            t == b -> MergeOutcome.OURS
            o == b -> MergeOutcome.THEIRS
            else -> MergeOutcome.CONFLICT
        }
        MergeResource(
            type = key.substringBefore('/'),
            key = key.substringAfter('/'),
            name = names[key],
            outcome = outcome,
            ours = change(b, o),
            theirs = change(b, t),
            inTheirs = t != null,
        )
    }

    private fun change(base: String?, side: String?): MergeChange? = when {
        base == side -> null
        base == null -> MergeChange.ADDED
        side == null -> MergeChange.REMOVED
        else -> MergeChange.MODIFIED
    }
}
