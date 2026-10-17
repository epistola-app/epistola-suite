// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.catalog.inCurrentVersion
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
 * structured `fontFamily` style ref (`{ "key": ..., "catalogKey": ... }`, or `slug` in content
 * stored before 2.0).
 *
 * Mirrors `app.epistola.suite.assets.queries.FindAssetUsages`. A font is
 * considered in use when the structured ref appears — at any nesting depth —
 * in any of the JSONB style documents that can carry a `fontFamily`:
 *
 *  - `themes.document_styles` (top-level + nested) and
 *    `themes.block_style_presets` (per-preset styles),
 *  - `template_variants.template_model` (node-level
 *    `documentStylesOverride` / inline styles), each variant's working model.
 *
 * The recursive `jsonpath` (`$.**.fontFamily ? (...)`) finds a `fontFamily` at any depth, so we
 * don't have to enumerate every nesting path.
 */
data class FindFontUsages(
    val fontId: FontId,
) : Query<List<FontUsage>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = fontId.tenantKey
}

@Component
class FindFontUsagesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindFontUsages, List<FontUsage>> {

    // Match every fontFamily ref, at any depth, to this font. Wire v8 names the font by `key`;
    // content stored before 2.0 says `slug` until the upgrade rewrites it, and still uses the font.
    private val jsonPath =
        """$.**.fontFamily ? ((@.key == ${'$'}slug || @.slug == ${'$'}slug) && @.catalogKey == ${'$'}catalogKey)"""

    override fun handle(query: FindFontUsages): List<FontUsage> = jdbi.withHandle<List<FontUsage>, Exception> { handle ->
        val vars = """{"slug": "${query.fontId.key.value}", "catalogKey": "${query.fontId.catalogKey.value}"}"""

        val themeUsages = handle.createQuery(
            """
            SELECT t.name AS name
            FROM themes t
            WHERE t.tenant_key = :tenantKey
              AND ${inCurrentVersion("t")}
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
            FROM template_variants tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.version_id = tv.version_id
                                      AND dt.resource_id = tv.template_resource_id
            WHERE tv.tenant_key = :tenantKey
              AND ${inCurrentVersion("tv")}
              AND jsonb_path_exists(tv.template_model, CAST(:jsonPath AS jsonpath), CAST(:vars AS jsonb))
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
