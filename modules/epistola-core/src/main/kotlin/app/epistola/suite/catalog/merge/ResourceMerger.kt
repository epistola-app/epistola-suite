// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.protocol.CatalogResource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** One entry of one side of a merge, with its fingerprint. */
sealed interface MergeValue {
    val fingerprint: String

    /** A resource as that side holds it, with the bytes it needs by archive path (an image's, a font's faces). */
    data class Resource(
        val resource: CatalogResource,
        override val fingerprint: String,
        val binaries: Map<String, ByteArray> = emptyMap(),
    ) : MergeValue

    /** A catalog setting as that side holds it. */
    data class Setting(val setting: CatalogSetting, override val fingerprint: String) : MergeValue
}

/** One entry of a merge on its three sides; a null side does not have it. */
data class MergeEntrySides(
    /** `type/key`. */
    val contentKey: String,
    /** What it is called: the draft's name, else the release's, else the base's. */
    val name: String?,
    val base: MergeValue?,
    val ours: MergeValue?,
    val theirs: MergeValue?,
    /** The base's fingerprint as the draft holds it ([MergeInputs.heldBase]); null where the draft never held it. */
    val heldBase: String? = base?.fingerprint,
) {
    val type: String get() = contentKey.substringBefore('/')
}

/** What merging does to one entry of the draft. */
sealed interface MergeAction {
    /** The draft's stays as it is. */
    data object Keep : MergeAction

    /** Written into the draft as [value] holds it. */
    data class Write(val value: MergeValue) : MergeAction

    /** Removed from the draft. */
    data object Remove : MergeAction
}

/**
 * How one type of entry merges: what the merge does with it ([classify]), and what to write for it
 * once it is resolved ([plan]). The default, [WholeResourceMerger], treats every entry as a whole,
 * which is all the suite does today; a merger for one type (a template merged per variant, later per
 * block) is a Spring bean implementing this for its [types], which [ResourceMergers] then prefers.
 */
interface ResourceMerger {
    /** The entry types (`template`, `theme`, [CatalogSetting.TYPE], …) this merger is for. */
    val types: Set<String>

    /** How the merge treats [sides]. */
    fun classify(sides: MergeEntrySides): MergeResource

    /**
     * What to do with [sides], classified as [classified], under [resolution]: the person's choice
     * for a conflict, null for anything else.
     */
    fun plan(sides: MergeEntrySides, classified: MergeResource, resolution: MergeResolution?): MergeAction
}

/**
 * Every entry as a whole (ADR 0032, section 5): from its three fingerprints by [DraftMerge.classifyOne],
 * and resolved by taking one side.
 */
object WholeResourceMerger : ResourceMerger {
    override val types: Set<String> = emptySet()

    override fun classify(sides: MergeEntrySides): MergeResource = DraftMerge.classifyOne(
        sides.contentKey,
        sides.base?.fingerprint,
        sides.ours?.fingerprint,
        sides.theirs?.fingerprint,
        sides.name,
        heldBase = sides.heldBase,
    )

    override fun plan(sides: MergeEntrySides, classified: MergeResource, resolution: MergeResolution?): MergeAction {
        val takesTheirs = when (classified.outcome) {
            MergeOutcome.THEIRS -> true
            MergeOutcome.CONFLICT -> resolution == MergeResolution.Theirs
            else -> false
        }
        if (!takesTheirs) return MergeAction.Keep
        return sides.theirs?.let(MergeAction::Write) ?: MergeAction.Remove
    }
}

/** The merger for each entry type: a bean that claims the type, else [WholeResourceMerger]. */
@Component
class ResourceMergers(mergers: ObjectProvider<ResourceMerger>) {
    private val byType: Map<String, ResourceMerger> = mergers.orderedStream().toList().flatMap { merger -> merger.types.map { it to merger } }.toMap()

    fun of(type: String): ResourceMerger = byType[type] ?: WholeResourceMerger
}
