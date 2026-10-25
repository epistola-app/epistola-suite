// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Starts a draft of an authored catalog (contract `createCatalogDraft`, ADR 0002), named [name], next
 * to the catalog's other drafts, each released on its own.
 *
 * - With [from] a released version (its number or its id), it starts as a copy of that release,
 *   which is its base. A fix to an older line is a draft from a release of that line.
 * - With [from] another draft's id (or `next`), it starts as a copy of that draft, unreleased changes
 *   included, on that draft's base.
 * - Without [from], it starts as a copy of the catalog's highest release, or empty with no base when
 *   the catalog has none.
 *
 * A copy keeps every resource's identity. Refused for a subscribed catalog, for a name another draft
 * of the catalog has, and when [from] names no version of the catalog.
 */
data class CreateCatalogDraft(
    val catalog: CatalogId,
    val name: String,
    val from: String? = null,
) : Command<CatalogRelease>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        validateDraftName(name)
    }
}

/** Renames a draft (part of contract `updateCatalogDraft`, see [UpdateCatalogDraft]); its id does not change. The next version has no name to change. */
data class RenameCatalogDraft(
    val catalog: CatalogId,
    val draft: DraftRef,
    val name: String,
) : Command<CatalogRelease>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        validateDraftName(name)
    }
}

/**
 * Discards a draft and everything changed in it (contract `deleteCatalogDraft`): its content rows,
 * ready marks and dependency declarations. A resource keeps its identity while another version holds
 * it.
 *
 * Discarding the **next version** throws its changes away: it starts again as a copy of the latest
 * release (empty when there is none), so everyday work always has somewhere to go (a convention of
 * this suite). Any other draft is simply gone. Environment overrides that follow it end.
 *
 * Refused ([CatalogDraftInUseException]) while a draft of another catalog names it in a dependency
 * declaration, and ([CatalogReleaseHeldException]) while the draft alone holds a resource something
 * outside it still needs: the tenant's default theme, a code list an attribute of another version
 * binds, a theme a template of another version uses.
 */
data class DeleteCatalogDraft(
    val catalog: CatalogId,
    val draft: DraftRef,
) : Command<Unit>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_RELEASE
}

/** A draft has no name the user cannot see, nor one that reads as the next release, by its name now or its former one. */
internal fun validateDraftName(name: String) {
    validate("name", name.isNotBlank(), ValidationCode.GENERIC) { "Give the work a name" }
    validate("name", name.length <= FieldLimits.MAX_NAME_LENGTH, ValidationCode.GENERIC) { "A name is at most ${FieldLimits.MAX_NAME_LENGTH} characters" }
    val reserved = listOf(CatalogDraft.NEXT_LABEL, CatalogDraft.FORMER_NEXT_LABEL, DraftRef.NEXT)
    validate("name", reserved.none { name.trim().equals(it, ignoreCase = true) }, ValidationCode.GENERIC) {
        "'${name.trim()}' is what the next release is called; choose another name"
    }
}

/** Another draft of the catalog is called [name] already; names tell drafts apart, so each is unique. */
class CatalogDraftNameInUseException(val catalogKey: CatalogKey, val name: String) : RuntimeException("Catalog '${catalogKey.value}' already has work called '$name'")

