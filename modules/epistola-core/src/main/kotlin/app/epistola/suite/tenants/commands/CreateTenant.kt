// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.commands

import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.catalog.namespaces.NamespaceGrantSource
import app.epistola.suite.catalog.namespaces.NamespaceGrantStore
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.catalog.namespaces.validateNamespaceName
import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Routable
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Creates a tenant, with its default catalog and the system catalog. [namespaces] are granted to it
 * at once (ADR 0034, section 3), by the administrator creating it, and its default catalog is named in
 * the first; without one it is named under `local`, in [LocalAuthority.DEFAULT_NAMESPACE], since
 * nothing is derived from the tenant key.
 */
data class CreateTenant(
    val id: TenantKey,
    val name: String,
    val namespaces: List<NamespaceName> = emptyList(),
) : Command<Tenant>,
    EntityIdentifiable,
    Routable,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
    override val entityId: String get() = id.value
    override val routingKey: String get() = id.value
    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        namespaces.forEach { namespace ->
            validateNamespaceName(namespace.authority, namespace.namespace)
            validate("namespaces", !namespace.isLocal, ValidationCode.CATALOG_NAMESPACE_INVALID) { "A local namespace needs no grant." }
        }
        validate("namespaces", namespaces.map { it.namespace }.distinct().size == namespaces.size, ValidationCode.CATALOG_NAMESPACE_TAKEN) {
            "A tenant holds a namespace under one authority."
        }
    }
}

@Component
class CreateTenantHandler(
    private val jdbi: Jdbi,
    private val grants: NamespaceGrantStore,
) : CommandHandler<CreateTenant, Tenant> {
    override fun handle(command: CreateTenant): Tenant = executeOrThrowDuplicate("tenant", command.id.value) {
        val tenant = jdbi.withHandle<Tenant, Exception> { handle ->
            // Insert tenant. Themes are optional — `default_theme_resource_id` stays NULL
            // until a user (or future tenant setting) picks one. Templates without
            // a theme render with engine defaults (A4 portrait, 20mm margins,
            // Helvetica fallback). The bundled `system` catalog ships a `default`
            // theme that can be opted into.
            val inserted = handle.createQuery(
                """
                INSERT INTO tenants (id, name, created_at)
                VALUES (:id, :name, NOW())
                RETURNING *, NULL::text AS default_theme_catalog_key, NULL::text AS default_theme_key
                """,
            )
                .bind("id", command.id)
                .bind("name", command.name)
                .mapTo<Tenant>()
                .one()

            command.namespaces.forEach { grants.grant(handle, command.id, it, NamespaceGrantSource.ADMINISTRATOR, currentUserIdOrNull()) }

            // Create the tenant's default catalog so authored resources have a home, named in the
            // first namespace it was granted, or under local.
            val defaultName = command.namespaces.firstOrNull() ?: NamespaceName(LocalAuthority.NAME, LocalAuthority.DEFAULT_NAMESPACE)
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_id, catalog_key, tenant_key, registry, namespace, name, created_at, updated_at)
                VALUES (epistola_uuidv7(), 'default', :tenantKey, :registry, :namespace, 'Default', NOW(), NOW())
                """,
            )
                .bind("tenantKey", command.id)
                .bind("registry", defaultName.authority)
                .bind("namespace", defaultName.namespace)
                .execute()
            // Its next version (contract ADR 0002) is opened by the database with the row, as for any catalog.

            inserted
        }

        // Install the bundled system catalog (reserved attributes, canonical code
        // lists, and the `default` theme). Runs in the same command transaction
        // boundary, so a failure to install rolls back the tenant creation — a
        // tenant should never exist without its system catalog.
        // `InstallSystemCatalog` is idempotent.
        InstallSystemCatalog(tenantKey = command.id).execute()

        tenant
    }
}
