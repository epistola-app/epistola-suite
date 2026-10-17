// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A catalog and one of its versions, as an address names them. */
data class ResolvedCatalogVersion(
    val catalog: Catalog,
    val version: CatalogVersion,
) {
    val catalogKey: CatalogKey get() = catalog.id
    val isDraft: Boolean get() = version.status == CatalogVersionStatus.DRAFT

    /** @throws CatalogVersionReleasedException when the version is released: only a draft changes. */
    fun requireDraft(): ResolvedCatalogVersion = apply {
        if (!isDraft) throw CatalogVersionReleasedException(catalogKey, version.version)
    }
}

/**
 * Resolves `…/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}` (contract ADR
 * 0002/0003): the tenant's catalog with that name, and its version with that number. [registry]
 * null, or `~`, is the installation's default registry. A catalog whose key matches but whose name
 * does not is another catalog, and not found.
 */
data class ResolveCatalogVersion(
    override val tenantKey: TenantKey,
    val registry: String?,
    val namespace: String,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<ResolvedCatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ResolveCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogVersionReader,
    private val identity: CatalogIdentityProperties,
) : QueryHandler<ResolveCatalogVersion, ResolvedCatalogVersion> {
    override fun handle(query: ResolveCatalogVersion): ResolvedCatalogVersion = jdbi.withHandle<ResolvedCatalogVersion, Exception> { handle ->
        val registry = query.registry?.takeUnless { it == DEFAULT_REGISTRY } ?: identity.defaultRegistry
        val catalog = handle.findCatalog(query.tenantKey, query.catalogKey)
            ?.takeIf { it.registry == registry && it.namespace == query.namespace }
            ?: throw CatalogNotFoundException(query.catalogKey)
        val version = versions.all(handle, query.tenantKey, query.catalogKey).firstOrNull { it.version == query.version }
            ?: throw CatalogVersionNotFoundException(query.catalogKey, query.version)
        ResolvedCatalogVersion(catalog, version)
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
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val type: String,
    val key: String,
) : Query<CatalogResource?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** Every resource of [type] a released version holds, in key order; empty when it kept no content. */
data class ListReleasedResources(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val type: String,
) : Query<List<CatalogResource>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetReleasedResourceHandler(
    private val assembler: ReleaseContentAssembler,
) : QueryHandler<GetReleasedResource, CatalogResource?> {
    override fun handle(query: GetReleasedResource): CatalogResource? = assembler.readResource(query.tenantKey, query.catalogKey, query.version, query.type, query.key)
}

@Component
class ListReleasedResourcesHandler(
    private val assembler: ReleaseContentAssembler,
) : QueryHandler<ListReleasedResources, List<CatalogResource>> {
    override fun handle(query: ListReleasedResources): List<CatalogResource> {
        val content = assembler.assemble(query.tenantKey, query.catalogKey, query.version)?.content ?: return emptyList()
        return content.resourceDetails.filterKeys { it.startsWith("${query.type}/") }.toSortedMap().values.map { it.resource }
    }
}
