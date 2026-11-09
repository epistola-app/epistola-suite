// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.queries

import app.epistola.suite.applications.APPLICATION_COLUMNS
import app.epistola.suite.applications.APPLICATION_FROM
import app.epistola.suite.applications.Application
import app.epistola.suite.applications.toApplication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** The tenant's applications, by name. A tenant has a handful, so the list is not paged. */
data class ListApplications(
    val tenantId: TenantKey,
) : Query<List<Application>>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
    override val tenantKey get() = tenantId
}

@Component
class ListApplicationsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListApplications, List<Application>> {

    override fun handle(query: ListApplications): List<Application> = jdbi.withHandle<List<Application>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT $APPLICATION_COLUMNS
            $APPLICATION_FROM
            WHERE a.tenant_key = :tenantId
            ORDER BY lower(a.name), a.id
            """,
        )
            .bind("tenantId", query.tenantId)
            .map { rs, _ -> rs.toApplication() }
            .list()
    }
}
