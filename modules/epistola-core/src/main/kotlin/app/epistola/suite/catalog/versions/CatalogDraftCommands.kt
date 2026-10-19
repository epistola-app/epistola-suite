// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.EnvironmentOverrideStore
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
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val name: String,
    val from: String? = null,
) : Command<CatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        validateDraftName(name)
    }
}

/** Renames a draft (contract `renameCatalogDraft`); its id does not change. The next version has no name to change. */
data class RenameCatalogDraft(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val draft: DraftRef,
    val name: String,
) : Command<CatalogVersion>,
    RequiresPermission {
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
 * declaration, and ([CatalogVersionHeldException]) while the draft alone holds a resource something
 * outside it still needs: the tenant's default theme, a code list an attribute of another version
 * binds, a theme a template of another version uses.
 */
data class DeleteCatalogDraft(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val draft: DraftRef,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_RELEASE
}

/**
 * Opens an authored catalog's next version where it has none, as a copy of its latest release (empty
 * when there is none), so everyday work has somewhere to go. A catalog always has one, except one whose
 * drafts were all discarded before amendment 2 of ADR 0002 kept it; this opens it again. Refused when
 * the catalog already has a next version.
 */
data class OpenNextVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<CatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
}

/**
 * Makes a draft the catalog's next version: the draft everyday work goes into, a convention of this
 * suite that the contract does not know. Nothing about either draft's content or base changes.
 *
 * The draft that was the next version is discarded with its changes, or, with [keepPreviousAs], kept
 * as another draft under that name.
 *
 * Refused for the next version itself, for a name another draft has, and while the next version,
 * when discarded, alone holds what something outside it uses ([CatalogVersionHeldException]) or is
 * named by another catalog's draft ([CatalogDraftInUseException]).
 */
data class PromoteCatalogDraft(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val draft: DraftRef,
    val keepPreviousAs: String? = null,
) : Command<CatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_RELEASE

    init {
        keepPreviousAs?.let(::validateDraftName)
    }
}

/** A draft has no name the user cannot see, nor one that reads as the next version. */
private fun validateDraftName(name: String) {
    validate("name", name.isNotBlank(), ValidationCode.GENERIC) { "Give the work a name" }
    validate("name", name.length <= FieldLimits.MAX_NAME_LENGTH, ValidationCode.GENERIC) { "A name is at most ${FieldLimits.MAX_NAME_LENGTH} characters" }
    validate("name", !name.trim().equals(CatalogDraft.NEXT_LABEL, ignoreCase = true) && !name.trim().equals(DraftRef.NEXT, ignoreCase = true), ValidationCode.GENERIC) {
        "'${name.trim()}' is what the next version is called; choose another name"
    }
}

/** Another draft of the catalog is called [name] already; names tell drafts apart, so each is unique. */
class CatalogDraftNameInUseException(val catalogKey: CatalogKey, val name: String) : RuntimeException("Catalog '${catalogKey.value}' already has work called '$name'")

@Component
class CreateCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
    private val copier: CatalogReleaseCopier,
) : CommandHandler<CreateCatalogDraft, CatalogVersion> {
    override fun handle(command: CreateCatalogDraft): CatalogVersion {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        val (tenantKey, catalogKey) = command.tenantKey to command.catalogKey
        val name = command.name.trim()
        val created = jdbi.withHandle<UUID, Exception> { handle ->
            val open = drafts.drafts(handle, tenantKey, catalogKey)
            if (open.any { it.name.equals(name, ignoreCase = true) }) throw CatalogDraftNameInUseException(catalogKey, name)
            val all = versions.all(handle, tenantKey, catalogKey)
            val from = command.from?.trim()
            val source = from?.let { DraftRef.parse(it) }?.let { ref -> open.firstOrNull { it.ref == ref || (ref is DraftRef.Id && it.key == ref.key) } }
            val release = if (source != null) {
                null
            } else if (from != null) {
                all.firstOrNull { it.status == CatalogVersionStatus.RELEASED && (it.version == from || it.versionId == from) }
                    ?: throw CatalogVersionNotFoundException(catalogKey, from)
            } else {
                CatalogDrafts.latest(all.filter { it.status == CatalogVersionStatus.RELEASED }.mapNotNull { it.version })
                    ?.let { highest -> all.first { it.status == CatalogVersionStatus.RELEASED && it.version == highest } }
            }
            when {
                source != null -> drafts.open(handle, tenantKey, catalogKey, source.base, name, source.key).also { drafts.copyContent(handle, tenantKey, source.versionId, it) }
                release != null -> drafts.open(handle, tenantKey, catalogKey, release.version, name).also { copier.copy(tenantKey, catalogKey, release, it) }
                else -> drafts.open(handle, tenantKey, catalogKey, base = null, name = name)
            }
        }
        return jdbi.withHandle<CatalogVersion, Exception> { handle ->
            val key = drafts.drafts(handle, tenantKey, catalogKey).first { it.versionId == created }.key
            versions.all(handle, tenantKey, catalogKey).first { it.status == CatalogVersionStatus.DRAFT && it.versionId == key }
        }
    }
}

