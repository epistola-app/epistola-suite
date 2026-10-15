// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.DeploymentAction
import app.epistola.suite.environments.DeploymentLogEntry
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * What an environment served over time, newest first: every deploy and undeploy, with the release it
 * replaced. Limited in the database.
 */
data class ListDeploymentHistory(
    val environmentId: EnvironmentId,
    val limit: Int = DEFAULT_LIMIT,
    /** Entries to skip, newest first; with [limit], pages the history in the database. */
    val offset: Int = 0,
) : Query<List<DeploymentLogEntry>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = environmentId.tenantKey

    init {
        validate("limit", limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT, got $limit" }
        validate("offset", offset >= 0) { "offset must not be negative, got $offset" }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}

@Component
class ListDeploymentHistoryHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDeploymentHistory, List<DeploymentLogEntry>> {
    override fun handle(query: ListDeploymentHistory): List<DeploymentLogEntry> = jdbi.withHandle<List<DeploymentLogEntry>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT l.environment_key, l.catalog_key, l.action, l.version, l.previous_version, l.changed_at,
                   u.display_name
            FROM environment_deployment_log l
            LEFT JOIN users u ON u.id = l.changed_by
            WHERE l.tenant_key = :t AND l.environment_key = :e
            ORDER BY l.changed_at DESC, l.id DESC
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("t", query.environmentId.tenantKey)
            .bind("e", query.environmentId.key)
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ ->
                DeploymentLogEntry(
                    environmentKey = EnvironmentKey.of(rs.getString("environment_key")),
                    catalogKey = CatalogKey.of(rs.getString("catalog_key")),
                    action = DeploymentAction.valueOf(rs.getString("action")),
                    version = rs.getString("version"),
                    previousVersion = rs.getString("previous_version"),
                    changedAt = rs.getObject("changed_at", OffsetDateTime::class.java),
                    changedByName = rs.getString("display_name"),
                )
            }
            .list()
    }
}

/** How many changes an environment's history holds, to page [ListDeploymentHistory]. */
data class CountDeploymentHistory(
    val environmentId: EnvironmentId,
) : Query<Long>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = environmentId.tenantKey
}

@Component
class CountDeploymentHistoryHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountDeploymentHistory, Long> {
    override fun handle(query: CountDeploymentHistory): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM environment_deployment_log WHERE tenant_key = :t AND environment_key = :e")
            .bind("t", query.environmentId.tenantKey)
            .bind("e", query.environmentId.key)
            .mapTo(Long::class.java)
            .one()
    }
}
