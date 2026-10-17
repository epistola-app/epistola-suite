// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.Theme
import app.epistola.suite.themes.ThemeStyleResolver
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * The unmerged theme a template variant renders with, by the cascade generation uses (variant
 * override, template default, tenant default): [ThemeStyleResolver.resolveTheme] as a query, so the
 * editor resolves it in the catalog version it edits. Null when no theme applies.
 */
data class ResolveTemplateTheme(
    override val tenantKey: TenantKey,
    val templateDefaultThemeId: ThemeKey?,
    val tenantDefaultThemeId: ThemeKey?,
    val templateModel: TemplateDocument,
    val templateCatalogKey: CatalogKey? = null,
    val tenantDefaultThemeCatalogKey: CatalogKey? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<Theme?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ResolveTemplateThemeHandler(
    private val resolver: ThemeStyleResolver,
) : QueryHandler<ResolveTemplateTheme, Theme?> {
    override fun handle(query: ResolveTemplateTheme): Theme? = resolver.resolveTheme(
        tenantId = query.tenantKey,
        templateDefaultThemeId = query.templateDefaultThemeId,
        tenantDefaultThemeId = query.tenantDefaultThemeId,
        templateModel = query.templateModel,
        templateCatalogKey = query.templateCatalogKey,
        tenantDefaultThemeCatalogKey = query.tenantDefaultThemeCatalogKey,
    )
}
