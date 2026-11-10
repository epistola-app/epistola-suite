// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.CatalogEditingProperties
import app.epistola.suite.catalog.dependencies.Reach
import app.epistola.suite.catalog.dependencies.VisibleVersions
import app.epistola.suite.catalog.dependencies.VisibleVersions.bindVisible
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.THEME_COLUMNS
import app.epistola.suite.themes.Theme
import app.epistola.suite.themes.ThemeRows
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The themes one version of [catalog] can use ([VisibleVersions]): its own catalog's, then those of
 * each catalog it declares, at the release or draft the declaration names; within a catalog last changed first. With
 * [reach] [Reach.UNDECLARED], those of the catalogs it could declare instead, each at the version
 * declaring it names: what a picker offers apart, to be declared before it is used.
 */
data class ListVisibleThemes(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
    /** The catalogs it can use, or those it could declare. */
    val reach: Reach = Reach.DECLARED,
) : ReleaseReadable,
    Query<List<Theme>>,
    RequiresPermission {
    override val permission get() = Permission.THEME_VIEW
    override val tenantKey get() = catalog.tenantKey
}

@Component
class ListVisibleThemesHandler(
    private val jdbi: Jdbi,
    private val editing: CatalogEditingProperties,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ListVisibleThemes, List<Theme>> {
    override fun handle(query: ListVisibleThemes): List<Theme> = jdbi.withHandle<List<Theme>, Exception> { handle ->
        val visible = VisibleVersions.reach(handle, query.catalog, query, query.reach, editing.enabled)
        if (visible.isEmpty()) return@withHandle emptyList()
        handle.createQuery(
            "SELECT $THEME_COLUMNS FROM version_themes t WHERE t.tenant_key = :t AND ${VisibleVersions.predicate("t")} " +
                "ORDER BY ${VisibleVersions.order("t")}, t.updated_at DESC, t.id",
        )
            .bind("t", query.tenantKey)
            .bindVisible(visible)
            .map(ThemeRows.ofTenant(handle, objectMapper, query.tenantKey))
            .list()
    }
}