@Component
class PromoteCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
    private val holdersCheck: DeleteCatalogDraftHandler,
    private val dependencyStore: CatalogDependencyStore,
) : CommandHandler<PromoteCatalogDraft, CatalogVersion> {
    override fun handle(command: PromoteCatalogDraft): CatalogVersion {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        val (tenantKey, catalogKey) = command.tenantKey to command.catalogKey
        return jdbi.inTransaction<CatalogVersion, Exception> { handle ->
            val open = drafts.drafts(handle, tenantKey, catalogKey)
            val work = drafts.find(handle, tenantKey, catalogKey, command.draft) ?: throw CatalogVersionNotFoundException(catalogKey, command.draft.toString())
            if (work.isNext) throw CatalogDraftIsNextException(catalogKey, "The next version of catalog '${catalogKey.value}' is the next version already")
            val previous = open.firstOrNull { it.isNext }
            val keepAs = command.keepPreviousAs?.trim()
            if (keepAs != null && open.any { it.versionId != previous?.versionId && it.name.equals(keepAs, ignoreCase = true) }) {
                throw CatalogDraftNameInUseException(catalogKey, keepAs)
            }
            val ended = if (previous != null && keepAs == null) {
                val holders = holdersCheck.holders(handle, tenantKey, previous.versionId)
                if (holders.isNotEmpty()) throw CatalogVersionHeldException(catalogKey, previous.label, holders)
                val namedBy = dependencyStore.namedBy(handle, tenantKey, previous.versionId)
                if (namedBy.isNotEmpty()) throw CatalogDraftInUseException(catalogKey, previous.label, namedBy)
                EnvironmentOverrideStore.list(handle, tenantKey, null, catalogKey).filter { it.draftId == previous.key }
            } else {
                emptyList()
            }
            drafts.promote(handle, tenantKey, work.versionId, previous?.versionId, keepAs)
            EnvironmentOverrideStore.recordEnded(handle, tenantKey, ended)
            versions.all(handle, tenantKey, catalogKey).first { it.status == CatalogVersionStatus.DRAFT && it.versionId == work.key }
        }
    }
}

@Component
class RenameCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
) : CommandHandler<RenameCatalogDraft, CatalogVersion> {
    override fun handle(command: RenameCatalogDraft): CatalogVersion {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        val name = command.name.trim()
        return jdbi.withHandle<CatalogVersion, Exception> { handle ->
            val open = drafts.drafts(handle, command.tenantKey, command.catalogKey)
            val draft = drafts.find(handle, command.tenantKey, command.catalogKey, command.draft) ?: throw CatalogVersionNotFoundException(command.catalogKey, command.draft.toString())
            // The next version is a convention of this suite, always called "Next version".
            if (draft.isNext) throw CatalogDraftIsNextException(command.catalogKey, "The next version of catalog '${command.catalogKey.value}' has no name of its own")
            if (open.any { it.versionId != draft.versionId && it.name.equals(name, ignoreCase = true) }) throw CatalogDraftNameInUseException(command.catalogKey, name)
            drafts.rename(handle, command.tenantKey, draft.versionId, name)
            versions.all(handle, command.tenantKey, command.catalogKey).first { it.status == CatalogVersionStatus.DRAFT && it.versionId == draft.key }
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
    private val targeting: CatalogVersionTargeting,
) {
    fun copy(tenantKey: TenantKey, catalogKey: CatalogKey, release: CatalogVersion, into: UUID) {
        val version = requireNotNull(release.version)
        val archive = ExportCatalogZip(tenantKey, catalogKey, version).execute()
        val imported = targeting.within(into) {
            ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = archive.zipBytes,
                catalogType = CatalogType.AUTHORED,
                validateCrossCatalogDeps = false,
                confirmMigration = true,
                intoDraft = true,
            ).execute()
        }
        val failed = imported.results.filter { it.status == InstallStatus.FAILED }
        check(failed.isEmpty()) {
            "Copying release $version of '${catalogKey.value}' into a draft failed for " +
                failed.joinToString { "${it.type} '${it.slug}': ${it.errorMessage}" }
        }
    }
}

@Component
class OpenNextVersionHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
    private val nextVersions: NextVersionOpener,
) : CommandHandler<OpenNextVersion, CatalogVersion> {
    override fun handle(command: OpenNextVersion): CatalogVersion {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        jdbi.withHandle<CatalogDraft?, Exception> { drafts.next(it, command.tenantKey, command.catalogKey) }
            ?.let { throw CatalogDraftIsNextException(command.catalogKey, "Catalog '${command.catalogKey.value}' has a next version already") }
        val opened = nextVersions.open(command.tenantKey, command.catalogKey)
        return jdbi.withHandle<CatalogVersion, Exception> { handle -> versions.all(handle, command.tenantKey, command.catalogKey).first { it.isNext } }
    }
}

