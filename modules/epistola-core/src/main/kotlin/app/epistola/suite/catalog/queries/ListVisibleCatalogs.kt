// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ContentVersions
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The catalogs one version of [catalog] can use resources of (contract ADR 0003): itself, and each
 * catalog it declares, each read at the version its declaration names. What a picker offers there,
 * so it offers what the version renders with and nothing it would have to declare first.
 */
data class ListVisibleCatalogs(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<VisibleCatalogs>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    override val permission get() = Permission.REFERENCE_VIEW
}

/**
 * The catalogs a version can use, its own first, then its declarations in their order.
 *
 * @property catalogs each with the version to read it at.
 */
data class VisibleCatalogs(val catalogs: List<VisibleCatalog>)

/**
 * A catalog a version can use, and the version of it to read: [version] is the release a declaration
 * pins or the draft it names, the addressed version for the catalog itself, and null for its next
 * version (a declaration naming a catalog with nothing released yet).
 */
data class VisibleCatalog(val catalog: CatalogId, val version: CatalogReleaseRef?)

@Component
class ListVisibleCatalogsHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : QueryHandler<ListVisibleCatalogs, VisibleCatalogs> {

    override fun handle(query: ListVisibleCatalogs): VisibleCatalogs = jdbi.withHandle<VisibleCatalogs, Exception> { handle ->
        handle.findCatalog(query.catalog) ?: throw CatalogNotFoundException(query.catalog.key)
        val tenantKey = query.tenantKey
        val own = VisibleCatalog(query.catalog, query.catalogVersion)
        // A declaration of a catalog, or of a release or draft of it, the tenant lacks makes nothing visible.
        val declared = store.declarations(handle, handle.versionOf(query.catalog, query)).mapNotNull { declaration ->
            val catalog = store.catalogNamed(handle, tenantKey, declaration) ?: return@mapNotNull null
            val draft = declaration.draftId
            val release = declaration.releaseVersion
            when {
                draft != null -> DraftRef.Id(draft).takeIf { store.draftOf(handle, tenantKey, declaration, draft) != null }?.let { VisibleCatalog(catalog, CatalogReleaseRef(catalog, it)) }
                release != null -> ContentVersions.release(handle, catalog, release)?.let { VisibleCatalog(catalog, CatalogReleaseRef(catalog, DraftRef.Released(release))) }
                else -> VisibleCatalog(catalog, null)
            }
        }
        VisibleCatalogs(listOf(own) + declared)
    }
}
