// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
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
    private val drafts: CatalogDrafts,
) : CommandHandler<CreateCatalog, Catalog> {

    override fun handle(command: CreateCatalog): Catalog = executeOrThrowDuplicate("catalog", command.id.value) {
        jdbi.withHandle<Catalog, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, description, type, created_at, updated_at)
                VALUES (:id, :tenantKey, :registry, :namespace, :name, :description, 'AUTHORED', NOW(), NOW())
                """,
            )
                .bind("id", command.id)
                .bind("registry", command.registry ?: identity.defaultRegistry)
                .bind("namespace", command.namespace ?: CatalogIdentityProperties.defaultNamespace(command.tenantKey))
                .bind("tenantKey", command.tenantKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .execute()
            // Its working copy is its next version (contract ADR 0002, amendment 2).
            drafts.open(handle, command.tenantKey, command.id, base = null)

            handle.findCatalog(command.tenantKey, command.id)!!
        }
    }
}
