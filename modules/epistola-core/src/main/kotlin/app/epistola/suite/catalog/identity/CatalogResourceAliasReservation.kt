// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.identity

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Thrown when a resource would be created at an address a relocated resource still answers to.
 *
 * Addresses are how published payloads name their dependencies, so reusing one is ambiguous: a
 * version published before the move meant the resource that has since left, while the new resource
 * would claim the same name. Rather than silently repoint historical references, the address stays
 * reserved until [ReleaseCatalogResourceAlias] deliberately gives it up.
 */
class CatalogResourceAddressReservedException(
    val address: ResourceAddress,
) : IllegalStateException(
    "The address ${address.catalogKey}/${address.key} is reserved by a relocated ${address.type.wireName}; " +
        "release the alias first or choose another key",
)

/**
 * Guards creation of a resource at a reserved address.
 *
 * Deliberately a command-layer rule rather than a database constraint: catalog import and tenant
 * backup restore must reproduce stored state faithfully, including a resource that legitimately
 * predates an alias.
 *
 * Only [app.epistola.suite.stencils.commands.CreateStencil] calls this today, because stencils are
 * the only relocatable type and no alias can exist for a type that cannot move. Making another type
 * movable must wire this into that type's create command in the same change — omitting it fails
 * nothing, it just lets a published reference be silently repointed at a different resource.
 */
fun requireAddressAvailable(handle: Handle, tenantKey: TenantKey, address: ResourceAddress) {
    val reserved = handle.createQuery(
        """
        SELECT EXISTS(
            SELECT 1 FROM catalog_resource_aliases
            WHERE tenant_key = :tenantKey
              AND resource_type = :resourceType
              AND catalog_key = :catalogKey
              AND resource_key = :resourceKey
        )
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("resourceType", address.type.wireName)
        .bind("catalogKey", address.catalogKey)
        .bind("resourceKey", address.key)
        .mapTo(Boolean::class.java)
        .one()
    if (reserved) throw CatalogResourceAddressReservedException(address)
}

/** What giving up a reserved address would cost. */
data class CatalogResourceAliasImpact(
    val address: ResourceAddress,
    val canonical: ResourceAddress?,
    /** References that currently resolve only because this alias exists. */
    val dependentReferenceCount: Int,
)

data class PreviewCatalogResourceAliasRelease(
    override val tenantKey: TenantKey,
    val address: ResourceAddress,
) : Query<CatalogResourceAliasImpact?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * Gives up a reserved address. References that resolved through it stop resolving, so callers are
 * expected to show [PreviewCatalogResourceAliasRelease] first.
 */
data class ReleaseCatalogResourceAlias(
    override val tenantKey: TenantKey,
    val address: ResourceAddress,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class PreviewCatalogResourceAliasReleaseHandler(
    private val jdbi: Jdbi,
) : QueryHandler<PreviewCatalogResourceAliasRelease, CatalogResourceAliasImpact?> {
    override fun handle(query: PreviewCatalogResourceAliasRelease): CatalogResourceAliasImpact? = jdbi.withHandle<CatalogResourceAliasImpact?, Exception> { handle ->
        val canonical = handle.createQuery(
            """
            SELECT resources.catalog_key::text, resources.resource_key
            FROM catalog_resource_aliases aliases
            JOIN catalog_resources resources
              ON resources.tenant_key = aliases.tenant_key
             AND resources.resource_id = aliases.target_resource_id
            WHERE aliases.tenant_key = :tenantKey
              AND aliases.resource_type = :resourceType
              AND aliases.catalog_key = :catalogKey
              AND aliases.resource_key = :resourceKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("resourceType", query.address.type.wireName)
            .bind("catalogKey", query.address.catalogKey)
            .bind("resourceKey", query.address.key)
            .map { rs, _ -> ResourceAddress(query.address.type, rs.getString(1), rs.getString(2)) }
            .findOne()
            .orElse(null)
            ?: return@withHandle null

        CatalogResourceAliasImpact(
            address = query.address,
            canonical = canonical,
            dependentReferenceCount = countDependentReferences(handle, query.tenantKey, query.address),
        )
    }

    private fun countDependentReferences(handle: Handle, tenantKey: TenantKey, address: ResourceAddress): Int {
        if (address.type != CatalogResourceType.STENCIL) return 0
        return handle.createQuery(
            """
            SELECT COUNT(*) FROM (
                SELECT jsonb_path_query(template_model, '$.** ? (@.type == "stencil")') node
                FROM template_versions WHERE tenant_key = :tenantKey
                UNION ALL
                SELECT jsonb_path_query(content, '$.** ? (@.type == "stencil")') node
                FROM stencil_versions WHERE tenant_key = :tenantKey
            ) nodes
            WHERE node -> 'props' ->> 'stencilId' = :resourceKey
              AND node -> 'props' ->> 'catalogKey' = :catalogKey
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("resourceKey", address.key)
            .bind("catalogKey", address.catalogKey)
            .mapTo(Int::class.java)
            .one()
    }
}

@Component
class ReleaseCatalogResourceAliasHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ReleaseCatalogResourceAlias, Unit> {
    override fun handle(command: ReleaseCatalogResourceAlias) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM catalog_resource_aliases
                WHERE tenant_key = :tenantKey
                  AND resource_type = :resourceType
                  AND catalog_key = :catalogKey
                  AND resource_key = :resourceKey
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("resourceType", command.address.type.wireName)
                .bind("catalogKey", command.address.catalogKey)
                .bind("resourceKey", command.address.key)
                .execute()
        }
    }
}
