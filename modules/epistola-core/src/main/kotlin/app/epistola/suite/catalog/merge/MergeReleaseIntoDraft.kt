// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.InstalledResource
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.graph.TenantResourceGraphBuilder
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.catalog.versions.CatalogVersionTargeting
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Merges [release] into a draft (ADR 0032, alpha): the draft the command targets, or the catalog's
 * next version. Per resource, as [PreviewDraftMerge] classifies it:
 *
 * - what only the release changed is taken from it: written as the release has it, or removed;
 * - what only the draft changed stays;
 * - a conflict is resolved by [resolutions] (`type/key` to a side), and every conflict must have one.
 *
 * Taken resources are marked ready, since they are released content. The draft's settings (its
 * default theme, presets and dependency declarations) stay its own. Afterwards the draft has merged
 * [release], so it is not behind it.
 *
 * Refused when the draft already contains [release] (its base, or merged before), when a conflict
 * has no resolution ([MergeConflictsUnresolvedException]), and when a resource the release removed
 * is still used ([MergeRefusedException]).
 */
data class MergeReleaseIntoDraft(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val release: String,
    val resolutions: Map<String, MergeSide> = emptyMap(),
    /** The draft to merge into; null for the catalog's next version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<MergedRelease>,
    RequiresPermission {
    // Merging changes the draft's content, which is editing it.
    override val permission get() = Permission.TEMPLATE_EDIT
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
)

/** A merge with conflicts that [conflicts] (`type/key`) has no resolution for. */
class MergeConflictsUnresolvedException(val catalogKey: CatalogKey, val release: String, val conflicts: List<String>) : RuntimeException("Merging $release into '${catalogKey.value}' needs a choice for: ${conflicts.joinToString()}")

/** A merge that cannot be applied as asked; [message] says why. */
class MergeRefusedException(val catalogKey: CatalogKey, override val message: String) : RuntimeException(message)

@Component
class MergeReleaseIntoDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val targeting: CatalogVersionTargeting,
    private val analyzer: CatalogUpgradeAnalyzer,
    private val graph: TenantResourceGraphBuilder,
) : CommandHandler<MergeReleaseIntoDraft, MergedRelease> {

    override fun handle(command: MergeReleaseIntoDraft): MergedRelease {
        val (tenantKey, catalogKey) = command.tenantKey to command.catalogKey
        requireCatalogEditable(tenantKey, catalogKey)
        val draft = jdbi.withHandle<_, Exception> { drafts.draft(it, tenantKey, catalogKey) }
            ?: throw CatalogVersionNotFoundException(catalogKey, command.catalogVersion?.draft?.toString() ?: "next")
        val preview = PreviewDraftMerge(tenantKey, catalogKey, command.release, command.catalogVersion).query()
        if (preview.alreadyMerged) {
            throw ValidationException("release", "${draft.label} of '${catalogKey.value}' already contains ${command.release}")
        }

        val conflicts = preview.conflicts.map { it.contentKey }
        val stray = command.resolutions.keys - conflicts.toSet()
        if (stray.isNotEmpty()) throw ValidationException("resolutions", "Not a conflict of this merge: ${stray.sorted().joinToString()}")
        val unresolved = conflicts.filter { it !in command.resolutions }
        if (unresolved.isNotEmpty()) throw MergeConflictsUnresolvedException(catalogKey, command.release, unresolved)

        val takes = preview.resources.filter { resource ->
            resource.outcome == MergeOutcome.THEIRS ||
                (resource.outcome == MergeOutcome.CONFLICT && command.resolutions[resource.contentKey] == MergeSide.THEIRS)
        }
        val (bring, remove) = takes.partition { it.inTheirs }

        targeting.within(draft.versionId) {
            if (bring.isNotEmpty()) {
                // The release as it was cut, rebuilt from what it kept, of which only these resources land.
                val archive = ExportCatalogZip(tenantKey, catalogKey, command.release).execute()
                val imported = ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = archive.zipBytes,
                    catalogType = CatalogType.AUTHORED,
                    validateCrossCatalogDeps = false,
                    confirmMigration = true,
                    intoDraft = true,
                    mergeResources = bring.map { it.contentKey }.toSet(),
                ).execute()
                val failed = imported.results.filter { it.status == InstallStatus.FAILED }
                if (failed.isNotEmpty()) {
                    throw MergeRefusedException(catalogKey, "Could not take " + failed.joinToString { "${it.type} '${it.slug}': ${it.errorMessage}" })
                }
            }
            if (remove.isNotEmpty()) {
                val stale = remove.map { InstalledResource(it.type, it.key) }
                val inUse = analyzer.findConflicts(tenantKey, catalogKey, stale)
                if (inUse.isNotEmpty()) throw MergeRefusedException(catalogKey, "${command.release} removes what is still used: ${inUse.joinToString("; ")}")
                // Within the draft too, which that check leaves out: a reference points at an identity,
                // so removing the row would leave it pointing at nothing instead of failing. Read after
                // taking theirs, since what was taken refers to what the release has.
                val removed = remove.map { it.type to it.key }.toSet()
                val users = jdbi.withHandle<_, Exception> { graph.buildOn(it, tenantKey, includeHistory = false) }.edges.filter { edge ->
                    val target = edge.target ?: return@filter false
                    target.catalogKey == catalogKey.value &&
                        (target.type.wireName to target.key) in removed &&
                        edge.source.catalogKey == catalogKey.value &&
                        (edge.source.type.wireName to edge.source.key) !in removed
                }
                if (users.isNotEmpty()) {
                    throw stillUsed(command, draft.label, users.map { "${it.target!!.type.wireName} '${it.target.key}' (used by ${it.source.type.wireName} '${it.source.key}')" }.distinct())
                }
                try {
                    analyzer.removeStale(tenantKey, catalogKey, stale)
                } catch (e: org.jdbi.v3.core.JdbiException) {
                    // A reference the database itself refuses to leave behind (an attribute's code list).
                    throw stillUsed(command, draft.label, remove.map { "${it.type} '${it.key}'" })
                }
            }
        }
        jdbi.useHandle<Exception> { drafts.recordMerged(it, tenantKey, draft.versionId, command.release) }

        return MergedRelease(
            release = command.release,
            taken = bring.map { it.contentKey },
            removed = remove.map { it.contentKey },
            kept = preview.conflicts.filter { command.resolutions[it.contentKey] == MergeSide.OURS }.map { it.contentKey },
        )
    }

    private fun stillUsed(command: MergeReleaseIntoDraft, draftLabel: String, what: List<String>) = MergeRefusedException(
        command.catalogKey,
        "${command.release} removes ${what.joinToString()}, which $draftLabel still uses. Keep it, or stop using it first.",
    )
}
