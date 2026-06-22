package app.epistola.suite.fonts.queries

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.math.abs

/**
 * Resolves the binary bytes of the **best-matching face** of a font family
 * for a requested CSS face (numeric [weight] 1–1000 + [italic]).
 *
 * This is the render-time entry point used by `DbFontFamilyResolver` /
 * [app.epistola.generation.pdf.FontCache]. The generation layer asks for an
 * exact (weight, italic); nearest-weight matching lives here (core owns the
 * DB and knows which faces a family actually ships).
 *
 * ## Nearest-weight algorithm
 *
 * 1. Load all variant rows for the family. If none, return `null`.
 * 2. **Italic preference**: prefer rows whose `italic` matches the request.
 *    If the family ships no face with the requested italic, fall back to the
 *    other italic set (so a request for italic with only upright faces still
 *    resolves, and vice versa).
 * 3. **Nearest weight** within the chosen italic set: an exact weight match
 *    wins; otherwise the row with the minimal absolute weight distance; ties
 *    (equal distance) break toward the **heavier** weight.
 * 4. Fetch that row's bytes by source (CLASSPATH → classloader resource;
 *    ASSET → [GetAssetContent]). Returns `null` when the resolved face's
 *    binary is missing.
 */
data class ResolveFontFace(
    val tenantId: TenantKey,
    val catalogKey: CatalogKey,
    val slug: FontKey,
    val weight: Int,
    val italic: Boolean,
) : Query<ByteArray?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId
}

internal data class FaceRow(
    val weight: Int,
    val italic: Boolean,
    val source: FontVariantSource,
    val assetKey: AssetKey?,
    val classpathLocation: String?,
)

/**
 * Pure nearest-weight face selection (no DB). Extracted for unit-testability;
 * the handler delegates here so behaviour is unchanged.
 *
 * 1. **Italic preference**: prefer rows whose `italic` matches the request;
 *    if the family ships no face with the requested italic, fall back to the
 *    other italic set.
 * 2. **Nearest weight**: minimal absolute weight distance; ties (equal
 *    distance) break toward the **heavier** weight.
 *
 * @return the best-matching row, or `null` when [rows] is empty.
 */
internal fun pickBestFace(rows: List<FaceRow>, weight: Int, italic: Boolean): FaceRow? {
    if (rows.isEmpty()) return null

    // (a) Italic preference: same-italic set, else the other set.
    val sameItalic = rows.filter { it.italic == italic }
    val candidates = sameItalic.ifEmpty { rows }

    // (b) Nearest weight: minimal |distance|; tie → heavier weight.
    return candidates.minWith(
        compareBy<FaceRow> { abs(it.weight - weight) }
            .thenByDescending { it.weight },
    )
}

@Component
class ResolveFontFaceHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ResolveFontFace, ByteArray?> {

    override fun handle(query: ResolveFontFace): ByteArray? {
        val rows = jdbi.withHandle<List<FaceRow>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT weight, italic, source, asset_key, classpath_location
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
                    FaceRow(
                        weight = rs.getInt("weight"),
                        italic = rs.getBoolean("italic"),
                        source = FontVariantSource.valueOf(rs.getString("source")),
                        assetKey = rs.getObject("asset_key", UUID::class.java)?.let(::AssetKey),
                        classpathLocation = rs.getString("classpath_location"),
                    )
                }
                .list()
        }

        val best = pickBestFace(rows, query.weight, query.italic) ?: return null

        return when (best.source) {
            FontVariantSource.CLASSPATH -> best.classpathLocation?.let { location ->
                this::class.java.classLoader.getResourceAsStream(location)?.readBytes()
            }
            FontVariantSource.ASSET -> best.assetKey?.let { assetKey ->
                GetAssetContent(query.tenantId, assetKey).query()?.content
            }
        }
    }
}
