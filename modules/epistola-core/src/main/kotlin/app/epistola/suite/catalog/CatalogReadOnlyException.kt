package app.epistola.suite.catalog

import app.epistola.suite.catalog.queries.IsCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/**
 * Thrown when attempting to modify resources in a read-only (subscribed) catalog.
 */
class CatalogReadOnlyException(catalogKey: CatalogKey) : RuntimeException("Cannot modify resources in read-only catalog '${catalogKey.value}'. Subscribed catalogs are read-only.")

/**
 * Checks that the given catalog is editable (AUTHORED). Throws [CatalogReadOnlyException] if not.
 * Call this at the start of any command handler that mutates catalog resources.
 */
fun requireCatalogEditable(tenantKey: TenantKey, catalogKey: CatalogKey) {
    if (!IsCatalogEditable(tenantKey, catalogKey).query()) {
        throw CatalogReadOnlyException(catalogKey)
    }
}
