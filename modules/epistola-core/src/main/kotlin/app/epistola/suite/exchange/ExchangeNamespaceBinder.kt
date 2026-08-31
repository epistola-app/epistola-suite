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
 * A catalog publishes into exactly one Exchange namespace for its whole life. The choice is always
 * **explicit** — nothing is inferred from a tenant default at publish time, because the result is
 * permanent and a permanent decision should not be made by a fallback. The tenant default is only
 * the value the picker starts on.
 *
 * The binding deliberately outlives the local catalog: Exchange keeps what was published under those
 * coordinates, so a catalog recreated under the same key must return to the same namespace.
 */
@Component
class ExchangeNamespaceBinder {

    /** The catalog's namespace, or null while it has never been chosen. */
    fun existingBinding(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle.createQuery(
        "SELECT namespace FROM catalog_exchange_bindings WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
        .mapTo(String::class.java).findOne().orElse(null)

    /**
     * Namespaces the tenant's active connection currently grants. A binding made earlier is not
     * proof of a present grant: an organization can withdraw one, and a reauthorization can arrive
     * with a different set.
     */
    fun grantedNamespaces(handle: Handle, tenantKey: TenantKey): Set<String> = handle.createQuery(
        "SELECT namespaces FROM exchange_tenant_connections WHERE tenant_key = :tenantKey AND status = 'ACTIVE'",
    ).bind("tenantKey", tenantKey)
        .map { rs, _ -> (rs.getArray("namespaces").array as Array<*>).mapNotNull { it?.toString() }.toSet() }
        .findOne().orElse(emptySet())

    /** The tenant's preferred namespace, offered as the starting value when a catalog is first set. */
    fun tenantDefault(handle: Handle, tenantKey: TenantKey): String? = handle.createQuery(
        "SELECT default_namespace FROM exchange_tenant_connections WHERE tenant_key = :tenantKey AND status = 'ACTIVE'",
    ).bind("tenantKey", tenantKey).mapTo(String::class.java).findOne().orElse(null)

    /**
     * Records that a release of this catalog has reached Exchange, fixing the namespace for good.
     *
     * Kept on the binding rather than derived from the publication rows: those are tied to their
     * releases and disappear with the catalog, while Exchange's copy does not.
     */
    fun markPublished(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey) {
        handle.createUpdate(
            """
            UPDATE catalog_exchange_bindings SET published_at = COALESCE(published_at, NOW())
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
            """,
        ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).execute()
    }

    /** True once a release has reached Exchange under this binding. */
    fun isLocked(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Boolean = handle.createQuery(
        """
        SELECT COALESCE(published_at IS NOT NULL, FALSE) FROM catalog_exchange_bindings
        WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
        """,
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
        .mapTo(Boolean::class.java).findOne().orElse(false)

    fun bind(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, namespace: String) {
        handle.createUpdate(
            """
            INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace)
            VALUES (:tenantKey, :catalogKey, :namespace)
            ON CONFLICT (tenant_key, catalog_key) DO UPDATE SET namespace = EXCLUDED.namespace, bound_at = NOW()
            """,
        ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).bind("namespace", namespace).execute()
    }
}
