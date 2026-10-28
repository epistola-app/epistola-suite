// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.sha256Hex
import app.epistola.suite.fonts.FontStore
import app.epistola.suite.fonts.model.FontFace
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A stable digest over the whole *face set* of a font family — the value a
 * published template version pins so its render is deterministic-or-nothing.
 *
 * The digest covers every face's `(weight, italic, content_hash)`, sorted
 * deterministically by `(weight, italic)`, each rendered as
 * `"$weight|$italic|${content_hash ?: "MISSING"}"` and newline-joined, then
 * SHA-256 hex. Any face added, removed, or whose bytes changed (its
 * `content_hash` differs, or is still null → `"MISSING"`) flips the
 * fingerprint.
 *
 * Returns `null` when the family has no variant rows at all (nothing to pin /
 * verify — the snapshot simply won't pin an entry for it).
 */
data class GetFontFamilyFingerprint(
    val catalog: CatalogId,
    val slug: FontKey,
) : Query<String?>,
    RequiresPermission {
    val tenantId: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetFontFamilyFingerprintHandler(
    private val jdbi: Jdbi,
    private val fonts: FontStore,
) : QueryHandler<GetFontFamilyFingerprint, String?> {

    override fun handle(query: GetFontFamilyFingerprint): String? {
        val faces = jdbi.withHandle<List<FontFace>, Exception> { handle -> fonts.faces(handle, FontId(query.slug, query.catalog)) }
        if (faces.isEmpty()) return null

        val canonical = faces
            .sortedWith(compareBy({ it.weight }, { it.italic }))
            .joinToString("\n") { "${it.weight}|${it.italic}|${it.contentHash}" }

        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }
}
