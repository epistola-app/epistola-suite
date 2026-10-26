// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.api.v1.toValidationProblemBody
import app.epistola.suite.common.ResourceModifiedException
import app.epistola.suite.htmx.catalogVersion
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.RefTypeRegistry
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The data contract tab: a template has one data contract, its schema and examples, edited in
 * place. There is no draft to publish and no history; the contract is released with its catalog.
 */
@Component
class DataContractTabHandler(
    private val detailHelper: TemplateDetailHelper,
    private val objectMapper: ObjectMapper,
) {
    fun dataContract(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()
        val editMode = request.params().getFirst("edit") == "true"

        val contract = GetDataContract(templateId = ctx.templateId, catalogVersion = ctx.catalogVersion).query()

        // For view mode: extract schema fields as a simple list for rendering
        val schemaFields = contract?.dataModel?.let { extractSchemaFields(it) } ?: emptyList()

        return detailHelper.renderDetailPage(
            ctx,
            "data-contract",
            mapOf(
                "editMode" to editMode,
                "contractDataModel" to contract?.dataModel,
                "contractDataExamples" to contract?.dataExamples,
                "contractRevision" to contract?.revision,
                "schemaFields" to schemaFields,
            ),
        )
    }

    /**
     * `PATCH /{catalogId}/{id}/contract` — updates the schema and/or examples (JSON, from the data
     * contract editor). Examples that do not satisfy the schema refuse the save, unless
     * `forceUpdate` is set: then it goes through and the failures come back as `warnings`.
     */
    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val body = request.body(String::class.java)
        val update = objectMapper.readValue(body, ContractUpdateRequest::class.java)

        val result = try {
            UpdateDataContract(
                templateId = templateId,
                dataModel = update.dataModel,
                dataExamples = update.dataExamples,
                clearDataModel = update.clearDataModel ?: false,
                forceUpdate = update.forceUpdate ?: false,
                catalogVersion = request.catalogVersion(),
                expectedRevision = update.expectedRevision,
            ).execute()
        } catch (e: ResourceModifiedException) {
            return ServerResponse.status(HttpStatus.PRECONDITION_FAILED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("detail" to "Someone else saved this data contract after you opened it. Reload the page to see their version."))
        } catch (e: ValidationException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(e.toValidationProblemBody(request.servletRequest()))
        } catch (e: DataModelValidationException) {
            val detail = e.validationErrors.entries.joinToString("; ") { (exampleName, errors) ->
                errors.joinToString("; ") { error ->
                    val location = error.path.ifBlank { "root" }
                    "$exampleName at $location: ${error.message}"
                }
            }
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "detail" to "Example data does not match the schema. $detail",
                        "errors" to e.validationErrors,
                    ),
                )
        }

        // The examples as stored, each addressed by its key (ADR 0033): the editor takes their ids.
        val response = mutableMapOf<String, Any?>("success" to true, "revision" to result.revision, "dataExamples" to result.contract.dataExamples)
        if (result.warnings.isNotEmpty()) {
            response["warnings"] = result.warnings
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(response))
    }

    /** Extracts a flat list of schema fields for read-only display. */
    private fun extractSchemaFields(dataModel: ObjectNode, prefix: String = "", depth: Int = 0): List<SchemaFieldView> {
        val properties = dataModel.get("properties") as? ObjectNode ?: return emptyList()
        val requiredNode = dataModel.get("required")
        val required = mutableSetOf<String>()
        if (requiredNode != null) {
            for (el in requiredNode) {
                required.add(el.asString())
            }
        }
        val fields = mutableListOf<SchemaFieldView>()

        for ((name, _) in properties.properties()) {
            val prop = properties.get(name) as? ObjectNode ?: continue
            val type = prop.get("type")?.asString()
                ?: RefTypeRegistry.findByUrl(prop.get("\$ref")?.asString())?.label
                ?: "unknown"
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            val description = prop.get("description")?.asString()

            fields.add(SchemaFieldView(path = path, name = name, type = type, required = name in required, description = description, depth = depth))

            if (type == "object") {
                fields.addAll(extractSchemaFields(prop, path, depth + 1))
            } else if (type == "array") {
                val items = prop.get("items") as? ObjectNode
                if (items != null && items.get("type")?.asString() == "object") {
                    fields.addAll(extractSchemaFields(items, "$path[]", depth + 1))
                }
            }
        }
        return fields
    }
}

data class SchemaFieldView(
    val path: String,
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
    val depth: Int,
)

private data class ContractUpdateRequest(
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
    val clearDataModel: Boolean? = null,
    val forceUpdate: Boolean? = null,
    /** The template's revision as the editor last read or saved it; a save over someone else's is refused (412). */
    val expectedRevision: Long? = null,
)
