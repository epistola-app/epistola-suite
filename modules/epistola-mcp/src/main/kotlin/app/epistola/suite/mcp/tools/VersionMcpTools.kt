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
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mcp.dto.VersionContentInfo
import app.epistola.suite.mcp.dto.VersionInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mcp.support.parseArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.templates.queries.versions.ListVersions
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class VersionMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) {

    @McpTool(
        name = "list_versions",
        description = "List versions for a template variant. Each variant has at most one draft " +
            "(the editable working copy) plus zero or more published versions (immutable, deployable). " +
            "Drafts are listed first, then published versions newest-first.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listVersions(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
    ): List<VersionInfo> {
        val tenantId = mcpTenantId()
        val vId = VariantId(
            VariantKey.of(variantId),
            TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantId)),
        )
        return mediator.query(ListVersions(vId)).map { VersionInfo.from(it) }
    }

    @McpTool(
        name = "get_version",
        description = "Fetch one exact template version, including its persisted template node/slot " +
            "graph and any rendering-default and resolved-theme snapshot frozen at publication. " +
            "Use this instead of current editor content when diagnosing historical or generated output.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getVersion(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
        @McpToolParam(description = "Sequential version number returned by list_versions.")
        versionId: Int,
    ): VersionContentInfo? {
        val tenantId = mcpTenantId()
        val variant = VariantId(
            VariantKey.of(variantId),
            TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantId)),
        )
        return mediator.query(GetVersion(VersionId(VersionKey.of(versionId), variant)))
            ?.let(VersionContentInfo::from)
    }

    @McpTool(
        name = "update_template_draft",
        description = "Replace the content of a variant's draft with a template document, creating the draft " +
            "when the variant has none. The document is the node/slot graph `get_template_content` returns " +
            "under `templateModel`: `{modelVersion, root, nodes, slots, ...}`. Build it from the component " +
            "types `list_component_types` describes — their `examples` are fragments to copy. Published " +
            "versions are never changed; publishing the draft is done in the UI after review. " +
            "A `stencil` node must carry a copy of the stencil version's content in its `children` slot: " +
            "the server renders only what is stored in the document. Check the result with " +
            "`preview_document`, passing the returned `id` as `versionId` — without it, preview renders " +
            "the latest published version, which a new template does not have. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateTemplateDraft(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
        @McpToolParam(description = "The complete template document, as a JSON object string.")
        content: String,
    ): VersionInfo {
        val variant = VariantId(
            VariantKey.of(variantId),
            TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId())),
        )
        val document = objectMapper.parseArgument("content", content, TemplateDocument::class.java)
        return UpdateDraft(variantId = variant, templateModel = document).execute()
            ?.let(VersionInfo::from)
            ?: throw notFound("Variant", catalogId, "$templateId/$variantId")
    }
}
