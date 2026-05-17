package app.epistola.suite.mcp.dto

import app.epistola.suite.fonts.model.Font

/**
 * Lightweight font-family record for MCP discovery. Mirrors [ThemeSummaryInfo]:
 * just the metadata an assistant needs to pick a `fontFamily` ref
 * (`{ slug, catalogKey }`) for a theme/template — not the binary faces.
 *
 * `readOnly` is true for SUBSCRIBED-catalog families (e.g. the bundled
 * `system` fonts), consistent with the code-list/attribute MCP DTOs.
 */
data class FontInfo(
    val slug: String,
    val catalog: String,
    val name: String,
    val kind: String,
    val variants: List<String>,
    val catalogType: String,
    val readOnly: Boolean,
) {
    companion object {
        fun from(font: Font, variants: List<String>): FontInfo {
            val subscribed =
                font.catalogType == app.epistola.suite.catalog.CatalogType.SUBSCRIBED
            return FontInfo(
                slug = font.slug.value,
                catalog = font.catalogKey.value,
                name = font.name,
                kind = font.kind.wire,
                variants = variants,
                catalogType = if (subscribed) "SUBSCRIBED" else "AUTHORED",
                readOnly = subscribed,
            )
        }
    }
}
