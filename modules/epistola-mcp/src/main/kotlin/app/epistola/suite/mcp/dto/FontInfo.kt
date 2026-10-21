// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.fonts.model.Font

/**
 * Lightweight font-family record for MCP discovery. Mirrors [ThemeSummaryInfo]:
 * just the metadata an assistant needs to pick a `fontFamily` ref
 * (`{ slug, catalogKey }`) for a theme/template — not the binary faces.
 *
 * `editable` says whether its catalog can be changed here, `readOnly` the
 * opposite (e.g. the bundled `system` fonts), consistent with the code-list/attribute MCP DTOs.
 */
data class FontVariantInfo(
    val weight: Int,
    val italic: Boolean,
)

data class FontInfo(
    val slug: String,
    val catalog: String,
    val name: String,
    val kind: String,
    val variants: List<FontVariantInfo>,
    val editable: Boolean,
    val readOnly: Boolean,
) {
    companion object {
        fun from(font: Font, variants: List<FontVariantInfo>, editable: Boolean): FontInfo = FontInfo(
            slug = font.slug.value,
            catalog = font.catalogKey.value,
            name = font.name,
            kind = font.kind.wire,
            variants = variants,
            editable = editable,
            readOnly = !editable,
        )
    }
}
