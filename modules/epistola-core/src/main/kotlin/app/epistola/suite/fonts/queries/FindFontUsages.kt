package app.epistola.suite.fonts.queries

import app.epistola.suite.common.ids.FontId
import app.epistola.suite.fonts.model.FontUsage
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds themes and template versions that reference a given font family via a
 * structured `fontFamily` style ref (`{ "slug": ..., "catalogKey": ... }`).
 *
 * Mirrors `app.epistola.suite.assets.queries.FindAssetUsages`. A font is
 * considered in use when the structured ref appears — at any nesting depth —
 * in any of the JSONB style documents that can carry a `fontFamily`:
 *
 *  - `themes.document_styles` (top-level + nested) and
 *    `themes.block_style_presets` (per-preset styles),
 *  - `template_versions.template_model` (node-level
 *    `documentStylesOverride` / inline styles), for `draft` or `published`
 *    versions only.
 *
 * The recursive `jsonpath` (`$.** ? (...)`) matches the migration's recursive
 * `fontFamily` rewrite, so we don't have to enumerate every nesting path.
 */
data class FindFontUsages(
    val fontId: FontId,
) : Query<List<FontUsage>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = fontId.tenantKey
}

@Component
class FindFontUsagesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindFontUsages, List<FontUsage>> {

    // Recursively match any object that *is* a fontFamily ref to this slug +
    // catalogKey. The migration nests the ref under a "fontFamily" key, so we
    // match the ref object itself anywhere in the document.
    private val jsonPath =
        """$.** ? (@.slug == ${'$'}slug && @.catalogKey == ${'$'}catalogKey)"""

    override fun handle(query: FindFontUsages): List<FontUsage> = jdbi.withHandle<List<FontUsage>, Exception> { handle ->
        val vars = """{"slug": "${query.fontId.key.value}", "catalogKey": "${query.fontId.catalogKey.value}"}"""

        val themeUsages = handle.createQuery(
            """
            SELECT t.name AS name
            FROM themes t
            WHERE t.tenant_key = :tenantKey
              AND (
                jsonb_path_exists(t.document_styles, CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
                OR jsonb_path_exists(COALESCE(t.block_style_presets, '{}'::jsonb), CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
              )
            ORDER BY t.name
            """,
        )
            .bind("tenantKey", query.fontId.tenantKey)
            .bind("jsonPath", jsonPath)
            .bind("vars", vars)
            .map { rs, _ -> FontUsage(kind = "theme", name = rs.getString("name")) }
            .list()

        val templateUsages = handle.createQuery(
            """
            SELECT DISTINCT dt.name AS name
            FROM template_versions ver
            JOIN template_variants tv ON tv.tenant_key = ver.tenant_key AND tv.template_key = ver.template_key AND tv.id = ver.variant_key
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.id = tv.template_key
            WHERE ver.tenant_key = :tenantKey
              AND ver.status IN ('draft', 'published')
              AND jsonb_path_exists(ver.template_model, CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
            ORDER BY dt.name
            """,
        )
            .bind("tenantKey", query.fontId.tenantKey)
            .bind("jsonPath", jsonPath)
            .bind("vars", vars)
            .map { rs, _ -> FontUsage(kind = "template", name = rs.getString("name")) }
            .list()

        themeUsages + templateUsages
    }
}
