package app.epistola.suite.themes.queries

import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.Theme
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetTheme(
    val id: ThemeId,
) : Query<Theme?>,
    RequiresPermission {
    override val permission get() = Permission.THEME_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetThemeHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetTheme, Theme?> {
    override fun handle(query: GetTheme): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT * FROM themes WHERE id = :id AND tenant_key = :tenantId
            """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .mapTo<Theme>()
            .findOne()
            .orElse(null)
    }
}
