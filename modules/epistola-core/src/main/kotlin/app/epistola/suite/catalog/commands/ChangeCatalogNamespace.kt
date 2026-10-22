// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogName
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.catalogName
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.namespaces.NamespaceGrantStore
import app.epistola.suite.catalog.namespaces.NamespaceMessages
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Gives an authored catalog another namespace (contract ADR 0003).
 *
 * The namespace is also where it publishes on Exchange: there is one. Changing it is one statement,
 * allowed as long as the catalog has never been published under its namespace; work queued for
 * Exchange and not sent yet follows it. A subscribed catalog is named by its publisher and is refused.
 *
 * Releases already cut keep the namespace they were cut with: they are history. The declarations of
 * the tenant's other catalogs that name it follow the new namespace.
 *
 * The new namespace is under the catalog's own authority, and the tenant must hold it (ADR 0034,
 * section 3), unless that authority is `local`.
 */
data class ChangeCatalogNamespace(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val namespace: String,
) : Command<Catalog>,
    RequiresPermission {
    // The namespace is where it publishes: deciding it is part of publishing (CATALOG_PUBLISH).
    override val permission get() = Permission.CATALOG_PUBLISH

    init {
        validate("namespace", CatalogNamespaces.isValid(namespace), ValidationCode.CATALOG_NAMESPACE_INVALID) {
            "Namespace must be a lowercase slug of ${CatalogNamespaces.MIN_LENGTH} to ${CatalogNamespaces.MAX_LENGTH} characters"
        }
    }
}

/** The catalog's namespace is fixed because a release was published under it. */
class CatalogNamespaceFixedException(
    val catalog: CatalogName,
) : RuntimeException("Catalog $catalog has been published under its namespace, which can no longer change")

@Component
class ChangeCatalogNamespaceHandler(
    private val jdbi: Jdbi,
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
    private val grants: NamespaceGrantStore,
) : CommandHandler<ChangeCatalogNamespace, Catalog> {

    override fun handle(command: ChangeCatalogNamespace): Catalog = jdbi.inTransaction<Catalog, Exception> { handle ->
        val catalog = handle.findCatalog(command.tenantKey, command.catalogKey)
            ?: throw CatalogNotFoundException(command.catalogKey)
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        if (catalog.namespace == command.namespace) return@inTransaction catalog
        if (publicationPort.ifAvailable?.isNamespaceFixed(handle, command.tenantKey, command.catalogKey) == true) {
            throw CatalogNamespaceFixedException(catalog.catalogName)
        }
        val target = NamespaceName(catalog.registry, command.namespace)
        if (target.isLocal) {
            val held = grants.authorityOf(handle, command.tenantKey, target.namespace)
            validate("namespace", held == null, ValidationCode.CATALOG_NAMESPACE_TAKEN) {
                "This tenant holds the namespace ${target.namespace} under $held; choose another local namespace."
            }
        } else {
            validate("namespace", grants.holds(handle, command.tenantKey, target), ValidationCode.CATALOG_NAMESPACE_NOT_HELD) { NamespaceMessages.notHeld(target) }
        }
        handle.createUpdate(
            "UPDATE catalogs SET namespace = :namespace, updated_at = NOW() WHERE tenant_key = :tenantKey AND catalog_id = epistola_catalog_id(:tenantKey, :catalogKey)",
        )
            .bind("namespace", command.namespace)
            .bind("tenantKey", command.tenantKey)
            .bind("catalogKey", command.catalogKey)
            .execute()
        // The tenant's other catalogs name this one in their declarations: they follow it. Their
        // locks stay, because the releases they lock to are the same releases.
        handle.createUpdate(
            """
            UPDATE catalog_dependencies SET namespace = :namespace
            WHERE tenant_key = :tenantKey AND registry = :registry AND namespace = :previous AND dependency_key = :catalogKey
            """,
        )
            .bind("namespace", command.namespace)
            .bind("tenantKey", command.tenantKey)
            .bind("registry", catalog.registry)
            .bind("previous", catalog.namespace)
            .bind("catalogKey", command.catalogKey)
            .execute()
        publicationPort.ifAvailable?.namespaceChanged(handle, command.tenantKey, command.catalogKey, command.namespace)
        handle.findCatalog(command.tenantKey, command.catalogKey)!!
    }
}
