package app.epistola.generation.pdf

/**
 * Resolves font binary content for a referenced font family + variant.
 *
 * The generation module stays free of tenant/JDBI/catalog concepts — tenant and
 * catalog scoping is the caller's responsibility when constructing the resolver
 * (mirrors [AssetResolver]). A null return means "not found"; [FontCache] then
 * falls back to the built-in font.
 */
fun interface FontFamilyResolver {
    fun resolve(catalogKey: String?, slug: String, variant: FontVariant): ByteArray?
}

/** The four weight/style variants a font family ships. */
enum class FontVariant { REGULAR, BOLD, ITALIC, BOLD_ITALIC }

/**
 * A resolved reference to a font family, parsed from a style map's
 * `fontFamily` value. `catalogKey` null means "the catalog that owns the
 * referencing theme/template" — the same convention as code-list bindings.
 */
data class ResolvedFontRef(val catalogKey: String?, val slug: String)
