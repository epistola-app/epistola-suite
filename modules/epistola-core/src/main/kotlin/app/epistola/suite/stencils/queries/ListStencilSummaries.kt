package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilSummaryWithVersionInfo
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Lists stencils with version metadata (latest published + latest version) in a single query.
 * Avoids N+1 by joining stencil_versions with aggregation.
 */
data class ListStencilSummaries(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val tag: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<StencilSummaryWithVersionInfo>>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ListStencilSummariesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ListStencilSummaries, List<StencilSummaryWithVersionInfo>> {
    override fun handle(query: ListStencilSummaries): List<StencilSummaryWithVersionInfo> = jdbi.withHandle<List<StencilSummaryWithVersionInfo>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                    SELECT s.id, s.tenant_key, s.name, s.description, s.tags,
                           s.created_at, s.last_modified,
                           MAX(CASE WHEN v.status = 'published' THEN v.id END) AS latest_published_version,
                           MAX(v.id) AS latest_version
                    FROM stencils s
                    LEFT JOIN stencil_versions v ON v.tenant_key = s.tenant_key AND v.stencil_key = s.id
                    WHERE s.tenant_key = :tenantId
                    """,
            )
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND (s.name ILIKE :searchTerm OR s.description ILIKE :searchTerm)")
            }
            if (!query.tag.isNullOrBlank()) {
                append(" AND s.tags @> :tag::jsonb")
            }
            append(" GROUP BY s.id, s.tenant_key, s.name, s.description, s.tags, s.created_at, s.last_modified")
            append(" ORDER BY s.last_modified DESC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        if (!query.tag.isNullOrBlank()) {
            jdbiQuery.bind("tag", "[\"${query.tag}\"]")
        }
        jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ ->
                val tagsJson = rs.getString("tags")
                val tags: List<String> = if (tagsJson != null && tagsJson != "null" && tagsJson.isNotBlank()) {
                    try {
                        objectMapper.readValue(
                            tagsJson,
                            object : tools.jackson.core.type.TypeReference<List<String>>() {},
                        )
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                StencilSummaryWithVersionInfo(
                    id = StencilKey.of(rs.getString("id")),
                    tenantKey = TenantKey.of(rs.getString("tenant_key")),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    tags = tags,
                    latestPublishedVersion = rs.getObject("latest_published_version") as? Int,
                    latestVersion = rs.getObject("latest_version") as? Int,
                    createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java),
                    lastModified = rs.getObject("last_modified", java.time.OffsetDateTime::class.java),
                )
            }
            .list()
    }
}
