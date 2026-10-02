// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.FACES_OF_FAMILY_AT_ADDRESS
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
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
internal fun pickBestFace(rows: List<FaceRow>, weight: Int, italic: Boolean): FaceRow? = pickNearestFace(rows, { it.weight }, { it.italic }, weight, italic)

/**
 * The selection rule behind [pickBestFace], for any face shape: a family stored in the database and
 * a family read back from a catalog release must pick the same face for the same request.
 */
fun <T> pickNearestFace(faces: List<T>, weightOf: (T) -> Int, italicOf: (T) -> Boolean, weight: Int, italic: Boolean): T? {
    if (faces.isEmpty()) return null

    // (a) Italic preference: same-italic set, else the other set.
    val sameItalic = faces.filter { italicOf(it) == italic }
    val candidates = sameItalic.ifEmpty { faces }

    // (b) Nearest weight: minimal |distance|; tie → heavier weight.
    return candidates.minWith(
        compareBy<T> { abs(weightOf(it) - weight) }
            .thenByDescending { weightOf(it) },
    )
}

@Component
class ResolveFontFaceHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ResolveFontFace, ByteArray?> {

    override fun handle(query: ResolveFontFace): ByteArray? {
        val rows = jdbi.withHandle<List<FaceRow>, Exception> { handle ->
            handle.loadFaces(query.tenantId, query.catalogKey, query.slug)
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

private fun Handle.loadFaces(tenantId: TenantKey, catalogKey: CatalogKey, slug: FontKey): List<FaceRow> = createQuery(
    """
    SELECT faces.weight, faces.italic, faces.source,
           binary_asset.id AS asset_key, faces.classpath_location
    $FACES_OF_FAMILY_AT_ADDRESS
    """,
)
    .bind("tenantKey", tenantId)
    .bind("catalogKey", catalogKey)
    .bind("slug", slug)
    .map { rs, _ ->
        FaceRow(
            weight = rs.getInt("weight"),
            italic = rs.getBoolean("italic"),
            source = FontVariantSource.valueOf(rs.getString("source")),
            assetKey = rs.getString("asset_key")?.let(::AssetKey),
            classpathLocation = rs.getString("classpath_location"),
        )
    }
    .list()
