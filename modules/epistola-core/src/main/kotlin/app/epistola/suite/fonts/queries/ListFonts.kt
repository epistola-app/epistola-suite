package app.epistola.suite.fonts.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.ilikeContains
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists font families owned by a tenant, optionally filtered to a single
 * catalog and/or a free-text [searchTerm] (matched against the family name and
 * slug, case-insensitively).
 *
 * Each row carries the owning catalog's `type` so the UI can gate edit
 * affordances (AUTHORED is editable, SUBSCRIBED is read-only). Variants are
 * not fetched here; use `GetFontVariants` for that.
 */
data class ListFonts(
    val tenantId: TenantId,
    val catalogKey: CatalogKey? = null,
    val searchTerm: String? = null,
) : Query<List<Font>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListFontsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListFonts, List<Font>> {
    override fun handle(query: ListFonts): List<Font> = jdbi.withHandle<List<Font>, Exception> { handle ->
        val search = query.searchTerm?.trim()?.takeIf { it.isNotEmpty() }
        val sql = buildString {
            append(
                """
                SELECT f.slug, f.tenant_key, f.catalog_key, f.name, f.kind,
                       f.created_at, f.updated_at,
                       c.type AS catalog_type
                FROM fonts f
                JOIN catalogs c ON c.tenant_key = f.tenant_key AND c.id = f.catalog_key
                WHERE f.tenant_key = :tenantId
                """,
            )
            if (query.catalogKey != null) {
                append(" AND f.catalog_key = :catalogKey")
            }
            if (search != null) {
                append(" AND (f.name ILIKE :search OR f.slug ILIKE :search)")
            }
            append(" ORDER BY f.name ASC")
        }

        val q = handle.createQuery(sql).bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            q.bind("catalogKey", query.catalogKey)
        }
        if (search != null) {
            q.bind("search", ilikeContains(search))
        }
        q.mapTo<Font>().list()
    }
}
