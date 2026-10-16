// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo

/**
 * The opaque `catalog_id` of the catalog a caller named by key.
 *
 * Tables reference a catalog by `catalog_id`; a caller that still names it by key resolves the key
 * here, in one place. `uq_catalogs_key_until_named` keeps a key unique in its tenant until callers
 * name catalogs fully, so this yields one catalog or none. None is NULL, which matches no row on a
 * read and violates the NOT NULL `catalog_id` on a write -- so neither silently succeeds.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 * @param catalogParam name of the bound catalog-key parameter.
 */
fun catalogAt(tenantParam: String, catalogParam: String): String = "(SELECT catalog_id FROM catalogs WHERE tenant_key = :$tenantParam AND catalog_key = :$catalogParam)"

/** The catalog with key [catalogKey] in [tenantKey], or null. */
fun Handle.findCatalog(tenantKey: TenantKey, catalogKey: CatalogKey): Catalog? = createQuery(
    "SELECT * FROM catalogs WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
)
    .bind("tenantKey", tenantKey)
    .bind("catalogKey", catalogKey)
    .mapTo<Catalog>()
    .findOne()
    .orElse(null)
