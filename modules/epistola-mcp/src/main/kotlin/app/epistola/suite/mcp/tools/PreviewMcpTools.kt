// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.queries.AnalyzeTemplateData
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mcp.dto.PreviewResult
import app.epistola.suite.mcp.dto.TemplateDataAnalysisInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.query
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Base64

@Component
class PreviewMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) {

    @McpTool(
        name = "preview_document",
        description = "Render a preview of a template variant as a PDF and return it base64-encoded. " +
            "When `data` is omitted, the contract's first sample example is used. " +
            "Specify either `versionId` (a specific version number) OR `environmentId` (resolves to the " +
            "version active in that environment) — never both. If neither is set, the latest published " +
            "version of the variant is used. If the data does not satisfy the template's data contract the " +
            "call fails; `analyze_template_data` then says which fields are missing or wrong.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun previewDocument(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(
            description = "Variant key. If omitted, the template's default variant is used.",
            required = false,
        )
        variantId: String?,
        @McpToolParam(
            description = "Specific version number (1..N) to render. Mutually exclusive with `environmentId`.",
            required = false,
        )
        versionId: Int?,
        @McpToolParam(
            description = "Environment key (e.g. 'production'). The active version for that environment is rendered. Mutually exclusive with `versionId`.",
            required = false,
        )
        environmentId: String?,
        @McpToolParam(
            description = "JSON object string with sample data conforming to the template's data contract. Omit to use the contract's first example.",
            required = false,
        )
        data: String?,
    ): PreviewResult {
        val parsedData = parseData(data)

        val pdfBytes = mediator.query(
            PreviewDocument(
                tenantId = mcpTenantKey(),
                catalogKey = CatalogKey.of(catalogId),
                templateId = TemplateKey.of(templateId),
                variantId = variantId?.let { VariantKey.of(it) },
                versionId = versionId?.let { VersionKey.of(it) },
                environmentId = environmentId?.let { EnvironmentKey.of(it) },
                data = parsedData,
            ),
        )

        return PreviewResult(
            mediaType = "application/pdf",
            data = Base64.getEncoder().encodeToString(pdfBytes),
            byteCount = pdfBytes.size,
        )
    }

    @McpTool(
        name = "analyze_template_data",
        description = "Check preview data against the data contract of the version `preview_document` would " +
            "render, without rendering. Returns which fields are missing (required ones always, optional ones " +
            "only when the template uses them), which supplied values are wrong, and `missingDataSchema`: a JSON " +
            "Schema of just the missing part. Use it to ask the user for exactly the data the template still " +
            "needs. Takes the same arguments as `preview_document`; paths are JSON Pointers into `data`.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun analyzeTemplateData(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(
            description = "Variant key. If omitted, the template's default variant is used.",
            required = false,
        )
        variantId: String?,
        @McpToolParam(
            description = "Specific version number (1..N). Mutually exclusive with `environmentId`.",
            required = false,
        )
        versionId: Int?,
        @McpToolParam(
            description = "Environment key (e.g. 'production'); the version active there is checked. Mutually exclusive with `versionId`.",
            required = false,
        )
        environmentId: String?,
        @McpToolParam(
            description = "JSON object string with the data to check. Omit to check the contract's first example.",
            required = false,
        )
        data: String?,
    ): TemplateDataAnalysisInfo {
        val analysis = AnalyzeTemplateData(
            tenantKey = mcpTenantKey(),
            catalogKey = CatalogKey.of(catalogId),
            templateId = TemplateKey.of(templateId),
            variantId = variantId?.let { VariantKey.of(it) },
            versionId = versionId?.let { VersionKey.of(it) },
            environmentId = environmentId?.let { EnvironmentKey.of(it) },
            data = parseData(data),
        ).query()
        return TemplateDataAnalysisInfo.from(analysis)
    }

    private fun parseData(data: String?): ObjectNode = data?.takeIf { it.isNotBlank() }?.let {
        objectMapper.readTree(it) as? ObjectNode
            ?: error("`data` must be a JSON object")
    } ?: objectMapper.createObjectNode()
}
