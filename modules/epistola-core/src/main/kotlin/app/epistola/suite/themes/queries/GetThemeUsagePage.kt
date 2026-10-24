// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

private const val DEFAULT_USAGE_PAGE_SIZE = 50
private const val MAX_USAGE_PAGE_SIZE = 100

enum class ThemeUsageSource {
    VARIANT_OVERRIDE,
    TEMPLATE_DEFAULT,
    CATALOG_DEFAULT,
}

data class ThemeUsage(
    val templateKey: TemplateKey,
    val templateCatalogKey: CatalogKey,
    val templateName: String,
    val variantKey: VariantKey,
    val variantTitle: String,
    val source: ThemeUsageSource,
    /** The namespace of the template's catalog: with [templateCatalogKey], its address (ADR 0034, section 9). */
    val templateCatalogNamespace: String = "",
) {
    /** The template's catalog's address, `namespace/key`. */
    val templateCatalogAddress: String get() = "$templateCatalogNamespace/${templateCatalogKey.value}"
}

data class ThemeUsagePage(
    val items: List<ThemeUsage>,
    val total: Int,
)

/**
 * Lists draft and published template versions whose current theme selection
 * cascade resolves to [themeId].
 *
 * Resolution mirrors [app.epistola.suite.themes.ThemeStyleResolver]:
 * version override, then template default, then the default theme of the template's
 * catalog version (contract ADR 0007). An unqualified
 * version override is only assigned to a theme when its key is unique within
 * the tenant; otherwise the reference itself is ambiguous.
 */
data class GetThemeUsagePage(
    val themeId: ThemeId,
    val limit: Int = DEFAULT_USAGE_PAGE_SIZE,
    val offset: Int = 0,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<ThemeUsagePage>,
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
                    template_catalog.catalog_key AS template_catalog_key,
                    template_catalog.namespace AS template_catalog_namespace,
                    template.name AS template_name,
                    variant.id AS variant_key,
                    variant.title AS variant_title,
                    CASE
                        WHEN COALESCE(variant.template_model -> 'themeRef' ->> 'type', 'inherit') = 'override'
                            THEN 'VARIANT_OVERRIDE'
                        WHEN template.theme_resource_id IS NOT NULL
                            THEN 'TEMPLATE_DEFAULT'
                        ELSE 'CATALOG_DEFAULT'
                    END AS usage_source,
                    CASE
                        WHEN COALESCE(variant.template_model -> 'themeRef' ->> 'type', 'inherit') = 'override'
                            THEN variant.template_model -> 'themeRef' ->> 'themeKey'
                        WHEN template.theme_resource_id IS NOT NULL
                            THEN template_theme.id::text
                        ELSE catalog_theme.id::text
                    END AS effective_theme_key,
                    -- The theme's catalog by id (ADR 0034, section 9): an override names it by alias,
                    -- resolved through the template's catalog's dependencies, or, undeclared, the
                    -- tenant's one catalog with that key.
                    CASE
                        WHEN COALESCE(variant.template_model -> 'themeRef' ->> 'type', 'inherit') = 'override'
                            THEN CASE
                                WHEN variant.template_model -> 'themeRef' ->> 'catalogKey' IS NULL
                                    THEN template_theme.catalog_id
                                WHEN variant.template_model -> 'themeRef' ->> 'catalogKey' = template_catalog.catalog_key
                                    THEN template.catalog_id
                                ELSE COALESCE(
                                    (SELECT epistola_catalog_id(dep.tenant_key, dep.namespace, dep.dependency_key)
                                     FROM catalog_dependencies dep
                                     WHERE dep.tenant_key = template.tenant_key
                                       AND dep.version_id = template.version_id
                                       AND dep.alias = variant.template_model -> 'themeRef' ->> 'catalogKey'),
                                    (SELECT MIN(sole.catalog_id::text)::uuid
                                     FROM catalogs sole
                                     WHERE sole.tenant_key = template.tenant_key
                                       AND sole.catalog_key = variant.template_model -> 'themeRef' ->> 'catalogKey'
                                     HAVING COUNT(*) = 1)
                                )
                            END
                        WHEN template.theme_resource_id IS NOT NULL
                            THEN template_theme.catalog_id
                        ELSE catalog_theme.catalog_id
                    END AS effective_theme_catalog_id
                FROM template_variants variant
                JOIN document_templates template
                  ON template.tenant_key = variant.tenant_key
                 AND template.version_id = variant.version_id
                 AND template.resource_id = variant.template_resource_id
                JOIN catalogs template_catalog
                  ON template_catalog.tenant_key = template.tenant_key
                 AND template_catalog.catalog_id = template.catalog_id
                JOIN catalog_versions template_version
                  ON template_version.tenant_key = template.tenant_key
                 AND template_version.version_id = template.version_id
                LEFT JOIN themes template_theme
                  ON template_theme.tenant_key = template.tenant_key
                 AND template_theme.resource_id = template.theme_resource_id
                 AND ${inCurrentVersion("template_theme")}
                LEFT JOIN themes catalog_theme
                  ON catalog_theme.tenant_key = template_version.tenant_key
                 AND catalog_theme.resource_id = template_version.default_theme_resource_id
                 AND ${inCurrentVersion("catalog_theme")}
                WHERE variant.tenant_key = :tenantKey
                  AND ${inCurrentVersion("variant")}
            ),
            matching_usage AS (
                SELECT *
                FROM resolved_usage usage
                WHERE usage.effective_theme_key = :themeKey
                  AND (
                      usage.effective_theme_catalog_id = ${catalogAt("tenantKey", "themeCatalog")}
                      OR (
                          usage.effective_theme_catalog_id IS NULL
                          AND (
                              SELECT COUNT(*)
                              FROM themes candidate
                              WHERE candidate.tenant_key = :tenantKey
                                AND candidate.id = :themeKey
                                AND ${inCurrentVersion("candidate")}
                          ) = 1
                      )
                  )
            )
            SELECT matching_usage.*, COUNT(*) OVER()::int AS total_count
            FROM matching_usage
            ORDER BY template_name, template_catalog_key, template_key,
                     variant_title, variant_key
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantKey", query.themeId.tenantKey)
            .bind("themeKey", query.themeId.key)
            .bindCatalog("themeCatalog", query.themeId.catalogId)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { rs, _ ->
                ThemeUsageRow(
                    usage = ThemeUsage(
                        templateKey = TemplateKey.of(rs.getString("template_key")),
                        templateCatalogKey = CatalogKey.of(rs.getString("template_catalog_key")),
                        templateCatalogNamespace = rs.getString("template_catalog_namespace"),
                        templateName = rs.getString("template_name"),
                        variantKey = VariantKey.of(rs.getString("variant_key")),
                        variantTitle = rs.getString("variant_title"),
                        source = ThemeUsageSource.valueOf(rs.getString("usage_source")),
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
