// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.queries

import app.epistola.suite.applications.APPLICATION_COLUMNS
import app.epistola.suite.applications.APPLICATION_FROM
import app.epistola.suite.applications.Application
import app.epistola.suite.applications.toApplication
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** One application of the tenant, or null when the tenant has none by that id. */
data class GetApplication(
    override val tenantKey: TenantKey,
    val id: ApplicationKey,
) : Query<Application?>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
}

@Component
class GetApplicationHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetApplication, Application?> {

    override fun handle(query: GetApplication): Application? = jdbi.withHandle<Application?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT $APPLICATION_COLUMNS
            $APPLICATION_FROM
            WHERE a.id = :id AND a.tenant_key = :tenantId
            """,
        )
            .bind("id", query.id)
            .bind("tenantId", query.tenantKey)
            .map { rs, _ -> rs.toApplication() }
            .findOne()
            .orElse(null)
    }
}
