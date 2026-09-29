// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.generation.pdf.FontBytesValidator
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mcp.dto.FontInfo
import app.epistola.suite.mcp.dto.FontVariantInfo
import app.epistola.suite.mcp.support.decodeBase64Argument
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mcp.support.parseArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode

/**
 * Font families: discovery of the `fontFamily` refs (`{ slug, catalogKey }`) a theme or template
 * can pick from — including the bundled `system` catalog fonts every tenant gets — and upload of a
 * family, so an assistant converting a document can bring its typeface along.
 *
 * `upload_font` does what the UI's font dialog does: every face is checked for embeddability
 * before anything is stored, then each face becomes an asset and the family is upserted over them.
 */
@Component
class FontMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
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

    @McpTool(
        name = "upload_font",
        description = "Create or replace a font family in an AUTHORED catalog from TTF/OTF files, one per face " +
            "(weight + italic). Uploading an existing family replaces all of its faces. Every face must be an " +
            "embeddable TTF or OTF; WOFF/WOFF2 and embedding-restricted fonts are rejected. Use the family in " +
            "styles as `fontFamily: {\"slug\": \"<fontId>\", \"catalogKey\": \"<catalogId>\"}`. " +
            "Requires the TEMPLATE_EDIT and REFERENCE_EDIT permissions.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun uploadFont(
        @McpToolParam(description = "Catalog key to upload into. Must be an AUTHORED catalog.")
        catalogId: String,
        @McpToolParam(description = "Family slug: starts with a letter, then lowercase letters, digits, hyphens, e.g. `acme-sans`.")
        fontId: String,
        @McpToolParam(description = "Display name, e.g. `Acme Sans`.")
        name: String,
        @McpToolParam(description = "One of `sans`, `serif`, `mono`, `condensed`, `display`.")
        kind: String,
        @McpToolParam(
            description = "JSON array of faces, each `{\"weight\": 400, \"italic\": false, \"content\": \"<base64>\"}`. " +
                "Weight is 1-1000; each weight/italic pair at most once.",
        )
        faces: String,
    ): FontInfo {
        val tenantId = TenantId(mcpTenantKey())
        val catalogKey = CatalogKey.of(catalogId)
        val slug = FontKey.of(fontId)
        val fontKind = try {
            FontKind.fromWire(kind)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("kind", "${e.message}. Use one of ${FontKind.entries.joinToString { it.wire }}")
        }
        // Every face is validated before any is stored, so a bad face leaves no orphaned asset behind.
        val parsed = parseFaces(objectMapper.parseArgument("faces", faces, ArrayNode::class.java))
        val variants = parsed.map { face ->
            val asset = UploadAsset(
                tenantId = tenantId.key,
                name = "${slug.value}-${face.weight}${if (face.italic) "-italic" else ""}.${face.extension}",
                mediaType = face.mediaType,
                content = face.bytes,
                width = null,
                height = null,
                catalogKey = catalogKey,
            ).execute()
            ImportFontVariant(weight = face.weight, italic = face.italic, source = FontVariantSource.ASSET, assetKey = asset.id)
        }
        ImportFont(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = slug.value,
            name = name,
            kind = fontKind.wire,
            variants = variants,
        ).execute()
        val font = ListFonts(tenantId = tenantId, catalogKey = catalogKey).query().first { it.slug == slug }
        val stored = GetFontVariants(fontId = FontId(slug, CatalogId(catalogKey, tenantId))).query()
            .map { FontVariantInfo(it.weight, it.italic) }
        return FontInfo.from(font, stored)
    }

    private class Face(val weight: Int, val italic: Boolean, val bytes: ByteArray, val mediaType: AssetMediaType) {
        val extension get() = if (mediaType == AssetMediaType.OTF) "otf" else "ttf"
    }

    private fun parseFaces(array: ArrayNode): List<Face> {
        if (array.isEmpty) throw ValidationException("faces", "At least one face is required")
        val faces = array.values().mapIndexed { index, node ->
            val label = "Face ${index + 1}"
            val weight = node.get("weight")?.takeIf { it.isIntegralNumber }?.asInt()
                ?: throw ValidationException("faces", "$label needs a numeric `weight` (1-1000)")
            if (weight !in 1..1000) throw ValidationException("faces", "$label: weight must be between 1 and 1000")
            val italic = node.get("italic")?.asBoolean() ?: false
            val content = node.get("content")?.asString()
                ?: throw ValidationException("faces", "$label needs `content` (base64 TTF or OTF)")
            val bytes = decodeBase64Argument("faces", content).bytes
            FontBytesValidator.rejectionReason(bytes)?.let { throw ValidationException("faces", "$label: $it") }
            Face(weight, italic, bytes, sfntMediaType(bytes))
        }
        if (faces.map { it.weight to it.italic }.toSet().size != faces.size) {
            throw ValidationException("faces", "Each weight/italic face may appear only once")
        }
        return faces
    }

    /** CFF-flavoured OpenType starts with `OTTO`; everything else that passed validation is TrueType. */
    private fun sfntMediaType(bytes: ByteArray): AssetMediaType = if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "OTTO") {
        AssetMediaType.OTF
    } else {
        AssetMediaType.TTF
    }
}
