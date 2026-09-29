// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mcp.dto.ContractPublishInfo
import app.epistola.suite.mcp.dto.StencilUpgradeInfo
import app.epistola.suite.mcp.dto.StencilVersionSummaryInfo
import app.epistola.suite.mcp.dto.VersionInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.StencilVersionNotDraftException
import app.epistola.suite.stencils.StencilVersionNotFoundException
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencilInTemplate
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Taking an authored catalog from drafts to published versions: stencils first, then the templates
 * that embed them. Each tool dispatches the command the UI and REST API use, so the publish
 * permissions (`STENCIL_PUBLISH`, `TEMPLATE_PUBLISH`) and every publish-time check apply unchanged.
 * Activating a version in an environment is deliberately not offered here.
 */
@Component
class PublishMcpTools {

    @McpTool(
        name = "publish_stencil_version",
        description = "Publish a stencil's draft version, freezing its content and parameter schema. Templates " +
            "can only be published against published stencil versions, so publish every stencil a template " +
            "uses first, then run `upgrade_stencil_in_template`. Requires the STENCIL_PUBLISH permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun publishStencilVersion(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
        @McpToolParam(description = "The draft's version number (see `list_stencil_versions`).")
        version: Int,
    ): StencilVersionSummaryInfo {
        val versionId = StencilVersionId(VersionKey.of(version), stencilId(catalogId, stencilId))
        return try {
            StencilVersionSummaryInfo.from(PublishStencilVersion(versionId).execute())
        } catch (_: StencilVersionNotFoundException) {
            throw notFound("Stencil version", catalogId, "$stencilId/$version")
        } catch (_: StencilVersionNotDraftException) {
            val status = GetStencilVersion(versionId).query()?.status?.name?.lowercase() ?: "not a draft"
            throw ValidationException("version", "Stencil version $version of '$stencilId' is already $status; only a draft can be published")
        }
    }

    @McpTool(
        name = "upgrade_stencil_in_template",
        description = "Point every instance of a stencil in a template variant's draft at a published stencil " +
            "version, replacing the instances' content with that version's. Use it after " +
            "`publish_stencil_version` to relink instances that were built against the stencil's draft: " +
            "a template cannot be published while it references a stencil draft. Creates the variant's draft " +
            "from its latest published version when there is none. Placeholder fills and parameter bindings " +
            "are kept where the new version still has them; the result lists what was dropped and which " +
            "required parameters are now unbound. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun upgradeStencilInTemplate(
        @McpToolParam(description = "Catalog key of the template.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
        @McpToolParam(description = "Stencil key. The stencil is looked up in the template's catalog.")
        stencilId: String,
        @McpToolParam(description = "The published stencil version to upgrade to.")
        version: Int,
    ): StencilUpgradeInfo = UpdateStencilInTemplate(
        variantId = variantId(catalogId, templateId, variantId),
        stencilId = stencilId(catalogId, stencilId),
        newVersion = version,
    ).execute()?.let(StencilUpgradeInfo::from)
        ?: throw notFound("Variant", catalogId, "$templateId/$variantId")

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
            "in the UI. The recommended order for a template built on stencil drafts: " +
            "1. `publish_stencil_version` for every stencil the template uses; " +
            "2. `upgrade_stencil_in_template` for each (stencil, variant); " +
            "3. `publish_template_version` (use `publish_data_contract` first if the contract change is breaking). " +
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

    private fun stencilId(catalogId: String, stencilId: String) = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))

    private fun variantId(catalogId: String, templateId: String, variantId: String) = VariantId(
        VariantKey.of(variantId),
        TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId())),
    )
}
