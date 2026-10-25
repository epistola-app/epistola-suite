// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.FontStore
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Resolves a single font-face's binary bytes by an **exact** (weight, italic)
 * key. Used by the editor `@font-face` content route, which serves a specific
 * face the catalog actually ships (the search surface only emits URLs for
 * present faces). Render-time resolution uses [ResolveFontFace] instead, which
 * adds nearest-weight matching.
 *
 * The face's file is the font's binary, stored under its hash (ADR 0033). Returns null when the
 * family has no such face or its bytes are missing.
 */
data class GetFontVariantContent(
    val catalog: CatalogId,
    val slug: FontKey,
    val weight: Int,
    val italic: Boolean,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<ByteArray?>,
    RequiresPermission {
    val tenantId: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetFontVariantContentHandler(
    private val jdbi: Jdbi,
    private val fonts: FontStore,
) : QueryHandler<GetFontVariantContent, ByteArray?> {

    override fun handle(query: GetFontVariantContent): ByteArray? = jdbi.withHandle<ByteArray?, Exception> { handle ->
        val face = fonts.faces(handle, FontId(query.slug, query.catalog)).firstOrNull { it.weight == query.weight && it.italic == query.italic }
            ?: return@withHandle null
        fonts.bytes(handle, query.tenantId, face.contentHash)
    }
}
