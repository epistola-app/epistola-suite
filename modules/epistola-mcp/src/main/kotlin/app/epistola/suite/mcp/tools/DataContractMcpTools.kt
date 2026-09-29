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
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mcp.support.parseOptionalArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersionResult
import app.epistola.suite.templates.contracts.queries.GetDraftContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

@Component
class DataContractMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
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

    @McpTool(
        name = "update_data_contract",
        description = "Set a template's draft data contract: the JSON Schema of the data the template reads " +
            "(`dataModel`) and named example datasets (`dataExamples`) that drive previews. Creates the draft " +
            "when only published contract versions exist. Omitted arguments are left unchanged. Every example " +
            "must validate against the schema, and at least one example is required. Publishing the contract " +
            "is done in the UI. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateDataContract(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
        @McpToolParam(
            description = "JSON Schema object describing the input data, as a JSON string. " +
                "Property names must be valid identifiers; templates reference them in expressions.",
            required = false,
        )
        dataModel: String?,
        @McpToolParam(
            description = "JSON array of examples, each `{\"id\": \"...\", \"name\": \"...\", \"data\": {...}}`. " +
                "Replaces all examples. `id` defaults to `example-<n>`.",
            required = false,
        )
        dataExamples: String?,
    ): DataContractInfo {
        val id = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        val command = UpdateContractVersion(
            templateId = id,
            dataModel = objectMapper.parseOptionalArgument("dataModel", dataModel, ObjectNode::class.java),
            dataExamples = objectMapper.parseOptionalArgument("dataExamples", dataExamples, ArrayNode::class.java)
                ?.let(::toExamples),
        )
        val result = sendUpdate(command) ?: run {
            mediator.send(CreateContractVersion(templateId = id)) ?: throw notFound("Template", catalogId, templateId)
            sendUpdate(command)
        } ?: throw notFound("Template", catalogId, templateId)
        return DataContractInfo.from(result.contractVersion)
    }

    /** Surfaces which example failed where; the exception's own message names neither. */
    private fun sendUpdate(command: UpdateContractVersion): UpdateContractVersionResult? = try {
        mediator.send(command)
    } catch (e: DataModelValidationException) {
        val details = e.validationErrors.entries.joinToString("; ") { (example, errors) ->
            "$example: " + errors.joinToString(", ") { "${it.path} ${it.message}" }
        }
        throw ValidationException("dataExamples", "Data examples do not match the schema — $details")
    }

    private fun toExamples(array: ArrayNode): List<DataExample> = array.values().mapIndexed { index, node ->
        val data = node.get("data") as? ObjectNode
            ?: throw ValidationException("dataExamples", "Example ${index + 1} needs a `data` object")
        DataExample(
            id = node.get("id")?.asString()?.takeIf { it.isNotBlank() } ?: "example-${index + 1}",
            name = node.get("name")?.asString()?.takeIf { it.isNotBlank() }
                ?: throw ValidationException("dataExamples", "Example ${index + 1} needs a `name`"),
            data = data,
        )
    }
}
