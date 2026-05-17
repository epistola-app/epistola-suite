package app.epistola.suite.fonts.queries

import app.epistola.suite.common.ids.FontId
import app.epistola.suite.fonts.model.FontVariantRow
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists the (up to four) variant rows of a font family, ordered by variant.
 */
data class GetFontVariants(
    val fontId: FontId,
) : Query<List<FontVariantRow>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = fontId.tenantKey
}

@Component
class GetFontVariantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFontVariants, List<FontVariantRow>> {
    override fun handle(query: GetFontVariants): List<FontVariantRow> = jdbi.withHandle<List<FontVariantRow>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT variant, source, asset_key, classpath_location
            FROM font_variants
            WHERE tenant_key = :tenantKey
              AND catalog_key = :catalogKey
              AND font_slug = :slug
            ORDER BY variant ASC
            """,
        )
            .bind("tenantKey", query.fontId.tenantKey)
            .bind("catalogKey", query.fontId.catalogKey)
            .bind("slug", query.fontId.key)
            .mapTo<FontVariantRow>()
            .list()
    }
}
