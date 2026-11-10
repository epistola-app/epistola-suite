// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.releaseNumberOrder
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A release a catalog template is in: its version, or null for a draft, and its release id.
 */
data class CatalogTemplateRelease(
    val version: String?,
    val releaseId: UUID,
    val draft: Boolean,
)

/**
 * A template of a catalog across the releases a client can reach, with the releases that contain it
 * (contract 2.0.0-rc.2, `listCatalogTemplates`). Its name and description are those of the first of
 * them: a draft first, then the highest release.
 */
data class CatalogTemplate(
    val key: String,
    val name: String,
    val description: String?,
    val releases: List<CatalogTemplateRelease>,
)

/**
 * The templates of [catalog] across the releases a client can reach: with [environmentKey], those
 * deployed to it, its deployed draft included; without it, every release, and the newest draft when
 * the catalog was never released. Only releases that kept their content count — a release whose
 * content is gone has no templates to list. One page of templates by key, paged in the database.
 *
 * @throws EnvironmentNotFoundException when [environmentKey] does not exist
 */
data class ListCatalogTemplates(
    val catalog: CatalogId,
    val environmentKey: EnvironmentKey? = null,
    val searchTerm: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<CatalogTemplate>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = catalog.tenantKey
}

/** How many templates [ListCatalogTemplates] would list over all its pages. */
data class CountCatalogTemplates(
    val catalog: CatalogId,
    val environmentKey: EnvironmentKey? = null,
    val searchTerm: String? = null,
) : Query<Long>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = catalog.tenantKey
}

/**
 * The reachable catalog versions as a common table expression `reachable`, over `catalog_versions`.
 * With an environment, what it serves (`environment_catalog_deployments`); without, every released
 * version, and the newest draft only when there is none.
 */
private fun reachableVersions(withEnvironment: Boolean): String = if (withEnvironment) {
    """
    reachable AS (
        SELECT r.* FROM environment_catalog_deployments d
        JOIN catalog_versions r ON r.tenant_key = d.tenant_key AND r.version_id = d.target_version_id
        WHERE d.tenant_key = :t AND d.environment_key = :e AND d.catalog_id = ${catalogAt("t", "c")}
    )
    """
} else {
    """
    reachable AS (
        SELECT r.* FROM catalog_versions r
        WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.state = 'released'
        UNION ALL
        (SELECT r.* FROM catalog_versions r
         WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.state = 'draft'
           AND NOT EXISTS (SELECT 1 FROM catalog_versions x
                           WHERE x.tenant_key = :t AND x.catalog_id = r.catalog_id AND x.state = 'released')
         ORDER BY r.created_at DESC LIMIT 1)
    )
    """
}

private fun Handle.requireEnvironment(catalog: CatalogId, environmentKey: EnvironmentKey?) {
    if (environmentKey == null) return
    val exists = createQuery("SELECT EXISTS (SELECT 1 FROM environments WHERE tenant_key = :t AND id = :e)")
        .bind("t", catalog.tenantKey)
        .bind("e", environmentKey)
        .mapTo(Boolean::class.java)
        .one()
    if (!exists) throw EnvironmentNotFoundException(catalog.tenantKey, environmentKey)
}

@Component
class ListCatalogTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogTemplates, List<CatalogTemplate>> {
    override fun handle(query: ListCatalogTemplates): List<CatalogTemplate> = jdbi.withHandle<List<CatalogTemplate>, Exception> { handle ->
        handle.requireEnvironment(query.catalog, query.environmentKey)
        val withEnvironment = query.environmentKey != null
        val search = !query.searchTerm.isNullOrBlank()

        // The page: template keys, paged here rather than by slicing a fetched list.
        val keys = handle.createQuery(
            """
            WITH ${reachableVersions(withEnvironment)}
            SELECT dt.id FROM version_templates dt
            JOIN reachable r ON r.tenant_key = dt.tenant_key AND r.version_id = dt.version_id
            WHERE dt.tenant_key = :t ${if (search) "AND dt.name ILIKE :q" else ""}
            GROUP BY dt.id ORDER BY dt.id LIMIT :limit OFFSET :offset
            """,
        )
            .bindReachable(query.catalog, query.environmentKey)
            .apply { if (search) bind("q", "%${query.searchTerm}%") }
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo(String::class.java)
            .list()
        if (keys.isEmpty()) return@withHandle emptyList()

        // The releases each of them is in: a draft first, then by release number, highest first.
        val rows = handle.createQuery(
            """
            WITH ${reachableVersions(withEnvironment)}
            SELECT dt.id, dt.name, dt.description, r.version, r.version_id, r.state FROM version_templates dt
            JOIN reachable r ON r.tenant_key = dt.tenant_key AND r.version_id = dt.version_id
            WHERE dt.tenant_key = :t AND dt.id IN (<keys>)
            ORDER BY dt.id, (r.state = 'draft') DESC, ${releaseNumberOrder("r")}
            """,
        )
            .bindReachable(query.catalog, query.environmentKey)
            .bindList("keys", keys)
            .map { rs, _ ->
                Row(
                    key = rs.getString("id"),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    release = CatalogTemplateRelease(
                        version = if (rs.getString("state") == "draft") null else rs.getString("version"),
                        releaseId = rs.getObject("version_id", UUID::class.java),
                        draft = rs.getString("state") == "draft",
                    ),
                )
            }
            .list()

        rows.groupBy { it.key }.map { (key, ofTemplate) ->
            CatalogTemplate(
                key = key,
                name = ofTemplate.first().name,
                description = ofTemplate.first().description,
                releases = ofTemplate.map { it.release },
            )
        }
    }

    private data class Row(val key: String, val name: String, val description: String?, val release: CatalogTemplateRelease)
}

@Component
class CountCatalogTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountCatalogTemplates, Long> {
    override fun handle(query: CountCatalogTemplates): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.requireEnvironment(query.catalog, query.environmentKey)
        val search = !query.searchTerm.isNullOrBlank()
        handle.createQuery(
            """
            WITH ${reachableVersions(query.environmentKey != null)}
            SELECT COUNT(DISTINCT dt.id) FROM version_templates dt
            JOIN reachable r ON r.tenant_key = dt.tenant_key AND r.version_id = dt.version_id
            WHERE dt.tenant_key = :t ${if (search) "AND dt.name ILIKE :q" else ""}
            """,
        )
            .bindReachable(query.catalog, query.environmentKey)
            .apply { if (search) bind("q", "%${query.searchTerm}%") }
            .mapTo(Long::class.java)
            .one()
    }
}

private fun org.jdbi.v3.core.statement.Query.bindReachable(
    catalog: CatalogId,
    environmentKey: EnvironmentKey?,
): org.jdbi.v3.core.statement.Query = bind("t", catalog.tenantKey)
    .bindCatalog("c", catalog)
    .apply { if (environmentKey != null) bind("e", environmentKey) }
