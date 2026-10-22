// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.releases.HouseTheme
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

data class CreateCatalog(
    override val tenantKey: TenantKey,
    val id: CatalogKey,
    val name: String,
    val description: String? = null,
    /** Its namespace (contract ADR 0003); null for the default, the tenant's key. */
    val namespace: String? = null,
    /** Its registry; null for the configured default registry. */
    val registry: String? = null,
) : Command<Catalog>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_MANAGE

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("namespace", namespace == null || CatalogNamespaces.isValid(namespace), ValidationCode.CATALOG_NAMESPACE_INVALID) {
            "Namespace must be a lowercase slug of ${CatalogNamespaces.MIN_LENGTH} to ${CatalogNamespaces.MAX_LENGTH} characters"
        }
        validate("registry", registry == null || CatalogNamespaces.isValidRegistry(registry), ValidationCode.CATALOG_NAMESPACE_INVALID) {
            "Registry must be a DNS name with at least one dot"
        }
    }
}

@Component
class CreateCatalogHandler(
    private val jdbi: Jdbi,
    private val identity: CatalogIdentityProperties,
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<CreateCatalog, Catalog> {

    override fun handle(command: CreateCatalog): Catalog = executeOrThrowDuplicate("catalog", command.id.value) {
        var houseThemeCatalog: CatalogKey? = null
        val created = jdbi.inTransaction<Catalog, Exception> { handle ->
            val namespace = namespaceFor(handle, command)
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, description, created_at, updated_at)
                VALUES (:id, :tenantKey, :registry, :namespace, :name, :description, NOW(), NOW())
                """,
            )
                .bind("id", command.id)
                .bind("registry", command.registry ?: identity.defaultRegistry)
                .bind("namespace", namespace)
                .bind("tenantKey", command.tenantKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .execute()
            // Its next version, where everyday work goes, is opened by the database with the row.
            // It starts with the tenant's house theme as its default theme (contract ADR 0007, 1a).
            houseThemeCatalog = HouseTheme.applyTo(handle, command.tenantKey, command.id)

            handle.findCatalog(command.tenantKey, command.id)!!
        }
        // A house theme of another catalog is a use of that catalog, declared as any reference is.
        houseThemeCatalog?.takeIf { it != command.id }?.let { usedCatalogs.declare(command.tenantKey, command.id, it) }
        created
    }

    /**
     * The namespace a catalog under this key was published under, which it returns to; otherwise the
     * one asked for, the publishing integration's preferred one, or the tenant's key.
     */
    private fun namespaceFor(handle: Handle, command: CreateCatalog): String {
        val port = publicationPort.ifAvailable
        port?.publishedNamespace(handle, command.tenantKey, command.id)?.let { published ->
            validate("namespace", command.namespace == null || command.namespace == published, ValidationCode.CATALOG_NAMESPACE_INVALID) {
                "A catalog '${command.id.value}' was published under namespace '$published', so it is recreated there"
            }
            return published
        }
        return command.namespace
            ?: port?.preferredNamespace(handle, command.tenantKey)?.takeIf(CatalogNamespaces::isValid)
            ?: CatalogIdentityProperties.defaultNamespace(command.tenantKey)
    }
}
