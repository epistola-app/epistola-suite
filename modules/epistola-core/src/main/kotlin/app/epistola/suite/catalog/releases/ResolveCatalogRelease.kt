// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.ReleaseResourceStore
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A catalog and one of its versions, as an address names them. */
data class ResolvedCatalogRelease(
    val catalog: Catalog,
    val version: CatalogRelease,
) {
    val catalogKey: CatalogKey get() = catalog.id
    val isDraft: Boolean get() = version.status == CatalogReleaseStatus.DRAFT

    /** @throws CatalogReleaseIsReleasedException when the version is released: only a draft changes. */
    fun requireDraft(): ResolvedCatalogRelease = apply {
        if (!isDraft) throw CatalogReleaseIsReleasedException(catalogKey, version.version ?: version.label)
    }
}

/**
 * Resolves `…/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}` (contract ADR
 * 0002/0003): the tenant's catalog with that name, and the version [version] names: its id (a draft
 * or a release), a release's number, or `next` for the next version. [registry]
 * null, or `~`, is the installation's default registry. A catalog whose key matches but whose name
 * does not is another catalog, and not found.
 *
 * Membership is enough: resolving an address only finds what the operation using it then checks
 * its own permission for, so an author's key need not also hold `CATALOG_VIEW` to write.
 */
data class ResolveCatalogRelease(
    /** The catalog by its address, namespace and key. */
    val catalog: CatalogId,
    val registry: String?,
    val version: String,
) : Query<ResolvedCatalogRelease>,
    RequiresAuthentication,
    TenantScoped {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

@Component
class ResolveCatalogReleaseHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
    private val identity: CatalogIdentityProperties,
) : QueryHandler<ResolveCatalogRelease, ResolvedCatalogRelease> {
    override fun handle(query: ResolveCatalogRelease): ResolvedCatalogRelease = jdbi.withHandle<ResolvedCatalogRelease, Exception> { handle ->
        val registry = query.registry?.takeUnless { it == DEFAULT_REGISTRY } ?: identity.defaultRegistry
        val catalog = handle.findCatalog(query.catalog)
            ?.takeIf { it.registry == registry }
            ?: throw CatalogNotFoundException(query.catalogKey)
        // A version is named by its id, a draft and its release alike; a release also by its number
        // (contract ADR 0002). `next`, the next version, is a convention of this suite.
        val all = versions.all(handle, query.catalog)
        val version = when (val ref = DraftRef.parse(query.version)) {
            DraftRef.Next -> all.firstOrNull { it.isNext }
            is DraftRef.Id -> all.firstOrNull { it.versionId == ref.key }
            is DraftRef.Released, null -> all.firstOrNull { it.status == CatalogReleaseStatus.RELEASED && it.version == query.version }
        } ?: throw CatalogReleaseNotFoundException(query.catalogKey, query.version)
        ResolvedCatalogRelease(catalog, version)
    }

    companion object {
        /** How an address names the installation's default registry. */
        const val DEFAULT_REGISTRY: String = "~"
    }
}

/**
 * One resource of a released version, as the release holds it (wire form), or null when it does not
 * hold one of that type and key. A release that did not keep its content holds nothing.
 */
data class GetReleasedResource(
    val catalog: CatalogId,
    val version: String,
    val type: String,
    val key: String,
) : Query<CatalogResource?>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

/** Every resource of [type] a released version holds, in key order. */
data class ListReleasedResources(
    val catalog: CatalogId,
    val version: String,
    val type: String,
) : Query<List<CatalogResource>>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetReleasedResourceHandler(
    private val assembler: ReleaseContentAssembler,
) : QueryHandler<GetReleasedResource, CatalogResource?> {
    override fun handle(query: GetReleasedResource): CatalogResource? = assembler.readResource(query.catalog, query.version, query.type, query.key)
}

/**
 * Reads only that type's rows and contents (ADR 0033): not the whole release, nor its binaries. A
 * list that shows only keys and names reads [ListReleaseResources] instead.
 */
@Component
class ListReleasedResourcesHandler(
    private val assembler: ReleaseContentAssembler,
) : QueryHandler<ListReleasedResources, List<CatalogResource>> {
    override fun handle(query: ListReleasedResources): List<CatalogResource> = assembler.readResources(query.catalog, query.version, query.type)
}

/** One resource of a release as a list shows it: its type, key, name and description. */
data class ReleasedResourceSummary(val type: String, val key: String, val name: String, val description: String?)

/**
 * The resources a released version holds, of [type] or of every type, in type and key order, read
 * from `release_resources` alone (ADR 0033: a list reads no content and no binaries). Empty when
 * the catalog has no such release.
 */
data class ListReleaseResources(
    val catalog: CatalogId,
    val version: String,
    val type: String? = null,
) : Query<List<ReleasedResourceSummary>>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListReleaseResourcesHandler(
    private val jdbi: Jdbi,
    private val rows: ReleaseResourceStore,
) : QueryHandler<ListReleaseResources, List<ReleasedResourceSummary>> {
    override fun handle(query: ListReleaseResources): List<ReleasedResourceSummary> = jdbi.withHandle<List<ReleasedResourceSummary>, Exception> { handle ->
        rows.entriesOf(handle, query.catalog, query.version, query.type).map { ReleasedResourceSummary(it.resourceType, it.resourceKey, it.name, it.description) }
    }
}
