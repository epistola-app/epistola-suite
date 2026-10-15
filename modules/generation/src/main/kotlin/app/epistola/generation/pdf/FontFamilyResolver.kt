// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
    /**
     * The face's bytes, with their content hash when the resolver knows it (core stores one per
     * face): parsed fonts are cached by that hash, so a known hash spares hashing the bytes on every
     * render.
     */
    fun resolve(catalogKey: String?, slug: String, weight: Int, italic: Boolean): FontFaceBytes?
}

/**
 * A font face's binary and, when known, the lowercase hex SHA-256 of exactly these [bytes]. A wrong
 * [contentHash] would let one font's parsed program stand in for another's, so pass null rather than
 * a hash that might not match.
 */
class FontFaceBytes(
    val bytes: ByteArray,
    val contentHash: String?,
)
