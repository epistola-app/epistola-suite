// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.identity

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

data class ResolvedCatalogResourceAddress(
    val resourceId: UUID,
    val requested: ResourceAddress,
    val canonical: ResourceAddress,
    val resolvedViaAlias: Boolean,
)

data class ResolveCatalogResourceAddress(
    override val tenantKey: TenantKey,
    val address: ResourceAddress,
) : Query<ResolvedCatalogResourceAddress?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ResolveCatalogResourceAddressHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ResolveCatalogResourceAddress, ResolvedCatalogResourceAddress?> {
    override fun handle(query: ResolveCatalogResourceAddress): ResolvedCatalogResourceAddress? = jdbi.withHandle<ResolvedCatalogResourceAddress?, Exception> { handle ->
        handle.resolveCatalogResourceAddress(query.tenantKey, query.address)
    }
}

/**
 * Where a resource named by an address from outside -- a bookmark, an integration's
 * configuration, an older export -- lives now. Answers with the canonical address only when the
 * given one is a retained alias; a canonical address, or one naming nothing, gives null so the
 * caller's own lookup and not-found handling run unchanged.
 *
 * [SystemInternal] deliberately. Every surface that takes an address from outside resolves it
 * through here *before* dispatching the caller's real operation, and that operation carries the
 * permission. Gating this step would make a generate-only API key fail at resolution on every
 * address, moved or not. What it reveals -- that one tenant-local address now spells another --
 * is already visible to anyone who can see either resource.
 */
data class ResolveCanonicalResourceAddress(
    val tenantKey: TenantKey,
    val address: ResourceAddress,
) : Query<ResourceAddress?>,
    SystemInternal

@Component
class ResolveCanonicalResourceAddressHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ResolveCanonicalResourceAddress, ResourceAddress?> {
    override fun handle(query: ResolveCanonicalResourceAddress): ResourceAddress? = jdbi.withHandle<ResourceAddress?, Exception> { handle ->
        handle.resolveCatalogResourceAddress(query.tenantKey, query.address)?.takeIf { it.resolvedViaAlias }?.canonical
    }
}

/** A canonical resource at the address wins over an alias there, exactly as export orders them. */
internal fun Handle.resolveCatalogResourceAddress(tenantKey: TenantKey, address: ResourceAddress): ResolvedCatalogResourceAddress? = createQuery(
    """
    SELECT resource_id, canonical_type, canonical_catalog, canonical_key, via_alias
    FROM (
        SELECT resource_id,
               resource_type canonical_type,
               catalog_key::text canonical_catalog,
               resource_key canonical_key,
               FALSE via_alias,
               0 priority
        FROM catalog_resources
        WHERE tenant_key = :tenantKey
          AND resource_type = :resourceType
          AND catalog_key = :catalogKey
          AND resource_key = :resourceKey

        UNION ALL

        SELECT resources.resource_id,
               resources.resource_type,
               resources.catalog_key::text,
               resources.resource_key,
               TRUE,
               1
        FROM catalog_resource_aliases aliases
        JOIN catalog_resources resources
          ON resources.tenant_key = aliases.tenant_key
         AND resources.resource_id = aliases.target_resource_id
         AND resources.resource_type = aliases.resource_type
        WHERE aliases.tenant_key = :tenantKey
          AND aliases.resource_type = :resourceType
          AND aliases.catalog_key = :catalogKey
          AND aliases.resource_key = :resourceKey
    ) resolved
    ORDER BY priority
    LIMIT 1
    """,
)
    .bind("tenantKey", tenantKey)
    .bind("resourceType", address.type.wireName)
    .bind("catalogKey", address.catalogKey)
    .bind("resourceKey", address.key)
    .map { rs, _ ->
        ResolvedCatalogResourceAddress(
            resourceId = rs.getObject("resource_id", UUID::class.java),
            requested = address,
            canonical = ResourceAddress(
                type = CatalogResourceType.entries.single { it.wireName == rs.getString("canonical_type") },
                catalogKey = rs.getString("canonical_catalog"),
                key = rs.getString("canonical_key"),
            ),
            resolvedViaAlias = rs.getBoolean("via_alias"),
        )
    }
    .findOne()
    .orElse(null)
