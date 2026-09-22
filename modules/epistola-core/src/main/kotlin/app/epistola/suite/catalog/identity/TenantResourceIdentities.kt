// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.identity

import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** One resource's tenant-local identity, paired with the address it occupied when recorded. */
data class ResourceIdentityRecord(
    val type: String,
    val catalogKey: String,
    val key: String,
    val resourceId: ResourceIdentity,
)

/**
 * A tenant's identity registry, as carried in and out of a snapshot.
 *
 * Not a public wire format: `resource_id` is deliberately absent from catalog
 * exchange, and these travel only inside a tenant's own snapshot -- which is why they live beside
 * the catalog ZIPs rather than in them.
 */
data class TenantResourceIdentities(
    val resources: List<ResourceIdentityRecord> = emptyList(),
)

/**
 * Reads and re-plants a tenant's identity registry, so a restored resource keeps the identity it
 * had rather than being handed a fresh one.
 *
 * Without this a restore silently breaks the `template_resource_id` on generation history, which is
 * deliberately unprotected by a foreign key, so a dangling one simply stops joining. That is
 * invisible until someone looks for a document by its template.
 */
@Component
class TenantResourceIdentityStore(
    private val jdbi: Jdbi,
) {
    /** Every identity in [catalogKeys]. */
    fun read(tenantKey: TenantKey, catalogKeys: Set<String>): TenantResourceIdentities {
        if (catalogKeys.isEmpty()) return TenantResourceIdentities()
        return jdbi.withHandle<TenantResourceIdentities, Exception> { handle ->
            val resources = handle.createQuery(
                """
                SELECT resource_type, catalog_key::text, resource_key, resource_id
                FROM catalog_resources
                WHERE tenant_key = :tenantKey AND catalog_key IN (<catalogKeys>)
                ORDER BY resource_type, catalog_key, resource_key
                """,
            )
                .bind("tenantKey", tenantKey)
                .bindList("catalogKeys", catalogKeys.toList())
                .map { rs, _ ->
                    ResourceIdentityRecord(
                        type = rs.getString("resource_type"),
                        catalogKey = rs.getString("catalog_key"),
                        key = rs.getString("resource_key"),
                        resourceId = ResourceIdentity.of(rs.getString("resource_id")),
                    )
                }
                .list()

            TenantResourceIdentities(resources)
        }
    }

    /**
     * Registers [resources] at their recorded addresses, before the resources themselves are
     * imported. The sync trigger then adopts each identity rather than minting a new one, which is
     * why this runs first and not after.
     *
     * The registry's foreign key to `catalogs` is deferrable for exactly this: the catalogs these
     * rows name are created by the import that follows, inside the same transaction.
     */
    fun plantIdentities(tenantKey: TenantKey, resources: List<ResourceIdentityRecord>) {
        if (resources.isEmpty()) return
        jdbi.useHandle<Exception> { handle ->
            handle.execute("SET CONSTRAINTS ALL DEFERRED")
            val batch = handle.prepareBatch(
                """
                INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_key, resource_key)
                VALUES (:tenantKey, :resourceId, :resourceType, :catalogKey, :resourceKey)
                ON CONFLICT (tenant_key, resource_id) DO NOTHING
                """,
            )
            for (resource in resources) {
                batch.bind("tenantKey", tenantKey)
                    .bind("resourceId", resource.resourceId)
                    .bind("resourceType", resource.type)
                    .bind("catalogKey", resource.catalogKey)
                    .bind("resourceKey", resource.key)
                    .add()
            }
            batch.execute()
        }
    }
}
