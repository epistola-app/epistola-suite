package app.epistola.suite.assets

import app.epistola.suite.common.ids.TenantKey

/** The shared dedup scope every non-sensitive asset uses (issue #738). */
const val GLOBAL_ASSET_SCOPE = "global"

/**
 * The content-addressable dedup scope for an asset blob (issue #738).
 *
 * - **non-sensitive → [GLOBAL_ASSET_SCOPE]** — identical bytes dedup once installation-wide,
 *   maximizing savings. This is the default and fits branding assets (logos, images, fonts).
 * - **sensitive → the tenant key** — stored in isolation, so there is no cross-tenant
 *   existence side-channel (a tenant can't infer another uploaded the same bytes) and physical
 *   erasure is clean. Sensitive assets still dedup against each other **within** a tenant.
 *
 * Derived — never stored as a separate column — so it can't drift. The SQL equivalent used by
 * the reaper and the backups dump is `CASE WHEN sensitive THEN tenant_key ELSE 'global' END`.
 *
 * The backend honours the flag today; surfacing it on the UI / REST / catalog-exchange formats
 * is tracked in issue #751 (and the contract-repo catalog-format issue).
 */
fun assetContentScope(sensitive: Boolean, tenantKey: TenantKey): String = if (sensitive) tenantKey.value else GLOBAL_ASSET_SCOPE
