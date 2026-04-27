package app.epistola.suite.templates

import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.queries.GetContractUsageOverview
import app.epistola.suite.templates.contracts.queries.GetDraftContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import app.epistola.suite.templates.contracts.queries.ListContractVersions
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.node.ObjectNode

@Component
class DataContractTabHandler(
    private val detailHelper: TemplateDetailHelper,
) {
    fun dataContract(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()
        val editMode = request.params().getFirst("edit") == "true"

        val contractVersion = GetLatestContractVersion(templateId = ctx.templateId).query()
        val draftContract = GetDraftContractVersion(templateId = ctx.templateId).query()
        val contractVersions = ListContractVersions(templateId = ctx.templateId).query()
        val latestPublished = GetLatestPublishedContractVersion(templateId = ctx.templateId).query()
        val latestPublishedId = latestPublished?.id?.value
        val usage = GetContractUsageOverview(templateId = ctx.templateId).query()

        // For view mode: extract schema fields as a simple list for rendering
        val schemaFields = contractVersion?.dataModel?.let { extractSchemaFields(it) } ?: emptyList()

        return detailHelper.renderDetailPage(
            ctx,
            "data-contract",
            mapOf(
                "editMode" to editMode,
                "contractDataModel" to contractVersion?.dataModel,
                "contractDataExamples" to contractVersion?.dataExamples,
                "contractVersionId" to contractVersion?.id?.value,
                "contractVersionStatus" to contractVersion?.status?.name?.lowercase(),
                "hasDraftContract" to (draftContract != null),
                "latestPublishedContractId" to latestPublishedId,
                "contractVersionCount" to contractVersions.size,
                "allTemplateVersions" to usage.versions,
                "hasOutdatedVersions" to usage.versions.any {
                    it.status == "published" && it.contractVersion != latestPublishedId && latestPublishedId != null
                },
                "schemaFields" to schemaFields,
            ),
        )
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
            val type = prop.get("type")?.asString() ?: "unknown"
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
