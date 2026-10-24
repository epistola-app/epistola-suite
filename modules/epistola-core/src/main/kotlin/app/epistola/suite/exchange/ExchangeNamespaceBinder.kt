// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Where each catalog publishes, and when that stops being changeable.
 *
 * A catalog publishes under its own namespace, `catalogs.namespace` (contract ADR 0003: the registry
 * grants it), so there is no second namespace setting. What this keeps is the record of what reached
 * Exchange: once a release has, the namespace is fixed, and the record deliberately outlives the
 * local catalog, because Exchange keeps what was published under those coordinates. A binding is by
 * the catalog's address (ADR 0034, section 9): two catalogs with one key keep one each.
 */
@Component
class ExchangeNamespaceBinder {

    /** The catalog's namespace, or null when the tenant has no such catalog. */
    fun namespaceOf(handle: Handle, catalog: CatalogId): String? = handle.createQuery(
        "SELECT namespace FROM catalogs WHERE tenant_key = :tenantKey AND catalog_id = epistola_catalog_id(:tenantKey, :catalogKey_ns, :catalogKey)",
    ).bind("tenantKey", catalog.tenantKey).bindCatalog("catalogKey", catalog)
        .mapTo(String::class.java).findOne().orElse(null)

    /** Whether the catalog is named under `local` (ADR 0034, section 7), which is never published. */
    fun isLocal(handle: Handle, catalog: CatalogId): Boolean = handle.createQuery(
        "SELECT registry FROM catalogs WHERE tenant_key = :tenantKey AND catalog_id = epistola_catalog_id(:tenantKey, :catalogKey_ns, :catalogKey)",
    ).bind("tenantKey", catalog.tenantKey).bindCatalog("catalogKey", catalog)
        .mapTo(String::class.java).findOne().map(LocalAuthority::isLocal).orElse(false)

    /**
     * The one namespace a release of a catalog with key [catalogKey] reached Exchange in, for a new
     * catalog under that key that names no namespace, which returns there; null when none has, or
     * several have.
     */
    fun publishedNamespace(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle.createQuery(
        "SELECT namespace FROM catalog_exchange_bindings WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND published_at IS NOT NULL",
    ).bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
        .mapTo(String::class.java).list().singleOrNull()

    /** Whether a release of [catalog] reached Exchange under its address. */
    fun isPublished(handle: Handle, catalog: CatalogId): Boolean = handle.createQuery(
        "SELECT EXISTS (SELECT 1 FROM catalog_exchange_bindings WHERE tenant_key = :tenantKey AND namespace = :namespace AND catalog_key = :catalogKey AND published_at IS NOT NULL)",
    ).bind("tenantKey", catalog.tenantKey).bind("namespace", catalog.namespace).bind("catalogKey", catalog.key)
        .mapTo(Boolean::class.java).one()

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
    fun markPublished(handle: Handle, catalog: CatalogId, namespace: String) {
        handle.createUpdate(
            """
            INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace, published_at)
            VALUES (:tenantKey, :catalogKey, :namespace, NOW())
            ON CONFLICT (tenant_key, namespace, catalog_key) DO UPDATE SET published_at = COALESCE(catalog_exchange_bindings.published_at, NOW())
            """,
        ).bind("tenantKey", catalog.tenantKey).bind("catalogKey", catalog.key).bind("namespace", namespace).execute()
    }

    /** True once a release has reached Exchange under this catalog's address. */
    fun isLocked(handle: Handle, catalog: CatalogId): Boolean = isPublished(handle, catalog)
}
