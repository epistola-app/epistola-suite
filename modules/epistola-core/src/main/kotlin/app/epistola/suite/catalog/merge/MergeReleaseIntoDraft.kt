// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.releases.CatalogDrafts
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Merges [release] into a draft (ADR 0032, alpha): the draft the command targets, or the catalog's
 * next version. Per resource and per catalog setting (its default theme, its presets and each catalog
 * it declares it uses, [CatalogSetting]), as [PreviewDraftMerge] classifies it:
 *
 * - what only the release changed is taken from it: written as the release has it, or removed;
 * - what only the draft changed stays;
 * - a conflict is resolved by [resolutions] (`type/key` to a side, [MergeResolution.Ours] or
 *   [MergeResolution.Theirs]) or by [mergedContent] (`type/key` to the entry as the draft is to hold
 *   it, combined from its sides, [GetDraftMergeSides]), and every conflict must have one of them.
 *
 * Taken resources are marked ready, since they are released content. Merged content is read and
 * validated as an import reads content ([MergedContentReader]), written as given, and not marked
 * ready: it is reviewed before the draft is released. Afterwards the draft has merged [release] (a
 * row of `catalog_draft_merges`), so it is not behind it.
 *
 * Refused when a conflict is resolved both by a side and by merged content, or merged content is not
 * valid for its entry ([app.epistola.suite.validation.ValidationException], its field
 * `mergedContent.<type/key>…`), when the draft already contains [release] (its base, or merged
 * before), or a resolution or merged content names an entry that is not a conflict
 * ([MergeNotApplicableException]), when [expectedToken] names another state than the one the merge
 * starts from ([MergePreviewOutdatedException]), when a conflict has no resolution
 * ([MergeConflictsUnresolvedException]), when a resource the release removed is still used, or the
 * result holds a reference that does not resolve: within the catalog, into a catalog it does not
 * declare, or into a release it pins that lacks it ([MergeReferenceCheck], [MergeRefusedException]),
 * and when this installation does not have the merge base the lineage names
 * ([MergeBaseMissingException]).
 */
data class MergeReleaseIntoDraft(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val release: String,
    val resolutions: Map<String, MergeResolution> = emptyMap(),
    /** The draft to merge into; null for the catalog's next version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The [DraftMergePreview.token] the merge was confirmed against; null merges whatever the state is now. */
    val expectedToken: String? = null,
    /**
     * Conflicts resolved to merged content, `type/key` to the entry in the portable catalog format
     * ([MergeEntryContent]), beside [resolutions]: a conflict is resolved in one of them, not both.
     */
    val mergedContent: Map<String, Map<String, Any?>> = emptyMap(),
) : CatalogReleaseScoped,
    Command<MergedRelease>,
    RequiresPermission {
    // Merging changes the draft's content, which is editing it.
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        val twice = mergedContent.keys.filter { it in resolutions }.sorted()
        validate("mergedContent.${twice.firstOrNull()}", twice.isEmpty()) {
            "${twice.joinToString()} ${if (twice.size == 1) "is" else "are"} resolved both to a side and to merged content; choose one"
        }
        val sides = resolutions.filterValues { it is MergeResolution.Content }.keys.sorted()
        validate("resolutions.${sides.firstOrNull()}", sides.isEmpty()) { "Merged content goes in mergedContent, not in resolutions: ${sides.joinToString()}" }
    }
}

/** What a merge did, each as `type/key`. */
data class MergedRelease(
    val release: String,
    /** Written as the release has it. */
    val taken: List<String>,
    /** Removed, because the release removed it. */
    val removed: List<String>,
    /** Conflicts kept as the draft has them. */
    val kept: List<String>,
    /** Conflicts resolved to merged content, written as given and not marked ready. */
    val merged: List<String> = emptyList(),
)

/** A merge with conflicts that [conflicts] (`type/key`) has no resolution for. */
class MergeConflictsUnresolvedException(val catalogKey: CatalogKey, val release: String, val conflicts: List<String>) : RuntimeException("Merging $release into '${catalogKey.value}' needs a choice for: ${conflicts.joinToString()}")

/** A merge that does not apply: the draft already contains the release, or a resolution or merged content names no conflict. */
class MergeNotApplicableException(val catalogKey: CatalogKey, override val message: String) : RuntimeException(message)

/** The draft or the release changed since the preview a merge was confirmed against. */
class MergePreviewOutdatedException(val catalogKey: CatalogKey, val release: String) : RuntimeException("The draft or $release changed since the preview; preview again")

/**
 * A merge whose merge base this installation does not have: [version] (with [fingerprint]), the
 * release the draft and [release] both descend from (contract ADR 0005). Getting it first, from a
 * registry, the Exchange or a file, makes the merge possible.
 */
class MergeBaseMissingException(
    val catalogKey: CatalogKey,
    val release: String,
    val version: String,
    val fingerprint: String,
) : RuntimeException(
    "To merge v$release, this installation needs v$version, the release both come from. Get it first (from a registry, the Exchange or a file).",
)

