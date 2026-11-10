// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.catalog.dependencies.VisibleVersions
import app.epistola.suite.catalog.dependencies.VisibleVersions.bindVisible
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.fonts.FONT_COLUMNS
import app.epistola.suite.fonts.FontStore
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.fonts.model.FontFace
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The fonts one version of [catalog] can use ([VisibleVersions]), with their faces: its own
 * catalog's, then those of each catalog it declares, at the release or draft the declaration names;
 * within a catalog by name. What the editor's font picker offers, in one read.
 */
data class ListVisibleFonts(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<List<VisibleFont>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = catalog.tenantKey
}

/**
 * A font a version can use, its [faces] by italic, then weight, and the [version] of its catalog it
 * was read at: where its faces are served from (null for its catalog's next version).
 */
data class VisibleFont(val font: Font, val faces: List<FontFace>, val version: CatalogReleaseRef?)

@Component
class ListVisibleFontsHandler(
    private val jdbi: Jdbi,
    private val fonts: FontStore,
) : QueryHandler<ListVisibleFonts, List<VisibleFont>> {
    override fun handle(query: ListVisibleFonts): List<VisibleFont> = jdbi.withHandle<List<VisibleFont>, Exception> { handle ->
        val visible = VisibleVersions.of(handle, query.catalog, query)
        if (visible.isEmpty()) return@withHandle emptyList()
        val refs = visible.associate { it.versionId to it.ref }
        handle.createQuery(
            "SELECT $FONT_COLUMNS, f.version_id, f.variants::text AS variants FROM version_fonts f " +
                "WHERE f.tenant_key = :t AND ${VisibleVersions.predicate("f")} ORDER BY ${VisibleVersions.order("f")}, f.name ASC, f.slug",
        )
            .bind("t", query.tenantKey)
            .bindVisible(visible)
            .map { rs, ctx ->
                val font = ctx.findRowMapperFor(Font::class.java).orElseThrow().map(rs, ctx)
                VisibleFont(font, fonts.facesOf(rs.getString("variants")), refs[rs.getObject("version_id", UUID::class.java)])
            }
            .list()
    }
}
