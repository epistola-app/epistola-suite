package app.epistola.suite.templates

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.htmx
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.queries.ListContractVersions
import app.epistola.suite.templates.model.DataExample
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Component
class ContractVersionHandler(
    private val objectMapper: ObjectMapper,
) {
    private fun resolveTemplateId(request: ServerRequest): TemplateId {
        val tenantId = TenantId(TenantKey.of(request.pathVariable("tenantId")))
        val catalogId = CatalogId(CatalogKey.of(request.pathVariable("catalogId")), tenantId)
        return TemplateId(TemplateKey.of(request.pathVariable("id")), catalogId)
    }

    /**
     * POST /{catalogId}/{id}/contract/draft — create a draft contract (start editing).
     * Idempotent: returns existing draft if one exists.
     */
    fun createDraft(request: ServerRequest): ServerResponse {
        val templateId = resolveTemplateId(request)
        val result = CreateContractVersion(templateId = templateId).execute()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                objectMapper.writeValueAsString(
                    mapOf(
                        "id" to result.id.value,
                        "status" to result.status.name.lowercase(),
                    ),
                ),
            )
    }

    /**
     * PATCH /{catalogId}/{id}/contract/draft — update the draft contract's schema and/or examples.
     * Auto-creates a draft if none exists.
     */
    fun updateDraft(request: ServerRequest): ServerResponse {
        val templateId = resolveTemplateId(request)
        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, ContractUpdateRequest::class.java)

        // Ensure a draft exists (idempotent)
        CreateContractVersion(templateId = templateId).execute()
            ?: return ServerResponse.notFound().build()

        val result = UpdateContractVersion(
            templateId = templateId,
            dataModel = updateRequest.dataModel,
            dataExamples = updateRequest.dataExamples,
            forceUpdate = updateRequest.forceUpdate ?: false,
        ).execute() ?: return ServerResponse.notFound().build()

        val response = mutableMapOf<String, Any?>(
            "success" to true,
            "id" to result.contractVersion.id.value,
            "status" to result.contractVersion.status.name.lowercase(),
        )
        if (result.warnings.isNotEmpty()) {
            response["warnings"] = result.warnings
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(response))
    }

    /**
     * POST /{catalogId}/{id}/contract/publish — publish the draft contract.
     * Pass {"confirmed": true} in the body for breaking changes.
     * Without confirmation, breaking changes return a preview.
     */
    fun publish(request: ServerRequest): ServerResponse {
        val templateId = resolveTemplateId(request)
        val confirmed = try {
            val body = request.body(String::class.java)
            if (body.isNotBlank()) {
                objectMapper.readTree(body).get("confirmed")?.asBoolean() ?: false
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        val result = PublishContractVersion(templateId = templateId, confirmed = confirmed).execute()
            ?: return ServerResponse.notFound().build()

        val response = mutableMapOf<String, Any?>(
            "published" to result.published,
            "compatible" to result.compatible,
            "breakingChanges" to result.breakingChanges.map {
                mapOf("type" to it.type.name, "path" to it.path, "description" to it.description)
            },
            "upgradedVersionCount" to result.upgradedVersionCount,
            "incompatibleVersions" to result.incompatibleVersions.map {
                mapOf(
                    "variantKey" to it.variantKey.value,
                    "versionId" to it.versionId.value,
                    "activeEnvironments" to it.activeEnvironments,
                )
            },
        )
        result.publishedVersion?.let { response["id"] = it.id.value }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(response))
    }

    /**
     * GET /{catalogId}/{id}/contract/versions — list all contract versions.
     */
    fun listVersions(request: ServerRequest): ServerResponse {
        val templateId = resolveTemplateId(request)
        val versions = ListContractVersions(templateId = templateId).query()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                objectMapper.writeValueAsString(
                    versions.map {
                        mapOf(
                            "id" to it.id.value,
                            "status" to it.status.name.lowercase(),
                            "createdAt" to it.createdAt,
                            "publishedAt" to it.publishedAt,
                        )
                    },
                ),
            )
    }

    /**
     * GET /{catalogId}/{id}/contract/versions/history — contract version history dialog (HTMX fragment).
     */
    fun versionHistory(request: ServerRequest): ServerResponse {
        val templateId = resolveTemplateId(request)
        val versions = ListContractVersions(templateId = templateId).query()

        return request.htmx {
            fragment("templates/contract-versions", "content") {
                "versions" to versions
            }
            onNonHtmx { redirect("/tenants/${templateId.tenantKey.value}/templates/${templateId.catalogKey.value}/${templateId.key.value}") }
        }
    }
}

private data class ContractUpdateRequest(
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
    val forceUpdate: Boolean? = null,
)
