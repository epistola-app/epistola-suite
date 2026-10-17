// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mcp.dto.VariantContentInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.parseArgument
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.TemplateDocument
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Editing a variant's working model; the catalog release is the only version (contract ADR 0002). */
@Component
class VariantContentMcpTools(
    private val objectMapper: ObjectMapper,
) {

    @McpTool(
        name = "update_variant_content",
        description = "Replace a template variant's working model with a template document. The document is " +
            "the node/slot graph `get_template_content` returns under `templateModel`: `{modelVersion, root, " +
            "nodes, slots, ...}`. Build it from the component types `list_component_types` describes — their " +
            "`examples` are fragments to copy. A `stencil` node names its stencil (`stencilKey`, and " +
            "`catalogKey` for another catalog's) and holds only the template's placeholder fills; the stencil " +
            "itself is used by reference. Check the result with `preview_document`. An edit makes the " +
            "variant's ready mark stale: mark it ready again with `mark_variant_ready` once reviewed. " +
            "The server normalises the document it stores; pass `returnContent: true` to get it back as " +
            "`templateModel`. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateVariantContent(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
        @McpToolParam(description = "The complete template document, as a JSON object string.")
        content: String,
        @McpToolParam(description = "Return the stored document as `templateModel`. Default false.", required = false)
        returnContent: Boolean? = null,
    ): VariantContentInfo {
        val variant = VariantId(
            VariantKey.of(variantId),
            TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId())),
        )
        val document = objectMapper.parseArgument("content", content, TemplateDocument::class.java)
        val stored = UpdateVariantContent(variantId = variant, templateModel = document).execute()
        return VariantContentInfo(variantId = variantId, templateModel = stored.takeIf { returnContent == true })
    }
}
