package app.epistola.suite.fonts

import app.epistola.generation.pdf.FontFamilyResolver
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.ResolveFontFace
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
    tenantKey: TenantKey,
    owningCatalogKey: CatalogKey,
): FontFamilyResolver = FontFamilyResolver { catalogKey, slug, weight, italic ->
    val fontKey = FontKey.validateOrNull(slug) ?: return@FontFamilyResolver null
    val effectiveCatalog = catalogKey?.let(CatalogKey::of) ?: owningCatalogKey
    ResolveFontFace(
        tenantId = tenantKey,
        catalogKey = effectiveCatalog,
        slug = fontKey,
        weight = weight,
        italic = italic,
    ).query()
}
