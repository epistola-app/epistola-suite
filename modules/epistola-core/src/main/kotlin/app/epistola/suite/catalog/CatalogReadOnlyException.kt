package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogKey

/**
 * Thrown when attempting to modify resources in a read-only (subscribed) catalog.
 */
class CatalogReadOnlyException(catalogKey: CatalogKey) : RuntimeException("Cannot modify resources in read-only catalog '${catalogKey.value}'. Subscribed catalogs are read-only.")
