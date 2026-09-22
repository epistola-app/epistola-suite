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
import app.epistola.suite.mcp.dto.TemplateContentInfo
import app.epistola.suite.mcp.dto.TemplateInfo
import app.epistola.suite.mcp.dto.TemplateSummaryInfo
import app.epistola.suite.mcp.dto.VariantInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.templates.queries.ListTemplateSummaries
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.ListVariants
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class TemplateMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_templates",
        description = "List templates in the current tenant. " +
            "Optionally filter by catalog (use `list_catalogs` to discover catalog IDs) " +
            "or by a name search term. Returns lightweight summaries; use `get_template` " +
            "for metadata and `get_template_content` for the editable structure.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listTemplates(
        @McpToolParam(
            description = "Catalog key to filter by. Omit to search across all catalogs in the tenant.",
            required = false,
        )
        catalogId: String?,
        @McpToolParam(
            description = "Case-insensitive substring match against the template name. Omit for no filter.",
            required = false,
        )
        search: String?,
    ): List<TemplateSummaryInfo> = mediator.query(
        ListTemplateSummaries(
            tenantId = mcpTenantId(),
            catalogKey = catalogId?.let { CatalogKey.of(it) },
            searchTerm = search,
        ),
    ).items.map { TemplateSummaryInfo.from(it) }

    @McpTool(
        name = "get_template",
        description = "Fetch metadata for a single template: name, theme reference, PDF/A flag, timestamps. " +
            "Does NOT include the editable template content — call `get_template_content` for that.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getTemplate(
        @McpToolParam(description = "Catalog key the template belongs to.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
    ): TemplateInfo? {
        val id = templateId(catalogId, templateId)
        return mediator.query(GetDocumentTemplate(id))?.let { TemplateInfo.from(it) }
    }

    @McpTool(
        name = "list_variants",
        description = "List variants for a template. A variant is a parallel rendition of the template " +
            "for a specific audience (language, brand, etc.). Each variant has its own version history " +
            "and one default variant is selected when no variant is specified at preview time.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listVariants(
        @McpToolParam(description = "Catalog key the template belongs to.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
    ): List<VariantInfo> = mediator.query(ListVariants(templateId(catalogId, templateId)))
        .map { VariantInfo.from(it) }

    @McpTool(
        name = "get_variant",
        description = "Fetch a single variant's metadata.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getVariant(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
    ): VariantInfo? = mediator
        .query(GetVariant(variantId(catalogId, templateId, variantId)))
        ?.let { VariantInfo.from(it) }

    @McpTool(
        name = "get_template_content",
        description = "Fetch the full editor context for a template variant: the template node/slot graph " +
            "(the editable document structure), the data contract's JSON Schema (`dataModel`), and " +
            "named sample datasets (`dataExamples`) that can drive a preview. " +
            "Use this when the user asks about a template's structure or wants to inspect what a template renders.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getTemplateContent(
        @McpToolParam(description = "Catalog key the template belongs to.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key. Use `list_variants` to discover.")
        variantId: String,
    ): TemplateContentInfo? = mediator
        .query(GetEditorContext(variantId(catalogId, templateId, variantId)))
        ?.let { TemplateContentInfo.from(it) }

    private fun templateId(catalogId: String, templateId: String): TemplateId {
        val catalog = CatalogId(CatalogKey.of(catalogId), mcpTenantId())
        return TemplateId(TemplateKey.of(templateId), catalog)
    }

    private fun variantId(catalogId: String, templateId: String, variantId: String): VariantId {
        val parent = templateId(catalogId, templateId)
        return VariantId(VariantKey.of(variantId), parent)
    }
}
