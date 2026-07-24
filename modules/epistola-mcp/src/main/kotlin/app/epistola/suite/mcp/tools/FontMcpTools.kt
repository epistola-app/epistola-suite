// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mcp.dto.FontInfo
import app.epistola.suite.mcp.dto.FontVariantInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Read-only discovery for font families. AI clients use this to learn which
 * `fontFamily` refs (`{ slug, catalogKey }`) a theme/template can pick from —
 * including the bundled `system` catalog fonts every tenant gets.
 *
 * Writes are intentionally out of scope: like image assets, font binaries are
 * managed through the UI / catalog exchange, never over MCP or REST.
 */
@Component
class FontMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_fonts",
        description = "List font families in the current tenant. Optionally narrow by " +
            "catalog (e.g. `system` for the bundled open-source families: inter, " +
            "roboto, lato, source-sans-3, source-serif-4, merriweather, lora, " +
            "jetbrains-mono). Each entry carries the family's faces as " +
            "{weight (1-1000), italic} pairs and a `readOnly` flag for " +
            "SUBSCRIBED-catalog (e.g. `system`) families.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listFonts(
        @McpToolParam(
            description = "Catalog slug to filter by. Omit to list across all catalogs.",
            required = false,
        )
        catalogId: String?,
    ): List<FontInfo> {
        val tenantId = TenantId(mcpTenantKey())
        val filter = catalogId?.takeIf { it.isNotBlank() }?.let { CatalogKey.of(it) }
        return mediator.query(ListFonts(tenantId = tenantId, catalogKey = filter))
            .map { font ->
                val variants = mediator.query(
                    GetFontVariants(
                        fontId = FontId(font.slug, CatalogId(font.catalogKey, tenantId)),
                    ),
                ).map { FontVariantInfo(it.weight, it.italic) }
                FontInfo.from(font, variants)
            }
    }
}
