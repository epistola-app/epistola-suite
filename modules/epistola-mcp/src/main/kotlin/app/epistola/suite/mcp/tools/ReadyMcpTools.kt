// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mcp.dto.CatalogChangesInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.MarkStencilReady
import app.epistola.suite.templates.commands.MarkTemplateReady
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Ready marks (contract ADR 0002): a catalog release contains only content someone marked ready, and
 * an edit makes a mark stale. Releasing itself is not offered here. Each tool dispatches the command
 * the UI and REST API use, so the same checks apply, including that every stencil instance fits.
 */
@Component
class ReadyMcpTools {

    @McpTool(
        name = "get_catalog_changes",
        description = "List what an AUTHORED catalog's next release would contain that its last one did not: " +
            "every resource with its state (`NEW`, `MODIFIED`, `RELEASED`, `REMOVED`, `UNKNOWN`) and whether it " +
            "is marked ready. `releasable` is false while a changed resource is not ready.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getCatalogChanges(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
    ): CatalogChangesInfo = CatalogChangesInfo.from(GetCatalogResourceChanges(mcpTenantKey(), CatalogKey.of(catalogId)).query())

    @McpTool(
        name = "mark_variant_ready",
        description = "Record that a template variant's current content, with its template's data contract, is " +
            "reviewed and may be released. Refused while the model cannot be released, for instance while a " +
            "stencil instance does not fit its stencil (`list_stencil_usages` shows which). " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun markVariantReady(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(description = "Variant key.")
        variantId: String,
    ): CatalogChangesInfo {
        MarkVariantReady(VariantId(VariantKey.of(variantId), templateId(catalogId, templateId))).execute()
        return getCatalogChanges(catalogId)
    }

    @McpTool(
        name = "mark_template_ready",
        description = "Mark every variant of a template ready, with its data contract, in one action. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun markTemplateReady(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
    ): CatalogChangesInfo {
        MarkTemplateReady(templateId(catalogId, templateId)).execute()
        return getCatalogChanges(catalogId)
    }

    @McpTool(
        name = "mark_stencil_ready",
        description = "Record that a stencil's current content is reviewed and may be released. Templates use " +
            "stencils by reference, so they see the stencil as it is; releasing checks that every instance fits. " +
            "Requires the STENCIL_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun markStencilReady(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
    ): CatalogChangesInfo {
        MarkStencilReady(StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))).execute()
        return getCatalogChanges(catalogId)
    }

    @McpTool(
        name = "mark_catalog_ready",
        description = "Mark every new or modified resource of an AUTHORED catalog ready in one action, and return " +
            "the changes that remain. Stops at the first resource that cannot be marked. " +
            "Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun markCatalogReady(
        @McpToolParam(description = "Catalog key of an AUTHORED catalog. From `list_catalogs`.")
        catalogId: String,
    ): CatalogChangesInfo = CatalogChangesInfo.from(MarkCatalogReady(mcpTenantKey(), CatalogKey.of(catalogId)).execute())

    private fun templateId(catalogId: String, templateId: String) = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
}