/** A merge that cannot be applied as asked; [message] says why. */
class MergeRefusedException(val catalogKey: CatalogKey, override val message: String) : RuntimeException(message)

@Component
class MergeReleaseIntoDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val targeting: CatalogReleaseTargeting,
    private val planner: DraftMergePlanner,
    private val mergers: ResourceMergers,
    private val writer: DraftResourceWriter,
    private val inputs: MergeInputReader,
    private val referenceCheck: MergeReferenceCheck,
    private val mergedContent: MergedContentReader,
    private val objectMapper: ObjectMapper,
    private val fingerprints: app.epistola.suite.catalog.CatalogFingerprintService,
) : CommandHandler<MergeReleaseIntoDraft, MergedRelease> {

    override fun handle(command: MergeReleaseIntoDraft): MergedRelease {
        val (tenantKey, catalogKey) = command.tenantKey to command.catalogKey
        requireCatalogEditable(tenantKey, catalogKey)
        val plan = planner.plan(tenantKey, catalogKey, command.release, command.catalogVersion?.draft?.toString() ?: "next")
        val draft = plan.inputs.draft
        if (command.release in plan.inputs.contained) {
            throw MergeNotApplicableException(catalogKey, "${draft.label} of '${catalogKey.value}' already contains ${command.release}")
        }
        if (command.expectedToken != null && command.expectedToken != plan.token) throw MergePreviewOutdatedException(catalogKey, command.release)
        plan.inputs.missingBase?.let { throw MergeBaseMissingException(catalogKey, command.release, it.version, it.fingerprint) }

        val conflicts = plan.resources.filter { it.outcome == MergeOutcome.CONFLICT }.map { it.contentKey }
        val stray = (command.resolutions.keys + command.mergedContent.keys) - conflicts.toSet()
        if (stray.isNotEmpty()) throw MergeNotApplicableException(catalogKey, "Not a conflict of this merge: ${stray.sorted().joinToString()}")
        val unresolved = conflicts.filter { it !in command.resolutions && it !in command.mergedContent }
        if (unresolved.isNotEmpty()) throw MergeConflictsUnresolvedException(catalogKey, command.release, unresolved)

        // Merged content read as an import reads content, before anything is written.
        val resolutions = command.resolutions + plan.entries.filter { it.contentKey in command.mergedContent }.associate { sides ->
            sides.contentKey to MergeResolution.Content(targeting.within(draft.versionId) { mergedContent.read(tenantKey, catalogKey, plan, sides, command.mergedContent.getValue(sides.contentKey)) })
        }
        val actions = plan.entries.zip(plan.resources).associate { (sides, classified) ->
            sides.contentKey to mergers.of(sides.type).plan(sides, classified, resolutions[sides.contentKey])
        }.filterValues { it != MergeAction.Keep }
        // What the merge would make of the draft: refused before anything is written when it would not resolve.
        targeting.within(draft.versionId) { referenceCheck.requireResolved(tenantKey, catalogKey, plan, actions) }
        val (writes, after) = targeting.within(draft.versionId) {
            val writes = writer.apply(tenantKey, catalogKey, command.release, draft.label, actions)
            val after = inputs.draftInput(tenantKey, catalogKey)
            // In the merge's transaction: what it wrote is checked too, as the database holds it.
            referenceCheck.requireResolved(tenantKey, catalogKey, plan, actions, after)
            writes to after
        }
        val recorded = objectMapper.writeValueAsString(resolutions.toSortedMap().mapValues { it.value.wire })
        val held = fingerprints.storeBaseline(held(plan, actions, after.fingerprints))
        jdbi.useHandle<Exception> { drafts.recordMerged(it, tenantKey, draft.versionId, command.release, plan.inputs.mergeBase, recorded, held) }

        val merged = command.mergedContent.keys.sorted()
        return MergedRelease(
            release = command.release,
            taken = (writes.written - merged.toSet()).sorted(),
            removed = writes.removed.sorted(),
            kept = conflicts.filter { command.resolutions[it] == MergeResolution.Ours },
            merged = merged,
        )
    }

    /**
     * What the draft holds of the release once merged, by entry, for a later merge that has it as its
     * base: as the draft now holds it where it holds the release's (taken, or the same on both
     * sides), and as the release has it where the draft kept its own or holds merged content, which is
     * the draft's change as much as the release's.
     */
    private fun held(plan: MergePlan, actions: Map<String, MergeAction>, after: Map<String, String>): Map<String, String> = plan.resources
        .mapNotNull { resource ->
            val key = resource.contentKey
            val taken = actions[key]?.let { it !is MergeAction.Write || it.ready } == true
            val holdsTheirs = taken || resource.outcome == MergeOutcome.UNCHANGED || resource.outcome == MergeOutcome.SAME
            val fingerprint = if (holdsTheirs) after[key] else plan.inputs.theirs.fingerprints[key]
            fingerprint?.let { key to it }
        }
        .toMap()
        .toSortedMap()
}
