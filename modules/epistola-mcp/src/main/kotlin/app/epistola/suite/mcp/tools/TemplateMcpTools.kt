// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mcp.dto.TemplateContentInfo
import app.epistola.suite.mcp.dto.TemplateInfo
import app.epistola.suite.mcp.dto.TemplateSummaryInfo
import app.epistola.suite.mcp.dto.VariantInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mcp.support.parseOptionalArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.templates.queries.ListTemplateSummaries
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class TemplateMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
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

    @McpTool(
        name = "create_template",
        description = "Create a template in an AUTHORED catalog. The template starts with a default variant " +
            "holding an empty draft, and an empty draft data contract. Fill the draft with " +
            "`update_template_draft`, describe its input data with `update_data_contract`, and add " +
            "further variants with `create_variant`. Optionally pick a theme up front. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createTemplate(
        @McpToolParam(description = "Catalog key to create the template in. Must be an AUTHORED catalog.")
        catalogId: String,
        @McpToolParam(description = "Key for the new template: lowercase letters, digits and hyphens, e.g. `invoice-letter`.")
        templateId: String,
        @McpToolParam(description = "Display name.")
        name: String,
        @McpToolParam(description = "Theme key to apply. Use `list_themes` to discover. Omit for no theme.", required = false)
        themeId: String?,
        @McpToolParam(description = "Catalog key of the theme. Defaults to `catalogId`.", required = false)
        themeCatalogId: String?,
    ): TemplateInfo {
        val id = templateId(catalogId, templateId)
        val created = CreateDocumentTemplate(id = id, name = name).execute()
        if (themeId.isNullOrBlank()) return TemplateInfo.from(created)
        val themed = UpdateDocumentTemplate(
            id = id,
            themeId = ThemeKey.of(themeId),
            themeCatalogKey = CatalogKey.of(themeCatalogId?.takeIf { it.isNotBlank() } ?: catalogId),
        ).execute() ?: error("Template '$templateId' disappeared while applying its theme")
        return TemplateInfo.from(themed)
    }

    @McpTool(
        name = "update_template",
        description = "Update a template's metadata: name, theme, PDF/A output. Omitted arguments are left " +
            "unchanged. Content lives in variant drafts; change it with `update_template_draft`. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateTemplate(
        @McpToolParam(description = "Catalog key the template belongs to.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "New display name.", required = false)
        name: String?,
        @McpToolParam(description = "Theme key to apply.", required = false)
        themeId: String?,
        @McpToolParam(description = "Catalog key of the theme. Defaults to `catalogId`.", required = false)
        themeCatalogId: String?,
        @McpToolParam(description = "True to remove the template's theme. Cannot be combined with `themeId`.", required = false)
        clearTheme: Boolean?,
        @McpToolParam(description = "Whether the template renders as PDF/A.", required = false)
        pdfaEnabled: Boolean?,
    ): TemplateInfo {
        if (clearTheme == true && !themeId.isNullOrBlank()) {
            throw ValidationException("clearTheme", "`clearTheme` cannot be combined with `themeId`")
        }
        val theme = themeId?.takeIf { it.isNotBlank() }
        return UpdateDocumentTemplate(
            id = templateId(catalogId, templateId),
            name = name?.takeIf { it.isNotBlank() },
            themeId = theme?.let { ThemeKey.of(it) },
            themeCatalogKey = theme?.let { CatalogKey.of(themeCatalogId?.takeIf { c -> c.isNotBlank() } ?: catalogId) },
            clearThemeId = clearTheme == true,
            pdfaEnabled = pdfaEnabled,
        ).execute()?.let { TemplateInfo.from(it) } ?: throw notFound("Template", catalogId, templateId)
    }

    @McpTool(
        name = "create_variant",
        description = "Add a variant to a template, e.g. a second language. The variant starts with an empty " +
            "draft; fill it with `update_template_draft`. Attribute keys must be defined (see " +
            "`list_attributes`); use the catalog-qualified form, e.g. `system.locale`, and values must satisfy " +
            "the definition. Give each variant a distinct attribute set: identical sets are accepted here but " +
            "make attribute-based variant selection fail as ambiguous at generation time. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createVariant(
        @McpToolParam(description = "Catalog key the template belongs to.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Key for the new variant: lowercase letters, digits and hyphens, e.g. `nl`.")
        variantId: String,
        @McpToolParam(description = "Display title.")
        title: String,
        @McpToolParam(description = "Optional description.", required = false)
        description: String?,
        @McpToolParam(
            description = "JSON object of attribute key to value, e.g. `{\"language\": \"nl\"}`. Omit for none.",
            required = false,
        )
        attributes: String?,
    ): VariantInfo = CreateVariant(
        id = variantId(catalogId, templateId, variantId),
        title = title,
        description = description?.takeIf { it.isNotBlank() },
        attributes = parseAttributes(attributes) ?: emptyMap(),
    ).execute()?.let { VariantInfo.from(it) } ?: throw notFound("Template", catalogId, templateId)

    @McpTool(
        name = "update_variant",
        description = "Update a variant's title and attributes. Omitted arguments are left unchanged; a given " +
            "`attributes` object replaces the whole attribute set. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateVariant(
        @McpToolParam(description = "Catalog key the template belongs to.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
        @McpToolParam(description = "New display title.", required = false)
        title: String?,
        @McpToolParam(
            description = "JSON object of attribute key to value. Replaces all attributes; `{}` clears them.",
            required = false,
        )
        attributes: String?,
    ): VariantInfo {
        val id = variantId(catalogId, templateId, variantId)
        val current = GetVariant(id).query() ?: throw notFound("Variant", catalogId, "$templateId/$variantId")
        return UpdateVariant(
            variantId = id,
            title = title?.takeIf { it.isNotBlank() } ?: current.title,
            attributes = parseAttributes(attributes) ?: current.attributes,
        ).execute()?.let { VariantInfo.from(it) } ?: throw notFound("Variant", catalogId, "$templateId/$variantId")
    }

    private fun parseAttributes(json: String?): Map<String, String>? = objectMapper
        .parseOptionalArgument("attributes", json, Map::class.java)
        ?.entries
        ?.associate { (key, value) ->
            if (value !is String) throw ValidationException("attributes", "Attribute '$key' must have a string value")
            key.toString() to value
        }

    private fun templateId(catalogId: String, templateId: String): TemplateId {
        val catalog = CatalogId(CatalogKey.of(catalogId), mcpTenantId())
        return TemplateId(TemplateKey.of(templateId), catalog)
    }

    private fun variantId(catalogId: String, templateId: String, variantId: String): VariantId {
        val parent = templateId(catalogId, templateId)
        return VariantId(VariantKey.of(variantId), parent)
    }
}
