package app.epistola.suite.fonts.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.sha256Hex
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
            handle.createQuery(
                """
                SELECT weight, italic, content_hash
                FROM font_variants
                WHERE tenant_key = :tenantKey
                  AND catalog_key = :catalogKey
                  AND font_slug = :slug
                """,
            )
                .bind("tenantKey", query.tenantId)
                .bind("catalogKey", query.catalogKey)
                .bind("slug", query.slug)
                .map { rs, _ ->
                    FaceHash(
                        weight = rs.getInt("weight"),
                        italic = rs.getBoolean("italic"),
                        contentHash = rs.getString("content_hash"),
                    )
                }
                .list()
        }

        if (faces.isEmpty()) return null

        val canonical = faces
            .sortedWith(compareBy({ it.weight }, { it.italic }))
            .joinToString("\n") { "${it.weight}|${it.italic}|${it.contentHash ?: "MISSING"}" }

        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }
}
