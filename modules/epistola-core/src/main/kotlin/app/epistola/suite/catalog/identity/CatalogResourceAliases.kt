// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.identity

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * A tenant's historical resource addresses and where they now point.
 *
 * Every consumer that resolves an address a resource used to hold needs this same map, and needs it
 * with the same shadowing rule: an alias is ignored once a live resource occupies its address
 * again, exactly as [ResolveCatalogResourceAddress] orders them. Loading it in one place is what
 * stops those consumers drifting apart — a gap that has already produced two bugs, an export that
 * redirected references it should have left alone, and a republish that failed because the draft
 * copied from a published version still named the old address.
 */
@Component
class CatalogResourceAliases {
    fun load(handle: Handle, tenantKey: TenantKey): Map<ResourceAddress, ResourceAddress> = handle.createQuery(
        """
        SELECT aliases.resource_type,
               aliases.catalog_key::text AS from_catalog,
               aliases.resource_key      AS from_key,
               resources.catalog_key::text AS to_catalog,
               resources.resource_key      AS to_key
        FROM catalog_resource_aliases aliases
        JOIN catalog_resources resources
          ON resources.tenant_key = aliases.tenant_key
         AND resources.resource_id = aliases.target_resource_id
         AND resources.resource_type = aliases.resource_type
        WHERE aliases.tenant_key = :tenantKey
          AND NOT EXISTS (
              SELECT 1 FROM catalog_resources shadow
              WHERE shadow.tenant_key = aliases.tenant_key
                AND shadow.resource_type = aliases.resource_type
                AND shadow.catalog_key = aliases.catalog_key
                AND shadow.resource_key = aliases.resource_key
          )
        """,
    )
        .bind("tenantKey", tenantKey)
        .map { rs, _ ->
            val type = CatalogResourceType.entries.single { it.wireName == rs.getString("resource_type") }
            ResourceAddress(type, rs.getString("from_catalog"), rs.getString("from_key")) to
                ResourceAddress(type, rs.getString("to_catalog"), rs.getString("to_key"))
        }
        .list()
        .toMap()

    /**
     * Rewrites references in [root] that name an address a resource has since left.
     *
     * Applied when mutable content is written, so a draft reopened from a version published before
     * a move names the resource where it actually lives. Immutable payloads are never passed
     * through this — they keep their original bytes and resolve through the alias at read time.
     */
    fun canonicalize(root: JsonNode, aliases: Map<ResourceAddress, ResourceAddress>): Boolean {
        if (aliases.isEmpty()) return false
        var changed = false
        for (site in ResourceReferenceSites.scan(root)) {
            val from = site.catalogKey?.let { ResourceAddress(site.kind.type, it, site.key) } ?: continue
            val to = aliases[from] ?: continue
            site.setCatalogKey(to.catalogKey)
            site.setKey(to.key)
            changed = true
        }
        return changed
    }
}
