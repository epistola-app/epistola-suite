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

/**
 * How a conflict is resolved: a whole side, [Ours] or [Theirs], or [Content] combined from the sides
 * by a person, an assistant or a tool (contract ADR 0005). A later case can carry a choice per part
 * (a template's variants, a block) without breaking callers. [wire] is how forms, MCP and REST write
 * a side, and how a merge records each resolution.
 */
sealed interface MergeResolution {
    val wire: String

    /** Keep the draft's. */
    data object Ours : MergeResolution {
        override val wire: String = "OURS"
    }

    /** Take the release's: written as it has it, or removed when it removed it. */
    data object Theirs : MergeResolution {
        override val wire: String = "THEIRS"
    }

    /**
     * Merged content: the entry as the draft is to hold it, read and validated as an import reads it
     * ([MergedContentReader]). Written as given and not marked ready, so it is reviewed before the
     * draft is released.
     */
    data class Content(val value: MergeValue) : MergeResolution {
        override val wire: String get() = WIRE

        companion object {
            /** How a merge records a conflict resolved to merged content. */
            const val WIRE: String = "MERGED"
        }
    }

    companion object {
        /** Every resolution a whole-entry choice can be. */
        val SIDES: List<MergeResolution> = listOf(Ours, Theirs)

        /** The side [wire] names (`OURS`, `THEIRS`, any case), or null. */
        fun ofWire(wire: String?): MergeResolution? = SIDES.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) }
    }
}

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
    /**
     * The other entries this one goes with (`type/key`): what its values refer to (a template's theme
     * or stencil, the dependency a reference into another catalog resolves through, the default
     * theme's theme or catalog) that the merge also changes, so a choice is made with both in view.
     */
    val needs: List<String> = emptyList(),
) {
    /** `type/key`, as fingerprints and resolutions name it. */
    val contentKey: String get() = "$type/$key"

    /** Whether this is one of the catalog's settings ([CatalogSetting]) rather than a resource. */
    val isSetting: Boolean get() = CatalogSetting.isSettingType(type)
}

/**
 * The three-way merge of a catalog per resource, from a fingerprint per resource on each side: the
 * merge base (the closest release both descend from, [app.epistola.suite.catalog.releases.ReleaseLineage.closestIn]), ours (the draft) and theirs
 * (the release merged in). Keys are `type/key`; a side without a key does not have that resource.
 */
object DraftMerge {

    fun classify(
        base: Map<String, String>,
        ours: Map<String, String>,
        theirs: Map<String, String>,
        names: Map<String, String> = emptyMap(),
    ): List<MergeResource> = (base.keys + ours.keys + theirs.keys).sorted().map { key ->
        classifyOne(key, base[key], ours[key], theirs[key], names[key])
    }

    /**
     * One entry ([contentKey], `type/key`) from its fingerprint on each side, null where that side
     * does not have it: the whole-entry table of ADR 0032, section 5.
     *
     * [heldBase] is the base as the draft holds it, which the draft's fingerprint [o] is compared
     * with; [b], the base as released, is what the release's [t] is compared with. They differ for a
     * release received from elsewhere: putting it in place writes it as this suite holds content
     * (a default written out, a slot a stencil needs added), which need not fingerprint as its
     * publisher's content does. When both sides made a change, [o] and [t] are compared directly.
     */
    fun classifyOne(contentKey: String, b: String?, o: String?, t: String?, name: String? = null, heldBase: String? = b): MergeResource {
        val oursChanged = o != heldBase
        val theirsChanged = t != b
        val outcome = when {
            !oursChanged && !theirsChanged -> MergeOutcome.UNCHANGED
            !theirsChanged -> MergeOutcome.OURS
            !oursChanged -> MergeOutcome.THEIRS
            o == t -> MergeOutcome.SAME
            else -> MergeOutcome.CONFLICT
        }
        return MergeResource(
            type = contentKey.substringBefore('/'),
            key = contentKey.substringAfter('/'),
            name = name,
            outcome = outcome,
            ours = change(heldBase, o),
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
