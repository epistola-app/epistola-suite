// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.catalog.protocol.FontResource
import app.epistola.generation.pdf.FontFaceBytes
import app.epistola.generation.pdf.FontFamilyResolver
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.fonts.queries.pickNearestFace
import app.epistola.suite.mediator.query

/**
 * Builds a tenant + owning-catalog scoped [FontFamilyResolver] for a single
 * render. Not a Spring bean — the resolver carries per-render scope, so it's
 * constructed inline at the render call sites exactly like `AssetResolver`.
 *
 * Resolution: the generation layer hands us the structured `fontFamily` ref
 * (`{ slug, catalogKey }`) plus the requested CSS face (numeric `weight` +
 * `italic`). We resolve `catalogKey` to the owning catalog when absent (same
 * convention as code-list / asset bindings), validate the slug, and dispatch
 * [ResolveFontFace] — which owns the nearest-weight matching against the
 * family's actual faces. A null return means "not found" — `FontCache` then
 * falls back to the built-in font.
 *
 * The mediator must be bound (`MediatorContext`) on the calling thread; all
 * render call sites already are.
 */
fun fontFamilyResolver(
    /** The catalog of the content the references are written in: they name other catalogs by alias from it (ADR 0034, section 9). */
    owning: CatalogId,
    /** The catalog version the render reads, for a render outside the message that targets it. */
    catalogVersion: CatalogReleaseRef? = null,
    /** Where a draft reads other catalogs' fonts: the release it pins, or the draft it names. */
    dependencies: DraftDependencyView,
): FontFamilyResolver = FontFamilyResolver { alias, slug, weight, italic ->
    val fontKey = FontKey.validateOrNull(slug) ?: return@FontFamilyResolver null
    val catalog = dependencies.resolve(owning, alias) ?: return@FontFamilyResolver null
    if (dependencies.releaseOf(catalog) != null) {
        val family = dependencies.released(catalog, "font", fontKey.value) as? FontResource ?: return@FontFamilyResolver null
        val face = pickNearestFace(family.variants, { it.weight }, { it.italic }, weight, italic) ?: return@FontFamilyResolver null
        return@FontFamilyResolver dependencies.releasedBinary(face.contentHash)?.let { FontFaceBytes(it, face.contentHash) }
    }
    val namedDraft = dependencies.draftOf(catalog)?.let { CatalogReleaseRef(catalog, DraftRef.Id(it.key)) }
    // Which face applies is decided in the version being rendered, every time; only the bytes are
    // cached, by content hash ([ResolveFontFace]), so two versions never share a face by address.
    ResolveFontFace(
        catalog = catalog,
        slug = fontKey,
        weight = weight,
        italic = italic,
        catalogVersion = namedDraft ?: catalogVersion?.takeIf { it.catalog == catalog },
    ).query()?.let { face -> FontFaceBytes(face.content, face.contentHash) }
}
