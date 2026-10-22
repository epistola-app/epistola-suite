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
import app.epistola.suite.mcp.support.CATALOG_VERSION_DESCRIPTION
import app.epistola.suite.mcp.support.EXPECTED_REVISION_DESCRIPTION
import app.epistola.suite.mcp.support.catalogFilter
import app.epistola.suite.mcp.support.catalogVersionRef
import app.epistola.suite.mcp.support.mcpCatalogKey
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
import app.epistola.suite.templates.commands.variants.validateAttributes
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
            "or by a name search term. Returns lightweight summaries, each with the template's description; use `get_template` " +
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<TemplateSummaryInfo> = mediator.query(
        ListTemplateSummaries(
            tenantId = mcpTenantId(),
            catalogKey = catalogFilter(catalogId),
            searchTerm = search,
            catalogVersion = catalogVersionRef(catalogId, catalogVersion),
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): TemplateInfo? {
        val id = templateId(catalogId, templateId)
        return mediator.query(GetDocumentTemplate(id, catalogVersion = catalogVersionRef(catalogId, catalogVersion)))?.let { TemplateInfo.from(it) }
    }

    @McpTool(
        name = "list_variants",
        description = "List variants for a template. A variant is a parallel rendition of the template " +
            "for a specific audience (language, brand, etc.). Each variant has one working model, " +
            "versioned with its catalog, and one default variant is selected when no variant is specified at preview time.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listVariants(
        @McpToolParam(description = "Catalog key the template belongs to.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<VariantInfo> = mediator.query(ListVariants(templateId(catalogId, templateId), catalogVersion = catalogVersionRef(catalogId, catalogVersion)))
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): VariantInfo? = mediator
        .query(GetVariant(variantId(catalogId, templateId, variantId), catalogVersion = catalogVersionRef(catalogId, catalogVersion)))
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): TemplateContentInfo? = mediator
        .query(GetEditorContext(variantId(catalogId, templateId, variantId), catalogVersion = catalogVersionRef(catalogId, catalogVersion)))
        ?.let { TemplateContentInfo.from(it) }

    @McpTool(
        name = "create_template",
        description = "Create a template in a catalog that can be changed here. The template starts with a default variant " +
            "holding an empty document, and an empty data contract. Fill the document with " +
            "`update_variant_content`, describe its input data with `update_data_contract`, and add " +
            "further variants with `create_variant`. Optionally pick a theme and the default variant's " +
            "attributes up front — set `system.locale` there, since `\$formatDate` and " +
            "`\$formatLocaleNumber` follow it. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createTemplate(
        @McpToolParam(description = "Catalog key to create the template in. Must be a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "Key for the new template: lowercase letters, digits and hyphens, e.g. `invoice-letter`.")
        templateId: String,
        @McpToolParam(description = "Display name.")
        name: String,
        @McpToolParam(description = "Theme key to apply. Use `list_themes` to discover. Omit for no theme.", required = false)
        themeId: String?,
        @McpToolParam(description = "Catalog key of the theme. Defaults to `catalogId`.", required = false)
        themeCatalogId: String?,
        @McpToolParam(
            description = "JSON object of attributes for the default variant, e.g. " +
                "`{\"system.locale\": \"nl-NL\"}`. Omit for none.",
            required = false,
        )
        variantAttributes: String?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
        @McpToolParam(description = TEMPLATE_DESCRIPTION_DESCRIPTION, required = false)
        description: String? = null,
    ): TemplateInfo {
        val id = templateId(catalogId, templateId)
        val version = catalogVersionRef(catalogId, catalogVersion)
        // Validated before anything is created, so bad attributes leave no half-made template behind.
        val attributes = parseAttributes(variantAttributes, field = "variantAttributes")
            ?.also { validateAttributes(mcpTenantId(), it) }
        var template = CreateDocumentTemplate(id = id, name = name, catalogVersion = version, description = description).execute()
        if (!themeId.isNullOrBlank()) {
            template = UpdateDocumentTemplate(
                id = id,
                themeId = ThemeKey.of(themeId),
                themeCatalogKey = mcpCatalogKey(themeCatalogId?.takeIf { it.isNotBlank() } ?: catalogId),
                catalogVersion = version,
            ).execute() ?: error("Template '$templateId' disappeared while applying its theme")
        }
        if (!attributes.isNullOrEmpty()) {
            val defaultVariant = ListVariants(id, catalogVersion = version).query().single { it.isDefault }
            UpdateVariant(
                variantId = VariantId(defaultVariant.id, id),
                title = defaultVariant.title,
                attributes = attributes,
                catalogVersion = version,
            ).execute()
        }
        return TemplateInfo.from(template)
    }

    @McpTool(
        name = "update_template",
        description = "Update a template's metadata: name, description, theme, PDF/A output. Omitted arguments are left " +
            "unchanged. Content lives in the variants; change it with `update_variant_content`. " +
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
        @McpToolParam(description = EXPECTED_REVISION_DESCRIPTION, required = false)
        expectedRevision: Long? = null,
        @McpToolParam(description = TEMPLATE_DESCRIPTION_DESCRIPTION + " An empty string removes it.", required = false)
        description: String? = null,
    ): TemplateInfo {
        if (clearTheme == true && !themeId.isNullOrBlank()) {
            throw ValidationException("clearTheme", "`clearTheme` cannot be combined with `themeId`")
        }
        val theme = themeId?.takeIf { it.isNotBlank() }
        return UpdateDocumentTemplate(
            id = templateId(catalogId, templateId),
            name = name?.takeIf { it.isNotBlank() },
            themeId = theme?.let { ThemeKey.of(it) },
            themeCatalogKey = theme?.let { mcpCatalogKey(themeCatalogId?.takeIf { c -> c.isNotBlank() } ?: catalogId) },
            clearThemeId = clearTheme == true,
            pdfaEnabled = pdfaEnabled,
            catalogVersion = catalogVersionRef(catalogId, catalogVersion),
            expectedRevision = expectedRevision,
            description = description,
        ).execute()?.let { TemplateInfo.from(it) } ?: throw notFound("Template", catalogId, templateId)
    }

    @McpTool(
        name = "create_variant",
        description = "Add a variant to a template, e.g. a second language. The variant starts with an empty " +
            "document; fill it with `update_variant_content`. Attribute keys must be defined (see " +
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): VariantInfo = CreateVariant(
        id = variantId(catalogId, templateId, variantId),
        title = title,
        description = description?.takeIf { it.isNotBlank() },
        attributes = parseAttributes(attributes) ?: emptyMap(),
        catalogVersion = catalogVersionRef(catalogId, catalogVersion),
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
        @McpToolParam(description = EXPECTED_REVISION_DESCRIPTION, required = false)
        expectedRevision: Long? = null,
    ): VariantInfo {
        val id = variantId(catalogId, templateId, variantId)
        val version = catalogVersionRef(catalogId, catalogVersion)
        val current = GetVariant(id, catalogVersion = version).query() ?: throw notFound("Variant", catalogId, "$templateId/$variantId")
        return UpdateVariant(
            variantId = id,
            title = title?.takeIf { it.isNotBlank() } ?: current.title,
            attributes = parseAttributes(attributes) ?: current.attributes,
            catalogVersion = version,
            expectedRevision = expectedRevision,
        ).execute()?.let { VariantInfo.from(it) } ?: throw notFound("Variant", catalogId, "$templateId/$variantId")
    }

    private fun parseAttributes(json: String?, field: String = "attributes"): Map<String, String>? = objectMapper
        .parseOptionalArgument(field, json, Map::class.java)
        ?.entries
        ?.associate { (key, value) ->
            if (value !is String) throw ValidationException(field, "Attribute '$key' must have a string value")
            key.toString() to value
        }

    private fun templateId(catalogId: String, templateId: String): TemplateId {
        val catalog = CatalogId(mcpCatalogKey(catalogId), mcpTenantId())
        return TemplateId(TemplateKey.of(templateId), catalog)
    }

    private fun variantId(catalogId: String, templateId: String, variantId: String): VariantId {
        val parent = templateId(catalogId, templateId)
        return VariantId(VariantKey.of(variantId), parent)
    }
}

/** What a template's `description` argument is, for its create and update tools. */
private const val TEMPLATE_DESCRIPTION_DESCRIPTION =
    "Plain-text notes on the template for its authors: what it is for, design decisions, known gaps. " +
        "Not rendered; at most 2000 characters; travels with the template in its catalog."
