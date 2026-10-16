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
import app.epistola.suite.mcp.dto.ContractPublishInfo
import app.epistola.suite.mcp.dto.VersionInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Taking an authored catalog's templates from drafts to published versions. Stencils have no
 * versions: templates use them by reference. Each tool dispatches the command the UI and REST API
 * use, so the publish permission (`TEMPLATE_PUBLISH`) and every publish-time check apply unchanged,
 * including that every stencil instance fits its stencil. Activating a version in an environment
 * is deliberately not offered here.
 */
@Component
class PublishMcpTools {

    @McpTool(
        name = "publish_data_contract",
        description = "Publish a template's draft data contract on its own. Usually unnecessary: " +
            "`publish_template_version` publishes a compatible draft contract automatically. When the draft " +
            "breaks the published contract (a field removed or retyped), this returns `published: false` with " +
            "the `breakingChanges` and the `incompatibleVersions` it would affect, and publishes nothing. Show " +
            "that to the user, and call again with `confirmBreaking: true` only after they agree. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun publishDataContract(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(
            description = "Publish even though the draft breaks the published contract. Default false.",
            required = false,
        )
        confirmBreaking: Boolean?,
    ): ContractPublishInfo {
        val id = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        return PublishContractVersion(templateId = id, confirmed = confirmBreaking == true).execute()
            ?.let(ContractPublishInfo::from)
            ?: throw ValidationException("templateId", "Template '$catalogId/$templateId' has no draft data contract to publish")
    }

    @McpTool(
        name = "publish_template_version",
        description = "Publish a template variant's draft, freezing its content with the current theme and " +
            "rendering defaults, and publishing its draft data contract when that is compatible. It does NOT " +
            "activate the version in any environment; documents are generated from it once it is activated " +
            "in the UI. Publishing is refused while a stencil instance does not fit its stencil (a fill for a " +
            "placeholder the stencil lacks, a required parameter left unbound); `list_stencil_usages` shows which. " +
            "Use `publish_data_contract` first if the contract change is breaking. " +
            "Requires the TEMPLATE_PUBLISH permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun publishTemplateVersion(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
        @McpToolParam(description = "Version number to publish. Omit to publish the variant's draft.", required = false)
        versionId: Int?,
    ): VersionInfo {
        val variant = variantId(catalogId, templateId, variantId)
        val version = versionId
            ?: ListVersions(variant).query().firstOrNull { it.status == VersionStatus.DRAFT }?.id?.value
            ?: throw ValidationException("versionId", "Variant '$templateId/$variantId' has no draft to publish")
        return PublishVersion(VersionId(VersionKey.of(version), variant)).execute()
            ?.let(VersionInfo::from)
            ?: throw notFound("Version", catalogId, "$templateId/$variantId/$version")
    }

    private fun variantId(catalogId: String, templateId: String, variantId: String) = VariantId(
        VariantKey.of(variantId),
        TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId())),
    )
}
