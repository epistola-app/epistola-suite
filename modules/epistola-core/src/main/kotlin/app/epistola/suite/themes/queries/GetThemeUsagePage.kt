// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.VersionStatus
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

private const val DEFAULT_USAGE_PAGE_SIZE = 50
private const val MAX_USAGE_PAGE_SIZE = 100

enum class ThemeUsageSource {
    VARIANT_OVERRIDE,
    TEMPLATE_DEFAULT,
    TENANT_DEFAULT,
}

data class ThemeUsage(
    val templateKey: TemplateKey,
    val templateCatalogKey: CatalogKey,
    val templateName: String,
    val variantKey: VariantKey,
    val variantTitle: String,
    val versionKey: VersionKey,
    val versionStatus: VersionStatus,
    val source: ThemeUsageSource,
    val frozenSnapshot: Boolean,
)

data class ThemeUsagePage(
    val items: List<ThemeUsage>,
    val total: Int,
)

/**
 * Lists draft and published template versions whose current theme selection
 * cascade resolves to [themeId].
 *
 * Resolution mirrors [app.epistola.suite.themes.ThemeStyleResolver]:
 * version override, then template default, then tenant default. An unqualified
 * version override is only assigned to a theme when its key is unique within
 * the tenant; otherwise the reference itself is ambiguous.
 */
data class GetThemeUsagePage(
    val themeId: ThemeId,
    val limit: Int = DEFAULT_USAGE_PAGE_SIZE,
    val offset: Int = 0,
) : Query<ThemeUsagePage>,
    RequiresPermission {
    override val permission: Permission get() = Permission.THEME_VIEW
    override val tenantKey: TenantKey get() = themeId.tenantKey
}

@Component
class GetThemeUsagePageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetThemeUsagePage, ThemeUsagePage> {
    override fun handle(query: GetThemeUsagePage): ThemeUsagePage = jdbi.withHandle<ThemeUsagePage, Exception> { handle ->
        val limit = query.limit.coerceIn(1, MAX_USAGE_PAGE_SIZE)
        val offset = query.offset.coerceAtLeast(0)

        val rows = handle.createQuery(
            """
            WITH resolved_usage AS (
                SELECT
                    template.id AS template_key,
                    template.catalog_key AS template_catalog_key,
                    template.name AS template_name,
                    variant.id AS variant_key,
                    variant.title AS variant_title,
                    version.id AS version_key,
                    version.status AS version_status,
                    version.rendering_defaults_version IS NOT NULL
                        AND version.resolved_theme IS NOT NULL AS frozen_snapshot,
                    CASE
                        WHEN COALESCE(version.template_model -> 'themeRef' ->> 'type', 'inherit') = 'override'
                            THEN 'VARIANT_OVERRIDE'
                        WHEN template.theme_key IS NOT NULL
                            THEN 'TEMPLATE_DEFAULT'
                        ELSE 'TENANT_DEFAULT'
                    END AS usage_source,
                    CASE
                        WHEN COALESCE(version.template_model -> 'themeRef' ->> 'type', 'inherit') = 'override'
                            THEN version.template_model -> 'themeRef' ->> 'themeId'
                        WHEN template.theme_key IS NOT NULL
                            THEN template.theme_key::text
                        ELSE tenant.default_theme_key::text
                    END AS effective_theme_key,
                    CASE
                        WHEN COALESCE(version.template_model -> 'themeRef' ->> 'type', 'inherit') = 'override'
                            THEN COALESCE(
                                version.template_model -> 'themeRef' ->> 'catalogKey',
                                template.theme_catalog_key::text
                            )
                        WHEN template.theme_key IS NOT NULL
                            THEN template.theme_catalog_key::text
                        ELSE tenant.default_theme_catalog_key::text
                    END AS effective_theme_catalog_key
                FROM template_versions version
                JOIN template_variants variant
                  ON variant.tenant_key = version.tenant_key
                 AND variant.catalog_key = version.catalog_key
                 AND variant.template_key = version.template_key
                 AND variant.id = version.variant_key
                JOIN document_templates template
                  ON template.tenant_key = variant.tenant_key
                 AND template.catalog_key = variant.catalog_key
                 AND template.id = variant.template_key
                JOIN tenants tenant ON tenant.id = version.tenant_key
                WHERE version.tenant_key = :tenantKey
                  AND version.status IN ('draft', 'published')
            ),
            matching_usage AS (
                SELECT *
                FROM resolved_usage usage
                WHERE usage.effective_theme_key = :themeKey
                  AND (
                      usage.effective_theme_catalog_key = :themeCatalogKey
                      OR (
                          usage.effective_theme_catalog_key IS NULL
                          AND (
                              SELECT COUNT(*)
                              FROM themes candidate
                              WHERE candidate.tenant_key = :tenantKey
                                AND candidate.id = :themeKey
                          ) = 1
                      )
                  )
            )
            SELECT matching_usage.*, COUNT(*) OVER()::int AS total_count
            FROM matching_usage
            ORDER BY template_name, template_catalog_key, template_key,
                     variant_title, variant_key, version_key DESC
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantKey", query.themeId.tenantKey)
            .bind("themeKey", query.themeId.key)
            .bind("themeCatalogKey", query.themeId.catalogKey)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { rs, _ ->
                ThemeUsageRow(
                    usage = ThemeUsage(
                        templateKey = TemplateKey.of(rs.getString("template_key")),
                        templateCatalogKey = CatalogKey.of(rs.getString("template_catalog_key")),
                        templateName = rs.getString("template_name"),
                        variantKey = VariantKey.of(rs.getString("variant_key")),
                        variantTitle = rs.getString("variant_title"),
                        versionKey = VersionKey.of(rs.getInt("version_key")),
                        versionStatus = VersionStatus.valueOf(rs.getString("version_status").uppercase()),
                        source = ThemeUsageSource.valueOf(rs.getString("usage_source")),
                        frozenSnapshot = rs.getBoolean("frozen_snapshot"),
                    ),
                    total = rs.getInt("total_count"),
                )
            }
            .list()

        ThemeUsagePage(
            items = rows.map { it.usage },
            total = rows.firstOrNull()?.total ?: 0,
        )
    }

    private data class ThemeUsageRow(
        val usage: ThemeUsage,
        val total: Int,
    )
}
