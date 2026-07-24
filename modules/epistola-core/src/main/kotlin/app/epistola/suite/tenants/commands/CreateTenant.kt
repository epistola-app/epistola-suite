// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.commands

import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Routable
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class CreateTenant(
    val id: TenantKey,
    val name: String,
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
    }
}

@Component
class CreateTenantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateTenant, Tenant> {
    @Transactional
    override fun handle(command: CreateTenant): Tenant = executeOrThrowDuplicate("tenant", command.id.value) {
        val tenant = jdbi.withHandle<Tenant, Exception> { handle ->
            // Insert tenant. Themes are optional — `default_theme_key` stays NULL
            // until a user (or future tenant setting) picks one. Templates without
            // a theme render with engine defaults (A4 portrait, 20mm margins,
            // Helvetica fallback). The bundled `system` catalog ships a `default`
            // theme that can be opted into.
            val inserted = handle.createQuery(
                """
                INSERT INTO tenants (id, name, created_at)
                VALUES (:id, :name, NOW())
                RETURNING *
                """,
            )
                .bind("id", command.id)
                .bind("name", command.name)
                .mapTo<Tenant>()
                .one()

            // Create the tenant's default catalog so authored resources have a home.
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, type, created_at, updated_at)
                VALUES ('default', :tenantKey, 'Default', 'AUTHORED', NOW(), NOW())
                """,
            )
                .bind("tenantKey", command.id)
                .execute()

            inserted
        }

        // Install the bundled system catalog (reserved attributes, canonical code
        // lists, and the `default` theme). Runs in the same `@Transactional`
        // boundary, so a failure to install rolls back the tenant creation — a
        // tenant should never exist without its system catalog.
        // `InstallSystemCatalog` is idempotent.
        InstallSystemCatalog(tenantKey = command.id).execute()

        tenant
    }
}
