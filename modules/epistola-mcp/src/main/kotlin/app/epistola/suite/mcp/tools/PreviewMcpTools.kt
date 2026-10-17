// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.queries.AnalyzeTemplateData
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mcp.dto.PreviewResult
import app.epistola.suite.mcp.dto.TemplateDataAnalysisInfo
import app.epistola.suite.mcp.support.CATALOG_VERSION_DESCRIPTION
import app.epistola.suite.mcp.support.catalogVersionRef
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
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
            "By default this renders the catalog's latest release, exactly as generation would, and fails when " +
            "the catalog has never been released. Set `workingCopy` to render the working copy " +
            "instead, to see unreleased work, or `catalogVersion` to render one draft's. Set `environmentId` to render the highest release that environment serves, " +
            "and `releaseVersion` to pick the highest release satisfying a version selector; the two combine. " +
            "If the data does not satisfy the template's data contract the call fails; `analyze_template_data` " +
            "then says which fields are missing or wrong.",
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
            description = "Render the working copy instead of the latest release.",
            required = false,
        )
        workingCopy: Boolean?,
        @McpToolParam(
            description = "Version selector in npm range grammar (e.g. '1.4.0', '^1.4.0'): the highest release satisfying it is rendered. Combines with `environmentId`; not with `workingCopy`.",
            required = false,
        )
        releaseVersion: String?,
        @McpToolParam(
            description = "Environment key (e.g. 'production'). The highest release that environment serves (satisfying `releaseVersion`, when set) is rendered.",
            required = false,
        )
        environmentId: String?,
        @McpToolParam(
            description = "JSON object string with sample data conforming to the template's data contract. Omit to use the contract's first example.",
            required = false,
        )
        data: String?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): PreviewResult {
        val parsedData = parseData(data)
        val version = catalogVersionRef(catalogId, catalogVersion)
        if (version != null && (releaseVersion != null || environmentId != null)) {
            throw ValidationException("catalogVersion", "`catalogVersion` renders a draft; it cannot be combined with `releaseVersion` or `environmentId`")
        }

        val pdfBytes = mediator.query(
            PreviewDocument(
                tenantId = mcpTenantKey(),
                catalogKey = CatalogKey.of(catalogId),
                templateId = TemplateKey.of(templateId),
                variantId = variantId?.let { VariantKey.of(it) },
                environmentId = environmentId?.let { EnvironmentKey.of(it) },
                releaseVersion = releaseVersion,
                data = parsedData,
                // A draft named renders that draft: its working copy.
                workingCopy = workingCopy == true || version != null,
                catalogVersion = version,
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
        description = "Check template data against the data contract of the release `preview_document` would " +
            "render, without rendering. Returns which fields are missing (required ones always, optional ones " +
            "only when the template uses them) and which supplied values are wrong, each with a JSON Pointer " +
            "into `data` and the field's JSON Schema. Use it to ask the user for exactly the data the template " +
            "still needs. Takes the same arguments as `preview_document`.",
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
            description = "Version selector in npm range grammar (e.g. '1.4.0', '^1.4.0'): the highest release satisfying it is checked against. Combines with `environmentId`.",
            required = false,
        )
        releaseVersion: String?,
        @McpToolParam(
            description = "Environment key (e.g. 'production'); the highest release it serves (satisfying `releaseVersion`, when set) is checked against.",
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
            releaseVersion = releaseVersion,
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