@Component
class CreateCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogReleaseReader,
    private val copier: CatalogReleaseCopier,
) : CommandHandler<CreateCatalogDraft, CatalogRelease> {
    override fun handle(command: CreateCatalogDraft): CatalogRelease {
        requireCatalogEditable(command.catalog)
        val catalog = command.catalog
        val (tenantKey, catalogKey) = catalog.tenantKey to catalog.key
        val name = command.name.trim()
        val created = jdbi.withHandle<UUID, Exception> { handle ->
            val open = drafts.drafts(handle, catalog)
            if (open.any { it.name.equals(name, ignoreCase = true) }) throw CatalogDraftNameInUseException(catalogKey, name)
            val all = versions.all(handle, catalog)
            val from = command.from?.trim()
            val source = from?.let { DraftRef.parseDraft(it) }?.let { ref -> open.firstOrNull { it.ref == ref || (ref is DraftRef.Id && it.key == ref.key) } }
            val release = if (source != null) {
                null
            } else if (from != null) {
                all.firstOrNull { it.status == CatalogReleaseStatus.RELEASED && (it.version == from || it.versionId == from) }
                    ?: throw CatalogReleaseNotFoundException(catalogKey, from)
            } else {
                CatalogDrafts.latest(all.filter { it.status == CatalogReleaseStatus.RELEASED }.mapNotNull { it.version })
                    ?.let { highest -> all.first { it.status == CatalogReleaseStatus.RELEASED && it.version == highest } }
            }
            when {
                source != null -> drafts.open(handle, catalog, source.base, name, source.key).also { drafts.copyContent(handle, tenantKey, source.versionId, it) }
                release != null -> drafts.open(handle, catalog, release.version, name).also { copier.copy(catalog, release, it) }
                else -> drafts.open(handle, catalog, base = null, name = name)
            }
        }
        return jdbi.withHandle<CatalogRelease, Exception> { handle ->
            val key = drafts.drafts(handle, catalog).first { it.versionId == created }.key
            versions.all(handle, catalog).first { it.status == CatalogReleaseStatus.DRAFT && it.versionId == key }
        }
    }
}

/**
 * The next version, a convention of this suite, handed to parallel work as it is released
 * ([app.epistola.suite.catalog.commands.ReleaseCatalogDraft.continueEverydayWork]): everyday work
 * then continues from that release. The draft that was the next version is discarded with its
 * changes, or, with `keepAs`, kept as parallel work under that name. Nothing is merged.
 */
@Component
class NextVersionHandOver(
    private val drafts: CatalogDrafts,
    private val holdersCheck: DeleteCatalogDraftHandler,
    private val dependencyStore: CatalogDependencyStore,
) {
    /**
     * Refuses the hand-over before anything is released: for a name another draft has, and while the
     * next version, when discarded, alone holds what something outside it uses
     * ([CatalogReleaseHeldException]) or is named by another catalog's draft ([CatalogDraftInUseException]).
     */
    fun check(handle: Handle, catalog: CatalogId, work: CatalogDraft, keepAs: String?) {
        val open = drafts.drafts(handle, catalog)
        val previous = open.firstOrNull { it.isNext } ?: return
        if (keepAs != null && open.any { it.versionId != previous.versionId && it.name.equals(keepAs, ignoreCase = true) }) {
            throw CatalogDraftNameInUseException(catalog.key, keepAs)
        }
        if (keepAs == null) {
            val holders = holdersCheck.holders(handle, catalog.tenantKey, previous.versionId)
            if (holders.isNotEmpty()) throw CatalogReleaseHeldException(catalog.key, previous.label, holders)
            val namedBy = dependencyStore.namedBy(handle, catalog.tenantKey, previous.versionId)
            if (namedBy.isNotEmpty()) throw CatalogDraftInUseException(catalog.key, previous.label, namedBy)
        }
        check(work.versionId != previous.versionId) { "the next version is the next version already" }
    }

    /** Makes [work] the next version, after [check]; the caller then advances it onto its release. */
    fun apply(handle: Handle, catalog: CatalogId, work: CatalogDraft, keepAs: String?) {
        val previous = drafts.drafts(handle, catalog).firstOrNull { it.isNext }
        // A discarded next version stops being served where it was deployed, in the history.
        if (previous != null && keepAs == null) DraftDeploymentStore.endFor(handle, catalog.tenantKey, previous.versionId)
        drafts.promote(handle, catalog.tenantKey, work.versionId, previous?.versionId, keepAs)
    }
}

@Component
class RenameCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogReleaseReader,
) : CommandHandler<RenameCatalogDraft, CatalogRelease> {
    override fun handle(command: RenameCatalogDraft): CatalogRelease {
        requireCatalogEditable(command.catalog)
        val name = command.name.trim()
        return jdbi.withHandle<CatalogRelease, Exception> { handle ->
            val open = drafts.drafts(handle, command.catalog)
            val draft = drafts.find(handle, command.catalog, command.draft) ?: throw CatalogReleaseNotFoundException(command.catalogKey, command.draft.toString())
            // The next release is a convention of this suite, always called "Next release".
            if (draft.isNext) throw CatalogDraftIsNextException(command.catalogKey, "The next release of catalog '${command.catalogKey.value}' has no name of its own")
            if (open.any { it.versionId != draft.versionId && it.name.equals(name, ignoreCase = true) }) throw CatalogDraftNameInUseException(command.catalogKey, name)
            drafts.rename(handle, command.tenantKey, draft.versionId, name)
            versions.all(handle, command.catalog).first { it.status == CatalogReleaseStatus.DRAFT && it.versionId == draft.key }
        }
    }
}

