// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.mcp.dto.DataContractInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.contracts.queries.GetDraftContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class DataContractMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "get_data_contract",
        description = "Fetch the data contract for a template — JSON Schema describing what input data " +
            "the template expects, plus named sample datasets. Use `status='draft'` for the editable contract, " +
            "`status='published'` for the latest published one, or omit `status` to get the most recent " +
            "(draft preferred over published).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getDataContract(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(
            description = "'draft', 'published', or omit for the latest of either (draft wins).",
            required = false,
        )
        status: String?,
    ): DataContractInfo? {
        val id = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        val contract = when (status?.lowercase()) {
            "draft" -> mediator.query(GetDraftContractVersion(id))
            "published" -> mediator.query(GetLatestPublishedContractVersion(id))
            null, "" -> mediator.query(GetLatestContractVersion(id))
            else -> error("Unknown contract status '$status' — expected 'draft', 'published', or omitted.")
        }
        return contract?.let { DataContractInfo.from(it) }
    }
}
