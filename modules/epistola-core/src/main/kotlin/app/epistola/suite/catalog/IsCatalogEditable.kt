package app.epistola.suite.catalog.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Checks if a catalog allows resource modifications.
 * Returns true for AUTHORED catalogs, false for SUBSCRIBED (read-only).
 * Throws if the catalog does not exist.
 *
 * System-internal: called from within command handlers that already have authorization.
 */
data class IsCatalogEditable(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<Boolean>,
    SystemInternal

@Component
class IsCatalogEditableHandler(
    private val jdbi: Jdbi,
) : QueryHandler<IsCatalogEditable, Boolean> {

    override fun handle(query: IsCatalogEditable): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            """
            SELECT type = 'AUTHORED'
            FROM catalogs
            WHERE tenant_key = :tenantKey AND id = :catalogKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .mapTo(Boolean::class.java)
            .findOne()
            .orElseThrow { IllegalArgumentException("Catalog not found: ${query.catalogKey}") }
    }
}
