// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogName
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.catalogName
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.findByTenantAndId
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
 * Existing catalogs were given their tenant's key as namespace on the move to 2.0, so changing it has
 * to be easy: it is one statement, allowed as long as the catalog has never been published under its
 * namespace. A subscribed catalog is named by its publisher and is refused.
 *
 * Releases already cut keep the namespace they were cut with: they are history.
 */
data class ChangeCatalogNamespace(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val namespace: String,
) : Command<Catalog>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_MANAGE

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
) : CommandHandler<ChangeCatalogNamespace, Catalog> {

    override fun handle(command: ChangeCatalogNamespace): Catalog = jdbi.inTransaction<Catalog, Exception> { handle ->
        val catalog = handle.findByTenantAndId<Catalog>("catalogs", command.tenantKey, command.catalogKey.value)
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.type == CatalogType.SUBSCRIBED) throw CatalogReadOnlyException(command.catalogKey)
        if (catalog.namespace == command.namespace) return@inTransaction catalog
        if (publicationPort.ifAvailable?.isNamespaceFixed(handle, command.tenantKey, command.catalogKey) == true) {
            throw CatalogNamespaceFixedException(catalog.catalogName)
        }
        handle.createUpdate(
            "UPDATE catalogs SET namespace = :namespace, updated_at = NOW() WHERE tenant_key = :tenantKey AND id = :catalogKey",
        )
            .bind("namespace", command.namespace)
            .bind("tenantKey", command.tenantKey)
            .bind("catalogKey", command.catalogKey)
            .execute()
        handle.findByTenantAndId<Catalog>("catalogs", command.tenantKey, command.catalogKey.value)!!
    }
}
