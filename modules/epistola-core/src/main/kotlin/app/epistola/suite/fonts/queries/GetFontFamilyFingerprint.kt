// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.resolveCatalogResourceAddress
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.FACES_OF_FAMILY_AT_ADDRESS
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
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
    val tenantId: TenantKey,
    val catalogKey: CatalogKey,
    val slug: FontKey,
) : Query<String?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetFontFamilyFingerprintHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFontFamilyFingerprint, String?> {

    private data class FaceHash(val weight: Int, val italic: Boolean, val contentHash: String?)

    override fun handle(query: GetFontFamilyFingerprint): String? {
        val faces = jdbi.withHandle<List<FaceHash>, Exception> { handle ->
            handle.loadFaceHashes(query.tenantId, query.catalogKey, query.slug).ifEmpty {
                // A published version pinned the fingerprint under the address the family had at
                // publish time. A relocated family leaves an alias there; following it keeps the
                // integrity check comparing the same faces instead of failing every render of that
                // version with "MISSING", and keeps a later publish pinning the font at all.
                handle.resolveCatalogResourceAddress(
                    query.tenantId,
                    ResourceAddress(CatalogResourceType.FONT, query.catalogKey.value, query.slug.value),
                )
                    ?.takeIf { it.resolvedViaAlias }
                    ?.let { handle.loadFaceHashes(query.tenantId, CatalogKey.of(it.canonical.catalogKey), FontKey.of(it.canonical.key)) }
                    ?: emptyList()
            }
        }

        if (faces.isEmpty()) return null

        val canonical = faces
            .sortedWith(compareBy({ it.weight }, { it.italic }))
            .joinToString("\n") { "${it.weight}|${it.italic}|${it.contentHash ?: "MISSING"}" }

        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun Handle.loadFaceHashes(tenantKey: TenantKey, catalogKey: CatalogKey, slug: FontKey): List<FaceHash> = createQuery(
        """
        SELECT faces.weight, faces.italic, faces.content_hash
        $FACES_OF_FAMILY_AT_ADDRESS
        """,
    )
        .bind("tenantKey", tenantKey)
        .bind("catalogKey", catalogKey)
        .bind("slug", slug)
        .map { rs, _ ->
            FaceHash(
                weight = rs.getInt("weight"),
                italic = rs.getBoolean("italic"),
                contentHash = rs.getString("content_hash"),
            )
        }
        .list()
}
