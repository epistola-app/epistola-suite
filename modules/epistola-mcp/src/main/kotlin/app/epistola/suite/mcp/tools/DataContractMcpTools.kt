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
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.contracts.commands.UpdateDataContractResult
import app.epistola.suite.templates.contracts.queries.GetDataContract
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
            "the template expects, plus named sample datasets — as it is now.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getDataContract(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Template key.")
        templateId: String,
    ): DataContractInfo? {
        val id = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        return mediator.query(GetDataContract(id))?.let { DataContractInfo.from(templateId, catalogId, it) }
    }

    @McpTool(
        name = "update_data_contract",
        description = "Set a template's data contract: the JSON Schema of the data the template reads " +
            "(`dataModel`) and named example datasets (`dataExamples`) that drive previews. Omitted arguments " +
            "are left unchanged. Every example must validate against the schema, and at least one example is " +
            "required. An edit makes the template's ready marks stale; mark it ready again with " +
            "`mark_template_ready` once reviewed. Requires the TEMPLATE_EDIT permission.",
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
        val command = UpdateDataContract(
            templateId = id,
            dataModel = objectMapper.parseOptionalArgument("dataModel", dataModel, ObjectNode::class.java),
            dataExamples = objectMapper.parseOptionalArgument("dataExamples", dataExamples, ArrayNode::class.java)
                ?.let(::toExamples),
        )
        return DataContractInfo.from(templateId, catalogId, sendUpdate(command).contract)
    }

    /** Surfaces which example failed where; the exception's own message names neither. */
    private fun sendUpdate(command: UpdateDataContract): UpdateDataContractResult = try {
        command.execute()
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
