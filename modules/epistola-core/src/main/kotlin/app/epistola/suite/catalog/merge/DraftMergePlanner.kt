// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.sha256Hex
import org.springframework.stereotype.Component

/**
 * A merge worked out: its [inputs], every entry on its three sides ([entries]) and how the merge
 * treats it ([resources], in the same order), and the [token] that names exactly this state.
 */
data class MergePlan(
    val inputs: MergeInputs,
    val entries: List<MergeEntrySides>,
    val resources: List<MergeResource>,
    val token: String,
)

/**
 * Works out a merge: reads the [MergeInputs], and classifies every entry by the merger for its type
 * ([ResourceMergers]). The preview shows a plan, and the merge applies one, so both agree by
 * construction.
 */
@Component
class DraftMergePlanner(
    private val inputs: MergeInputReader,
    private val mergers: ResourceMergers,
    private val references: MergeReferences,
) {

    fun plan(catalog: CatalogId, release: String, draftName: String): MergePlan {
        val read = inputs.read(catalog, release, draftName)
        val (base, ours, theirs) = Triple(read.base, read.ours, read.theirs)
        // A resource is called what the draft calls it, else what the release does, else the base.
        val names = base.names + theirs.names + ours.names
        // Without the merge base it names, nothing can be classified (contract ADR 0005).
        val keys = if (read.missingBase != null) emptyList() else (base.values.keys + ours.values.keys + theirs.values.keys).sorted()
        val entries = keys.map { key ->
            val setting = (ours.values[key] ?: theirs.values[key] ?: base.values[key]) as? MergeValue.Setting
            MergeEntrySides(key, setting?.setting?.label ?: names[key], base.values[key], ours.values[key], theirs.values[key], heldBase = read.heldBase[key])
        }
        val classified = entries.map { mergers.of(it.type).classify(it) }
        return MergePlan(
            inputs = read,
            entries = entries,
            resources = withNeeds(catalog.key, entries, classified),
            token = token(read),
        )
    }

    /**
     * [classified] with what each entry goes with: the entries its draft's or its release's value
     * refers to ([MergeReferences]) among those the merge changes or asks about.
     */
    private fun withNeeds(catalogKey: CatalogKey, entries: List<MergeEntrySides>, classified: List<MergeResource>): List<MergeResource> {
        val changing = classified.filter { it.outcome in CHANGING }.map { it.contentKey }.toSet()
        return entries.zip(classified).map { (sides, resource) ->
            if (resource.outcome !in CHANGING) return@map resource
            val needs = listOfNotNull(sides.ours, sides.theirs).flatMap(references::of).map { it.entryIn(catalogKey) }
                .filter { it in changing && it != resource.contentKey }
                .distinct()
                .sorted()
            resource.copy(needs = needs)
        }
    }

    companion object {
        /** The outcomes the merge acts on or asks about; the others change nothing. */
        private val CHANGING = setOf(MergeOutcome.OURS, MergeOutcome.THEIRS, MergeOutcome.CONFLICT)

        /**
         * A digest over what a merge is worked out from: the release, the merge base and every
         * entry's fingerprint on each side. Any change to the draft, or a different merge base, gives
         * another token, so a merge confirmed against a preview applies only what that preview showed.
         */
        fun token(inputs: MergeInputs): String {
            fun side(name: String, input: MergeInput) = input.fingerprints.toSortedMap().entries.joinToString("\n") { "$name ${it.key} ${it.value}" }
            val text = listOf(
                "release ${inputs.release}",
                "base ${inputs.mergeBase.orEmpty()}",
                "missing ${inputs.missingBase?.let { "${it.version} ${it.fingerprint}" }.orEmpty()}",
                side("base", inputs.base),
                inputs.heldBase.toSortedMap().entries.joinToString("\n") { "held ${it.key} ${it.value}" },
                side("ours", inputs.ours),
                side("theirs", inputs.theirs),
            ).joinToString("\n")
            return sha256Hex(text.toByteArray(Charsets.UTF_8))
        }
    }
}
