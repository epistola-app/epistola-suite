// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.fonts.FontStore
import app.epistola.suite.fonts.model.FontFace
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Lists the variant rows (font faces) of a font family, ordered by
 * (italic, weight).
 */
data class GetFontVariants(
    val fontId: FontId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<FontFace>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = fontId.tenantKey
}

@Component
class GetFontVariantsHandler(
    private val jdbi: Jdbi,
    private val fonts: FontStore,
) : QueryHandler<GetFontVariants, List<FontFace>> {
    override fun handle(query: GetFontVariants): List<FontFace> = jdbi.withHandle<List<FontFace>, Exception> { handle -> fonts.faces(handle, query.fontId) }
}
