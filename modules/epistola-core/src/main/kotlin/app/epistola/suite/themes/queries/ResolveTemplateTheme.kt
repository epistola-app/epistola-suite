// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.dependencies.DraftDependencyViews
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogId
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
    /** The template's catalog: its variant's theme is named by alias from it, and its draft's declarations say where another catalog's theme comes from. */
    val catalog: CatalogId,
    val templateDefaultThemeId: ThemeKey?,
    val catalogDefaultThemeId: ThemeKey?,
    val templateModel: TemplateDocument,
    /** The catalog of [templateDefaultThemeId]; null for [catalog]. */
    val templateThemeCatalog: CatalogId? = null,
    /** The catalog of [catalogDefaultThemeId]; null for [catalog]. */
    val catalogDefaultThemeCatalog: CatalogId? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<Theme?>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ResolveTemplateThemeHandler(
    private val resolver: ThemeStyleResolver,
    private val dependencyViews: DraftDependencyViews,
) : QueryHandler<ResolveTemplateTheme, Theme?> {
    override fun handle(query: ResolveTemplateTheme): Theme? = resolver.resolveTheme(
        templateCatalog = query.catalog,
        templateDefaultThemeId = query.templateDefaultThemeId,
        catalogDefaultThemeId = query.catalogDefaultThemeId,
        templateModel = query.templateModel,
        templateThemeCatalog = query.templateThemeCatalog,
        catalogDefaultThemeCatalog = query.catalogDefaultThemeCatalog,
        dependencies = dependencyViews.of(query.catalog),
    )
}
