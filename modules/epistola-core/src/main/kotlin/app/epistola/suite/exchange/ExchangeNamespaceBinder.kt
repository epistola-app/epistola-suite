// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Sole owner of the immutable namespace binding rule.
 *
 * A catalog publishes into exactly one Exchange namespace for its whole life. The namespace is
 * chosen the first time a release is queued — the catalog's preference when the connection still
 * grants it, otherwise the tenant's default — and is then frozen in `catalog_exchange_bindings`.
 * Returning `null` means the tenant is not enrolled yet or has no usable namespace; the caller
 * leaves the publication in `WAITING_SETUP` rather than failing.
 *
 * Every path that can create a publication (releasing a version, publishing an unchanged current
 * release, and the worker's deferred setup recheck) resolves through this one method, so the rule
 * cannot drift between them.
 */
@Component
class ExchangeNamespaceBinder {
    fun resolveAndBind(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? {
        existingBinding(handle, tenantKey, catalogKey)?.let { return it }
        val selected = select(handle, tenantKey, catalogKey) ?: return null
        handle.createUpdate(
            """
            INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace)
            VALUES (:tenantKey, :catalogKey, :namespace)
            ON CONFLICT (tenant_key, catalog_key) DO NOTHING
            """,
        ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).bind("namespace", selected).execute()
        // A concurrent binder may have won the insert; the stored value is authoritative.
        return existingBinding(handle, tenantKey, catalogKey)
    }

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

    /**
     * Moves a catalog to a different granted namespace.
     *
     * The binding is immutable *once Exchange has seen a release* — that is what protects published
     * coordinates. Before then it protects nothing, and a namespace chosen by mistake would
     * otherwise be permanent. Publications still queued locally follow the catalog to its new home.
     */
    fun rebind(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, namespace: String) {
        handle.createUpdate(
            """
            INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace)
            VALUES (:tenantKey, :catalogKey, :namespace)
            ON CONFLICT (tenant_key, catalog_key) DO UPDATE SET namespace = EXCLUDED.namespace, bound_at = NOW()
            """,
        ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).bind("namespace", namespace).execute()
    }

    fun existingBinding(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle.createQuery(
        "SELECT namespace FROM catalog_exchange_bindings WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
        .mapTo(String::class.java).findOne().orElse(null)

    private fun select(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle.createQuery(
        """
        SELECT c.exchange_namespace_preference AS preference, x.namespaces, x.default_namespace
        FROM catalogs c
        JOIN exchange_tenant_connections x ON x.tenant_key = c.tenant_key AND x.status = 'ACTIVE'
        WHERE c.tenant_key = :tenantKey AND c.id = :catalogKey
        """,
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey).map { rs, _ ->
        val allowed = (rs.getArray("namespaces").array as Array<*>).map(Any?::toString).toSet()
        val preference = rs.getString("preference")?.takeIf(allowed::contains)
        (preference ?: rs.getString("default_namespace"))?.takeIf(allowed::contains)
    }.findOne().orElse(null)
}
