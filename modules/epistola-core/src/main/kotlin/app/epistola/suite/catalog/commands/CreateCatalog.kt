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
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.catalog.namespaces.NamespaceGrantStore
import app.epistola.suite.catalog.namespaces.NamespaceMessages
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.catalog.namespaces.heldNamespaces
import app.epistola.suite.catalog.releases.HouseTheme
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
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
    /**
     * The namespace it is named in (ADR 0034, section 3): one the tenant holds, or any under `local`.
     * Null for the one the tenant holds, or the preferred of several; refused when that is no answer.
     */
    val namespace: String? = null,
    /**
     * The authority of [namespace] (the contract's `registry`): null for the one the tenant holds the
     * namespace under, or [LocalAuthority.NAME].
     */
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
        validate("registry", registry == null || LocalAuthority.isValidAuthority(registry), ValidationCode.CATALOG_NAMESPACE_INVALID) {
            "Registry must be a DNS name with at least one dot, or local"
        }
    }
}

@Component
class CreateCatalogHandler(
    private val jdbi: Jdbi,
    private val identity: CatalogIdentityProperties,
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
    private val usedCatalogs: UsedCatalogDeclarer,
    private val grants: NamespaceGrantStore,
) : CommandHandler<CreateCatalog, Catalog> {

    override fun handle(command: CreateCatalog): Catalog = executeOrThrowDuplicate("catalog", command.id.value) {
        var houseThemeCatalog: CatalogKey? = null
        val created = jdbi.inTransaction<Catalog, Exception> { handle ->
            val name = nameFor(handle, command)
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, description, created_at, updated_at)
                VALUES (:id, :tenantKey, :registry, :namespace, :name, :description, NOW(), NOW())
                """,
            )
                .bind("id", command.id)
                .bind("registry", name.authority)
                .bind("namespace", name.namespace)
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
     * The name a new catalog gets (ADR 0034, section 3): the namespace a catalog under this key was
     * published under, which it returns to; otherwise the one asked for, which the tenant must hold
     * unless it is local; otherwise the one the tenant holds, or the preferred of several. Never
     * derived from the tenant key.
     */
    private fun nameFor(handle: Handle, command: CreateCatalog): NamespaceName {
        val port = publicationPort.ifAvailable
        port?.publishedNamespace(handle, command.tenantKey, command.id)?.let { published ->
            validate("namespace", command.namespace == null || command.namespace == published, ValidationCode.CATALOG_NAMESPACE_INVALID) {
                "A catalog '${command.id.value}' was published under namespace '$published', so it is recreated there"
            }
            return requireHeld(handle, command, NamespaceName(identity.defaultRegistry, published))
        }
        val registry = command.registry
        val namespace = command.namespace
        val name = when {
            namespace != null -> NamespaceName(
                registry ?: grants.authorityOf(handle, command.tenantKey, namespace) ?: identity.defaultRegistry,
                namespace,
            )

            LocalAuthority.isLocal(registry) -> throw ValidationException(
                "namespace",
                "Choose the namespace to name the catalog in under local.",
                ValidationCode.CATALOG_NAMESPACE_REQUIRED,
            )

            else -> {
                val held = heldNamespaces(handle, command.tenantKey, grants, identity, port)
                val candidates = held.held.filter { registry == null || it.authority == registry }
                candidates.singleOrNull() ?: held.preferred?.takeIf { it in candidates } ?: throw ValidationException(
                    "namespace",
                    if (candidates.isEmpty()) {
                        "This tenant holds no namespace${registry?.let { " under $it" }.orEmpty()}. Name the catalog under local, with a namespace you choose, or ask an administrator to grant one."
                    } else {
                        "This tenant holds several namespaces (${candidates.joinToString(", ")}): choose the one to name the catalog in."
                    },
                    ValidationCode.CATALOG_NAMESPACE_REQUIRED,
                )
            }
        }
        return requireHeld(handle, command, name)
    }

    /** [name], if the tenant may name a catalog in it: it holds it, or it is local and the token is not held elsewhere. */
    private fun requireHeld(handle: Handle, command: CreateCatalog, name: NamespaceName): NamespaceName {
        if (name.isLocal) {
            val held = grants.authorityOf(handle, command.tenantKey, name.namespace)
            validate("namespace", held == null, ValidationCode.CATALOG_NAMESPACE_TAKEN) {
                "This tenant holds the namespace ${name.namespace} under $held; name the catalog there, or choose another local namespace."
            }
            return name
        }
        validate("namespace", grants.holds(handle, command.tenantKey, name), ValidationCode.CATALOG_NAMESPACE_NOT_HELD) { NamespaceMessages.notHeld(name) }
        return name
    }
}
