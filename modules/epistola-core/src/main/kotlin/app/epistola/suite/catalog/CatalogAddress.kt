// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogKey

/**
 * Which catalog of a tenant this is: its namespace and key, unique within the tenant (ADR 0034,
 * phase 4). A key alone is not: two catalogs called `letters`, from two namespaces, live side by side,
 * so whatever holds every catalog of a tenant at once keys them by this.
 *
 * Written `namespace/key`, as the catalog's pages are addressed.
 */
data class CatalogAddress(val namespace: String, val key: CatalogKey) {
    override fun toString(): String = "$namespace/${key.value}"

    companion object {
        /**
         * The value of [values] for the catalog [catalogKey] means here, looked up as the database
         * does (`epistola_catalog_id`): the one catalog with that key, else the one the running work
         * names ([CatalogNames]). When the key names several and the work names none, the answer they
         * share, if they share one; null otherwise.
         */
        fun <V> lookUp(values: Map<CatalogAddress, V>, catalogKey: CatalogKey): V? {
            val candidates = values.filterKeys { it.key == catalogKey }
            if (candidates.size <= 1) return candidates.values.firstOrNull()
            CatalogNames.current()[catalogKey.value]?.let { return values[CatalogAddress(it, catalogKey)] }
            return candidates.values.distinct().singleOrNull()
        }
    }
}

/** Which catalog of its tenant this is. */
val Catalog.address: CatalogAddress get() = CatalogAddress(namespace, id)