/** Asked of the next version what only another draft can do: being renamed, made the next version, or opened again. */
class CatalogDraftIsNextException(val catalogKey: CatalogKey, message: String) : RuntimeException(message)

/**
 * Copies a released version into a draft. A release's content is the content it retained: rebuilt
 * as its archive and imported, as a ZIP import would, into the draft (the import writes the version
 * the transaction targets). Identities are adopted by address, so a resource another version still
 * holds keeps its identity; one no version holds any more gets a new one.
 */
@Component
class CatalogReleaseCopier(
    private val targeting: CatalogReleaseTargeting,
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val contentBuilder: app.epistola.suite.catalog.CatalogContentBuilder,
    private val fingerprints: app.epistola.suite.catalog.CatalogFingerprintService,
) {
    fun copy(catalog: CatalogId, release: CatalogRelease, into: UUID) {
        val version = requireNotNull(release.version)
        val archive = ExportCatalogZip(catalog, version).execute()
        val imported = targeting.within(into) {
            ImportCatalogZip(
                tenantKey = catalog.tenantKey,
                zipBytes = archive.zipBytes,
                validateCrossCatalogDeps = false,
                confirmMigration = true,
                intoDraft = true,
            ).execute()
        }
        val failed = imported.results.filter { it.status == InstallStatus.FAILED }
        check(failed.isEmpty()) {
            "Copying release $version of '${catalog.key.value}' into a draft failed for " +
                failed.joinToString { "${it.type} '${it.slug}': ${it.errorMessage}" }
        }
        // What the draft holds of its base, as this suite holds content: a merge compares the draft
        // with this rather than with the release's own fingerprints, which for a release received
        // from elsewhere need not match what this suite computes for the same content.
        val held = targeting.within(into) { fingerprints.entryFingerprints(catalog.key, contentBuilder.build(catalog)) }
        jdbi.useHandle<Exception> { drafts.recordBaseFingerprints(it, catalog.tenantKey, into, fingerprints.storeBaseline(held)) }
    }
}

/** Opens a catalog's next version on its latest release, as a copy of it. */
@Component
class NextVersionOpener(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogReleaseReader,
    private val copier: CatalogReleaseCopier,
) {
    /** The new next version's id: a copy of the latest release, or empty when there is none. */
    fun open(catalog: CatalogId): UUID {
        val latest = CatalogDrafts.latest(ListCatalogReleaseSummaries(catalog).query().map { it.version })
        val release = latest?.let { version ->
            jdbi.withHandle<CatalogRelease, Exception> { handle -> versions.all(handle, catalog).first { it.status == CatalogReleaseStatus.RELEASED && it.version == version } }
        }
        val opened = jdbi.withHandle<UUID, Exception> { drafts.open(it, catalog, base = latest) }
        if (release != null) copier.copy(catalog, release, opened)
        return opened
    }
}

