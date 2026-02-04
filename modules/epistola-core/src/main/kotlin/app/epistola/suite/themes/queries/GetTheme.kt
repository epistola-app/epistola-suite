package app.epistola.suite.themes.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.themes.Theme
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetTheme(
    val tenantId: TenantId,
    val id: ThemeId,
) : Query<Theme?>

@Component
class GetThemeHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetTheme, Theme?> {
    override fun handle(query: GetTheme): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT * FROM themes WHERE id = :id AND tenant_id = :tenantId
            """,
        )
            .bind("id", query.id)
            .bind("tenantId", query.tenantId)
            .mapTo<Theme>()
            .findOne()
            .orElse(null)
    }
}
