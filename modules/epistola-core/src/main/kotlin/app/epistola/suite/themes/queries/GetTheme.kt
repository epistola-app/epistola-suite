// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.ThemeId
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

data class GetTheme(
    val id: ThemeId,
    /** The catalog version this addresses: a draft or a release (ADR 0033); null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<Theme?>,
    RequiresPermission {
    override val permission get() = Permission.THEME_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetTheme, Theme?> {
    override fun handle(query: GetTheme): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT $THEME_COLUMNS
            FROM version_themes t
            WHERE t.id = :id
              AND t.tenant_key = :tenantId
              AND t.version_id = :v
            """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bindVersion("v", handle.versionOf(query.id.catalogId, query).versionId)
            .map(ThemeRows.of(objectMapper, query.id.catalogId))
            .findOne()
            .orElse(null)
    }
}
