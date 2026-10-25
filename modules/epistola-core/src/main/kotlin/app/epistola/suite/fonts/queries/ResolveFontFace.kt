// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.FontByteCache
import app.epistola.suite.fonts.FontStore
import app.epistola.suite.fonts.model.FontFace
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
 * 4. Fetch that face's file: the font's binary stored under its hash (ADR 0033).
 *    Returns `null` when its bytes are missing.
 *
 * The result carries the face's content hash, so the renderer's parsed-font cache need not hash
 * the bytes again.
 */
data class ResolveFontFace(
    val catalog: CatalogId,
    val slug: FontKey,
    val weight: Int,
    val italic: Boolean,
    /** The catalog version a render reads; null for each catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : app.epistola.suite.catalog.releases.ReleaseReadable,
    Query<ResolvedFontFace?>,
    RequiresPermission {
    val tenantId: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId
}

/**
 * A face's binary and the lowercase hex SHA-256 its font names it by (the face's `contentHash` in
 * the font's content, ADR 0033); null only when nothing recorded one.
 */
class ResolvedFontFace(
    val content: ByteArray,
    val contentHash: String?,
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
internal fun pickBestFace(rows: List<FontFace>, weight: Int, italic: Boolean): FontFace? = pickNearestFace(rows, { it.weight }, { it.italic }, weight, italic)

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
    private val fonts: FontStore,
    private val bytes: FontByteCache,
) : QueryHandler<ResolveFontFace, ResolvedFontFace?> {

    override fun handle(query: ResolveFontFace): ResolvedFontFace? = jdbi.withHandle<ResolvedFontFace?, Exception> { handle ->
        val best = pickBestFace(fonts.faces(handle, FontId(query.slug, query.catalog)), query.weight, query.italic) ?: return@withHandle null
        // Bytes are the same wherever a hash is.
        val content = bytes.getOrLoad("${query.tenantId.value}|${best.contentHash}") { fonts.bytes(handle, query.tenantId, best.contentHash) }
            ?: return@withHandle null
        ResolvedFontFace(content, best.contentHash)
    }
}
