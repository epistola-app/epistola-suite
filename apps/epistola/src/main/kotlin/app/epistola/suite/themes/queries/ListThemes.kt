package app.epistola.suite.themes.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.themes.Theme
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListThemes(
    val tenantId: TenantId,
    val searchTerm: String? = null,
) : Query<List<Theme>>

@Component
class ListThemesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListThemes, List<Theme>> {
    override fun handle(query: ListThemes): List<Theme> = jdbi.withHandle<List<Theme>, Exception> { handle ->
        val sql = buildString {
            append("SELECT * FROM themes WHERE tenant_id = :tenantId")
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND name ILIKE :searchTerm")
            }
            append(" ORDER BY last_modified DESC")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId)
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery
            .mapTo<Theme>()
            .list()
    }
}
