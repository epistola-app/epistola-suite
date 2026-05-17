package app.epistola.generation.pdf

/**
 * Resolves font binary content for a referenced font family + the requested
 * CSS face (numeric `weight` 1–1000 + `italic`).
 *
 * The generation module stays free of tenant/JDBI/catalog concepts — tenant
 * and catalog scoping is the caller's responsibility when constructing the
 * resolver (mirrors [AssetResolver]). Generation asks for an *exact*
 * (weight, italic); nearest-weight matching is the RESOLVER's job (core owns
 * the DB and knows which faces a family actually ships). A null return means
 * "not found / family has no faces"; [FontCache] then falls back to the
 * built-in font.
 */
fun interface FontFamilyResolver {
    fun resolve(catalogKey: String?, slug: String, weight: Int, italic: Boolean): ByteArray?
}
