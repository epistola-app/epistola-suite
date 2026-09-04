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
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class SetTenantCatalogPublishingDefault(
    override val tenantId: TenantKey,
    val publishByDefault: Boolean,
) : Command<Tenant>,
    TenantScoped,
    EntityIdentifiable,
    Routable,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
    override val tenantKey: TenantKey get() = tenantId
    override val entityId: String get() = tenantId.value
    override val routingKey: String get() = tenantId.value
}

@Component
class SetTenantCatalogPublishingDefaultHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetTenantCatalogPublishingDefault, Tenant> {
    override fun handle(command: SetTenantCatalogPublishingDefault): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
        handle.createQuery(
            """
                UPDATE tenants
                SET publish_catalogs_by_default = :publishByDefault
                WHERE id = :tenantId
                RETURNING *
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("publishByDefault", command.publishByDefault)
            .mapTo<Tenant>()
            .one()
    }
}
