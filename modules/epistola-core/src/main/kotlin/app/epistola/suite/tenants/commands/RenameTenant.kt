// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.commands

import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Routable
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Renames a tenant. Returns the updated [Tenant], or null when no tenant with
 * [tenantId] exists (so the caller can distinguish not-found from success).
 */
data class RenameTenant(
    override val tenantId: TenantKey,
    val name: String,
) : Command<Tenant?>,
    TenantScoped,
    EntityIdentifiable,
    Routable,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
    override val tenantKey: TenantKey get() = tenantId
    override val entityId: String get() = tenantId.value
    override val routingKey: String get() = tenantId.value

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class RenameTenantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RenameTenant, Tenant?> {
    @Transactional
    override fun handle(command: RenameTenant): Tenant? = jdbi.withHandle<Tenant?, Exception> { handle ->
        handle.createQuery(
            """
            UPDATE tenants
            SET name = :name
            WHERE id = :tenantId
            RETURNING *
            """,
        )
            .bind("tenantId", command.tenantId)
            .bind("name", command.name)
            .mapTo<Tenant>()
            .findOne()
            .orElse(null)
    }
}