/** Opens a catalog's next version on its latest release, as a copy of it. */
@Component
class NextVersionOpener(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
    private val copier: CatalogReleaseCopier,
) {
    /** The new next version's id: a copy of the latest release, or empty when there is none. */
    fun open(tenantKey: TenantKey, catalogKey: CatalogKey): UUID {
        val latest = CatalogDrafts.latest(ListCatalogReleases(tenantKey, catalogKey).query().map { it.version })
        val release = latest?.let { version ->
            jdbi.withHandle<CatalogVersion, Exception> { handle -> versions.all(handle, tenantKey, catalogKey).first { it.status == CatalogVersionStatus.RELEASED && it.version == version } }
        }
        val opened = jdbi.withHandle<UUID, Exception> { drafts.open(it, tenantKey, catalogKey, base = latest) }
        if (release != null) copier.copy(tenantKey, catalogKey, release, opened)
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
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        val (tenantKey, catalogKey) = command.tenantKey to command.catalogKey
        val draft = jdbi.withHandle<CatalogDraft, Exception> { handle ->
            val draft = drafts.find(handle, tenantKey, catalogKey, command.draft) ?: throw CatalogVersionNotFoundException(catalogKey, command.draft.toString())
            val namedBy = dependencyStore.namedBy(handle, tenantKey, draft.versionId)
            if (namedBy.isNotEmpty()) throw CatalogDraftInUseException(catalogKey, draft.label, namedBy)
            val holders = holders(handle, tenantKey, draft.versionId)
            if (holders.isNotEmpty()) throw CatalogVersionHeldException(catalogKey, draft.label, holders)
            // Environments following it stop: recorded in their history.
            val ended = EnvironmentOverrideStore.list(handle, tenantKey, null, catalogKey).filter { it.draftId == draft.key }
            drafts.delete(handle, tenantKey, draft.versionId)
            EnvironmentOverrideStore.recordEnded(handle, tenantKey, ended)
            draft
        }
        // The next version starts again on the latest release, so everyday work has somewhere to go.
        if (draft.isNext) nextVersions.open(tenantKey, catalogKey)
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
            SELECT resource_id FROM themes WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM document_templates WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM variant_attribute_definitions WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM code_lists WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM assets WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM fonts WHERE tenant_key = :t AND version_id = :v
            UNION SELECT resource_id FROM stencils WHERE tenant_key = :t AND version_id = :v
        ),
        elsewhere AS (
            SELECT resource_id FROM themes WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM document_templates WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM variant_attribute_definitions WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM code_lists WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM assets WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM fonts WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM stencils WHERE tenant_key = :t AND version_id <> :v
            UNION SELECT resource_id FROM release_entries WHERE tenant_key = :t
        ),
        alone AS (SELECT resource_id FROM own EXCEPT SELECT resource_id FROM elsewhere),
        refs (holder, holder_resource_id, held_resource_id, ordinal) AS (
            SELECT 'the tenant''s default theme', NULL::uuid, default_theme_resource_id, 1
            FROM tenants WHERE id = :t AND default_theme_resource_id IN (SELECT resource_id FROM alone)
            UNION SELECT 'attribute', resource_id, code_list_resource_id, 2
            FROM variant_attribute_definitions WHERE tenant_key = :t AND version_id <> :v
              AND code_list_resource_id IN (SELECT resource_id FROM alone)
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
