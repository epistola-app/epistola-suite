package app.epistola.suite.assets

import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey

/**
 * Derive the content-addressable dedup scope for an asset blob (issue #738).
 *
 * The scope is a privacy boundary, not stored on the asset — always derived from the
 * owning `catalog_key` so it can never drift:
 *
 * - **`"system"`** — bundled / system-catalog assets (fonts, demo images) shared with
 *   every tenant by design, so identical bytes dedup **globally**. High win, no leak.
 * - **the tenant key** — user-uploaded tenant assets dedup only **within the tenant**,
 *   avoiding a cross-tenant existence side-channel (a tenant inferring another uploaded
 *   the same bytes) and keeping tenant erasure clean.
 *
 * Keep this identical everywhere content is written, read, or reaped — the SQL
 * equivalent is `CASE WHEN catalog_key = 'system' THEN 'system' ELSE tenant_key END`.
 */
fun assetContentScope(catalogKey: CatalogKey, tenantKey: TenantKey): String = if (catalogKey == SYSTEM_CATALOG_KEY) "system" else tenantKey.value
