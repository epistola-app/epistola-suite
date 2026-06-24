package app.epistola.suite.fonts.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.ilikeContains
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.fonts.model.FontVariantRow
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.mapper.Nested
import org.springframework.stereotype.Component

/** A font family plus its weight/italic faces, for list views that render every face. */
data class FontWithVariants(
    val font: Font,
    val variants: List<FontVariantRow>,
)

/**
 * Like [ListFonts], but also returns each family's faces in **two queries total** (the families,
 * then all their variants in one batched fetch) instead of one variant query per font. The font
 * management list and the editor font picker render every face, so the per-row [GetFontVariants]
 * fan-out was an unbounded N+1; this keeps it at 2. Mirrors the batch pattern in `ExportFonts`.
 *
 * Use [ListFonts] where only family metadata is needed (REST/MCP listing).
 */
data class ListFontsWithVariants(
    val tenantId: TenantId,
    val catalogKey: CatalogKey? = null,
    val searchTerm: String? = null,
) : Query<List<FontWithVariants>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

/** Batch-variant row: the grouping key (catalog + font) + the face mapped via @Nested. */
private data class FontVariantBatchRow(
    val catalogKey: CatalogKey,
    val fontSlug: FontKey,
    @Nested val variant: FontVariantRow,
)

@Component
class ListFontsWithVariantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListFontsWithVariants, List<FontWithVariants>> {
    override fun handle(query: ListFontsWithVariants): List<FontWithVariants> = jdbi.withHandle<List<FontWithVariants>, Exception> { handle ->
        val search = query.searchTerm?.trim()?.takeIf { it.isNotEmpty() }
        val fontSql = buildString {
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
            if (query.catalogKey != null) append(" AND f.catalog_key = :catalogKey")
            if (search != null) append(" AND (f.name ILIKE :search OR f.slug ILIKE :search)")
            append(" ORDER BY f.name ASC")
        }
        val fonts = handle.createQuery(fontSql).bind("tenantId", query.tenantId.key).apply {
            if (query.catalogKey != null) bind("catalogKey", query.catalogKey)
            if (search != null) bind("search", ilikeContains(search))
        }.mapTo<Font>().list()

        if (fonts.isEmpty()) return@withHandle emptyList()

        // One batched fetch for every face of the listed families. JDBI has no tuple-IN binder,
        // so over-fetch by the two single-column INs and narrow to the exact (catalog, slug) set
        // in Kotlin (the family count is small).
        val wantedKeys = fonts.map { it.catalogKey to it.slug }.toSet()
        val variantsByFont = handle.createQuery(
            """
            SELECT catalog_key, font_slug, weight, italic, source, asset_key, classpath_location
            FROM font_variants
            WHERE tenant_key = :tenantKey
              AND catalog_key IN (<catalogs>)
              AND font_slug IN (<slugs>)
            ORDER BY catalog_key, font_slug, italic, weight
            """,
        )
            .bind("tenantKey", query.tenantId.key)
            .bindList("catalogs", fonts.map { it.catalogKey }.distinct())
            .bindList("slugs", fonts.map { it.slug }.distinct())
            .mapTo<FontVariantBatchRow>()
            .list()
            .filter { (it.catalogKey to it.fontSlug) in wantedKeys }
            .groupBy({ it.catalogKey to it.fontSlug }, { it.variant })

        fonts.map { font -> FontWithVariants(font, variantsByFont[font.catalogKey to font.slug] ?: emptyList()) }
    }
}
