// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Where each catalog publishes, and when that stops being changeable.
 *
 * A catalog publishes under its own namespace, `catalogs.namespace` (contract ADR 0003: the registry
 * grants it), so there is no second namespace setting. What this keeps is the record of what reached
 * Exchange: once a release has, the namespace is fixed, and the record deliberately outlives the
 * local catalog, because Exchange keeps what was published under those coordinates and a catalog
 * recreated under the same key must return to the same namespace.
 */
@Component
class ExchangeNamespaceBinder {

    /** The catalog's namespace, or null when the tenant has no such catalog. */
    fun namespaceOf(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle.createQuery(
        "SELECT namespace FROM catalogs WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
        .mapTo(String::class.java).findOne().orElse(null)

    /** The namespace a release under [catalogKey] reached Exchange in, or null when none has. */
    fun publishedNamespace(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle.createQuery(
        "SELECT namespace FROM catalog_exchange_bindings WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND published_at IS NOT NULL",
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
        .mapTo(String::class.java).findOne().orElse(null)

    /**
     * Namespaces the tenant's active connection currently grants. A namespace granted earlier is not
     * proof of a present grant: an organization can withdraw one, and a reauthorization can arrive
     * with a different set.
     */
    fun grantedNamespaces(handle: Handle, tenantKey: TenantKey): Set<String> = handle.createQuery(
        "SELECT namespaces FROM exchange_tenant_connections WHERE tenant_key = :tenantKey AND status = 'ACTIVE'",
    ).bind("tenantKey", tenantKey)
        .map { rs, _ -> (rs.getArray("namespaces").array as Array<*>).mapNotNull { it?.toString() }.toSet() }
        .findOne().orElse(emptySet())

    /** The tenant's preferred namespace, which a new catalog starts with. */
    fun tenantDefault(handle: Handle, tenantKey: TenantKey): String? = handle.createQuery(
        "SELECT default_namespace FROM exchange_tenant_connections WHERE tenant_key = :tenantKey AND status = 'ACTIVE'",
    ).bind("tenantKey", tenantKey).mapTo(String::class.java).findOne().orElse(null)

    /**
     * Records that a release of this catalog reached Exchange under [namespace], fixing it for good.
     *
     * Kept apart from the publication rows: those are tied to their releases and disappear with the
     * catalog, while Exchange's copy does not.
     */
    fun markPublished(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, namespace: String) {
        handle.createUpdate(
            """
            INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace, published_at)
            VALUES (:tenantKey, :catalogKey, :namespace, NOW())
            ON CONFLICT (tenant_key, catalog_key) DO UPDATE SET published_at = COALESCE(catalog_exchange_bindings.published_at, NOW())
            """,
        ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).bind("namespace", namespace).execute()
    }

    /** True once a release has reached Exchange under this catalog's key. */
    fun isLocked(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Boolean = publishedNamespace(handle, tenantKey, catalogKey) != null
}
