// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.sha256Hex
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
) {

    fun plan(tenantKey: TenantKey, catalogKey: CatalogKey, release: String, draftName: String): MergePlan {
        val read = inputs.read(tenantKey, catalogKey, release, draftName)
        val (base, ours, theirs) = Triple(read.base, read.ours, read.theirs)
        // A resource is called what the draft calls it, else what the release does, else the base.
        val names = base.names + theirs.names + ours.names
        val entries = (base.values.keys + ours.values.keys + theirs.values.keys).sorted().map { key ->
            val setting = (ours.values[key] ?: theirs.values[key] ?: base.values[key]) as? MergeValue.Setting
            MergeEntrySides(key, setting?.setting?.label ?: names[key], base.values[key], ours.values[key], theirs.values[key], heldBase = read.heldBase[key])
        }
        return MergePlan(
            inputs = read,
            entries = entries,
            resources = entries.map { mergers.of(it.type).classify(it) },
            token = token(read),
        )
    }

    companion object {
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
                side("base", inputs.base),
                inputs.heldBase.toSortedMap().entries.joinToString("\n") { "held ${it.key} ${it.value}" },
                side("ours", inputs.ours),
                side("theirs", inputs.theirs),
            ).joinToString("\n")
            return sha256Hex(text.toByteArray(Charsets.UTF_8))
        }
    }
}
