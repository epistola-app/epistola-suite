// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.CatalogReleaseNotRetainedException
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Opens a draft numbered [version] of an authored catalog (contract `createCatalogVersion`, ADR 0002).
 *
 * - Without [from], the draft starts as a copy of its **base**: the highest released version below its
 *   number, or empty when there is none.
 * - With [from], it starts as a copy of that version, a draft or a released one, below or above the new
 *   number. Its base is [from] when that is released, and [from]'s base when it is a draft.
 *
 * A copy keeps every resource's identity. Refused when [version] is in use (drafts and releases), for
 * a subscribed catalog, and when the version to copy is a release that kept no content.
 */
data class CreateCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val from: String? = null,
) : Command<CatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        validate("version", SemVer.parseOrNull(version) != null, ValidationCode.GENERIC) { "Version '$version' must be MAJOR.MINOR.PATCH" }
        validate("from", from == null || SemVer.parseOrNull(from) != null, ValidationCode.GENERIC) { "Version '$from' must be MAJOR.MINOR.PATCH" }
    }
}

/**
 * Discards a draft and everything changed in it (contract `deleteCatalogVersion`): its content rows,
 * ready marks and dependency declarations. A resource keeps its identity while another version holds
 * it. Refused for a released version, and ([CatalogVersionHeldException]) while the draft alone holds a
 * resource something outside it still needs: the tenant's default theme, a code list an attribute of
 * another version binds, a theme a template of another version uses. The contract does not keep a catalog's last draft: one without drafts gets one
 * again by [CreateCatalogVersion].
 */
data class DeleteCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_RELEASE
}

@Component
class CreateCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
    private val targeting: CatalogVersionTargeting,
) : CommandHandler<CreateCatalogVersion, CatalogVersion> {
    override fun handle(command: CreateCatalogVersion): CatalogVersion {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        val (tenantKey, catalogKey) = command.tenantKey to command.catalogKey
        val all = jdbi.withHandle<List<CatalogVersion>, Exception> { versions.all(it, tenantKey, catalogKey) }
        if (all.any { it.version == command.version }) throw CatalogVersionInUseException(catalogKey, command.version)
        val releases = all.filter { it.status == CatalogVersionStatus.RELEASED }

        val source: CatalogVersion? = when (command.from) {
            null -> CatalogDrafts.highestBelow(command.version, releases.map { it.version })?.let { base -> releases.first { it.version == base } }
            else -> all.firstOrNull { it.version == command.from } ?: throw CatalogVersionNotFoundException(catalogKey, command.from)
        }
        val base = when (source?.status) {
            null -> null
            CatalogVersionStatus.RELEASED -> source.version
            CatalogVersionStatus.DRAFT -> source.base
        }
        // The contract records the source only when it is not the base.
        val createdFrom = command.from?.takeIf { it != base }

        val created = jdbi.withHandle<java.util.UUID, Exception> { drafts.open(it, tenantKey, catalogKey, command.version, base, createdFrom) }
        when (source?.status) {
            null -> {}

            CatalogVersionStatus.DRAFT -> jdbi.useHandle<Exception> { handle ->
                val from = requireNotNull(drafts.named(handle, tenantKey, catalogKey, source.version))
                drafts.copyContent(handle, tenantKey, from.versionId, created)
            }

            CatalogVersionStatus.RELEASED -> copyRelease(command, source, created)
        }
        return jdbi.withHandle<CatalogVersion, Exception> { handle -> versions.all(handle, tenantKey, catalogKey).first { it.version == command.version } }
    }

    /**
     * A released version's content is the content it retained: rebuilt as its archive and imported,
     * as a ZIP import would, into the new draft (the import writes the version the transaction
     * targets). Identities are adopted by address, so a resource another version still holds keeps its
     * identity; one no version holds any more gets a new one.
     */
    private fun copyRelease(command: CreateCatalogVersion, release: CatalogVersion, into: java.util.UUID) {
        if (release.contentRetained != true) throw CatalogReleaseNotRetainedException(command.catalogKey, release.version)
        val archive = ExportCatalogZip(command.tenantKey, command.catalogKey, release.version).execute()
        val imported = targeting.within(into) {
            ImportCatalogZip(
                tenantKey = command.tenantKey,
                zipBytes = archive.zipBytes,
                catalogType = CatalogType.AUTHORED,
                validateCrossCatalogDeps = false,
                confirmMigration = true,
                intoDraft = true,
            ).execute()
        }
        val failed = imported.results.filter { it.status == InstallStatus.FAILED }
        check(failed.isEmpty()) {
            "Copying release ${release.version} of '${command.catalogKey.value}' into draft ${command.version} failed for " +
                failed.joinToString { "${it.type} '${it.slug}': ${it.errorMessage}" }
        }
    }
}

@Component
class DeleteCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
) : CommandHandler<DeleteCatalogVersion, Unit> {
    override fun handle(command: DeleteCatalogVersion) {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        jdbi.useHandle<Exception> { handle ->
            val draft = drafts.named(handle, command.tenantKey, command.catalogKey, command.version)
            if (draft == null) {
                val released = versions.all(handle, command.tenantKey, command.catalogKey).any { it.version == command.version }
                if (released) throw CatalogVersionReleasedException(command.catalogKey, command.version)
                throw CatalogVersionNotFoundException(command.catalogKey, command.version)
            }
            val holders = holders(handle, command.tenantKey, draft.versionId)
            if (holders.isNotEmpty()) throw CatalogVersionHeldException(command.catalogKey, command.version, holders)
            drafts.delete(handle, command.tenantKey, draft.versionId)
        }
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
    private fun holders(handle: Handle, tenantKey: TenantKey, versionId: UUID): List<String> = handle.createQuery(
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