@Component
class DeleteCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val nextVersions: NextVersionOpener,
    private val dependencyStore: CatalogDependencyStore,
) : CommandHandler<DeleteCatalogDraft, Unit> {
    override fun handle(command: DeleteCatalogDraft) {
        requireCatalogEditable(command.catalog)
        val catalog = command.catalog
        val (tenantKey, catalogKey) = catalog.tenantKey to catalog.key
        val draft = jdbi.withHandle<CatalogDraft, Exception> { handle ->
            val draft = drafts.find(handle, catalog, command.draft) ?: throw CatalogReleaseNotFoundException(catalogKey, command.draft.toString())
            // What it alone holds is named first: that is the more specific reason, and using a held
            // resource from another catalog also names this draft there.
            val holders = holders(handle, tenantKey, draft.versionId)
            if (holders.isNotEmpty()) throw CatalogReleaseHeldException(catalogKey, draft.label, holders)
            val namedBy = dependencyStore.namedBy(handle, tenantKey, draft.versionId)
            if (namedBy.isNotEmpty()) throw CatalogDraftInUseException(catalogKey, draft.label, namedBy)
            // Environments serving it stop: recorded in their history.
            DraftDeploymentStore.endFor(handle, tenantKey, draft.versionId)
            drafts.delete(handle, tenantKey, draft.versionId)
            draft
        }
        // The next version starts again on the latest release, so everyday work has somewhere to go.
        if (draft.isNext) nextVersions.open(catalog)
    }

    /**
     * What outside draft [versionId] references a resource only it holds, each as `what (resource)`.
     *
     * Discarding the draft deletes the identity of every resource no other version and no release
     * holds (`sync_catalog_resource_identity`), and the references into `catalog_resources` would
     * then fail (the tenant's default theme, an attribute's code list) or be cleared silently (a
     * template's theme). A reference from inside the draft goes with it. A font face's image
     * (`font_variants_asset_fkey`) is always in the family's own version, so it is not looked for.
     */
    fun holders(handle: Handle, tenantKey: TenantKey, versionId: UUID): List<String> = handle.createQuery(
        $$"""
        WITH own AS (
            SELECT resource_id FROM release_resources WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM document_templates WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM assets WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM fonts WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM stencils WHERE tenant_key = :t AND version_id = :v
        ),
        elsewhere AS (
            SELECT resource_id FROM document_templates WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM assets WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM fonts WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM stencils WHERE tenant_key = :t AND version_id <> :v
            -- Every other draft's rows and every release's (ADR 0033): not this draft's own.
            UNION SELECT resource_id FROM release_resources WHERE tenant_key = :t AND version_id IS DISTINCT FROM :v
        ),
        alone AS (SELECT resource_id FROM own EXCEPT SELECT resource_id FROM elsewhere),
        refs (holder, holder_resource_id, held_resource_id, ordinal) AS (
            SELECT 'the tenant''s default theme', NULL::uuid, default_theme_resource_id, 1
            FROM tenants WHERE id = :t AND default_theme_resource_id IN (SELECT resource_id FROM alone)
            -- An attribute of another draft bound to a code list only this draft holds: its binding
            -- resolved through the one alias resolver (resolve_catalog_alias, ADR 0033).
            UNION SELECT 'attribute', r.resource_id, target.resource_id, 2
            FROM release_resources r
            JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash AND x.resource_type = 'codeList'
            JOIN catalog_resources target ON target.tenant_key = r.tenant_key AND target.resource_type = 'codeList'
             AND target.catalog_id = resolve_catalog_alias(r.tenant_key, r.catalog_id, r.version_id, r.version, x.alias)
             AND target.resource_key = x.resource_key
            WHERE r.tenant_key = :t AND r.resource_type = 'attribute' AND r.version_id IS NOT NULL AND r.version_id <> :v
              AND target.resource_id IN (SELECT resource_id FROM alone)
            UNION SELECT 'template', resource_id, theme_resource_id, 3
            FROM document_templates WHERE tenant_key = :t AND version_id <> :v
              AND theme_resource_id IN (SELECT resource_id FROM alone)
        )
        SELECT DISTINCT r.ordinal,
               r.holder || COALESCE(' ' || hc.catalog_key || '/' || h.resource_key, '')
                   || ' (' || CASE held.resource_type WHEN 'asset' THEN 'image' WHEN 'codeList' THEN 'code list' ELSE held.resource_type END
                   || ' ' || dc.catalog_key || '/' || held.resource_key || ')' AS description
        FROM refs r
        JOIN catalog_resources held ON held.tenant_key = :t AND held.resource_id = r.held_resource_id
        JOIN catalogs dc ON dc.tenant_key = held.tenant_key AND dc.catalog_id = held.catalog_id
        LEFT JOIN catalog_resources h ON h.tenant_key = :t AND h.resource_id = r.holder_resource_id
        LEFT JOIN catalogs hc ON hc.tenant_key = h.tenant_key AND hc.catalog_id = h.catalog_id
        ORDER BY r.ordinal, description
        """,
    )
        .bind("t", tenantKey)
        .bind("v", versionId)
        .map { rs, _ -> rs.getString("description") }
        .list()
}
