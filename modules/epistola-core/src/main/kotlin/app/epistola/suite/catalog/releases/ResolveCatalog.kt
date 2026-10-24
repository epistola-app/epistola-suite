// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.RequiresAuthentication
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Resolves `…/catalogs/{registry}/{namespace}/{catalogKey}` (contract ADR 0003) to the tenant's
 * catalog with that name, for the operations that address a catalog rather than one of its versions,
 * and for a request body that names a catalog. [registry] null, or `~`, is the installation's default
 * registry. A catalog whose key matches but whose name does not is another catalog, and not found.
 *
 * Any member of the tenant may resolve a name: the operation the name is resolved for checks its own
 * permission, so a document generator can name the catalog it generates from without viewing catalogs.
 */
data class ResolveCatalog(
    /** The catalog by its address, namespace and key. */
    val catalog: CatalogId,
    val registry: String?,
) : Query<Catalog>,
    RequiresAuthentication,
    TenantScoped {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

@Component
class ResolveCatalogHandler(
    private val jdbi: Jdbi,
    private val identity: CatalogIdentityProperties,
) : QueryHandler<ResolveCatalog, Catalog> {
    override fun handle(query: ResolveCatalog): Catalog = jdbi.withHandle<Catalog, Exception> { handle ->
        val registry = query.registry?.takeUnless { it == ResolveCatalogReleaseHandler.DEFAULT_REGISTRY } ?: identity.defaultRegistry
        handle.findCatalog(query.catalog)
            ?.takeIf { it.registry == registry }
            ?: throw CatalogNotFoundException(query.catalogKey)
    }
}
