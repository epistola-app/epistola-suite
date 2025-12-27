package app.epistola.suite.templates

import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.validation.ValidationException
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Request body for updating a document template's metadata.
 * Note: templateModel is now stored in TemplateVersion and updated separately.
 */
data class UpdateTemplateRequest(
    val name: String? = null,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
)

@Component
class DocumentTemplateHandler(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templates = mediator.query(ListDocumentTemplates(tenantId = tenantId))
        return ServerResponse.ok().render(
            "templates/list",
            mapOf(
                "tenantId" to tenantId,
                "templates" to templates,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val searchTerm = request.param("q").orElse(null)
        val templates = mediator.query(ListDocumentTemplates(tenantId = tenantId, searchTerm = searchTerm))
        return request.htmx {
            fragment("templates/list", "rows") {
                "tenantId" to tenantId
                "templates" to templates
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val name = request.params().getFirst("name")?.trim().orEmpty()

        val command = try {
            CreateDocumentTemplate(tenantId = tenantId, name = name)
        } catch (e: ValidationException) {
            val formData = mapOf("name" to name)
            val errors = mapOf(e.field to e.message)
            return request.htmx {
                fragment("templates/list", "create-form") {
                    "tenantId" to tenantId
                    "formData" to formData
                    "errors" to errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/tenants/$tenantId/templates") }
            }
        }

        mediator.send(command)

        val templates = mediator.query(ListDocumentTemplates(tenantId = tenantId))
        return request.htmx {
            fragment("templates/list", "rows") {
                "tenantId" to tenantId
                "templates" to templates
            }
            trigger("templateCreated")
            onNonHtmx { redirect("/tenants/$tenantId/templates") }
        }
    }

    fun editor(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = id))
            ?: return ServerResponse.notFound().build()

        // TODO: Load templateModel from draft version instead
        // The editor needs to be updated to work with variants/versions
        return ServerResponse.ok().render(
            "templates/editor",
            mapOf(
                "tenantId" to tenantId,
                "templateId" to id,
                "templateName" to template.name,
                "templateModel" to emptyMap<String, Any>(),
            ),
        )
    }

    fun get(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = id))
            ?: return ServerResponse.notFound().build()

        // Note: templateModel is now in versions, not in template
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to template.id,
                    "name" to template.name,
                    "dataModel" to template.dataModel,
                    "dataExamples" to template.dataExamples,
                    "createdAt" to template.createdAt,
                    "lastModified" to template.lastModified,
                ),
            )
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateTemplateRequest::class.java)

        // Note: templateModel updates should go through version commands now
        return try {
            val updated = mediator.send(
                UpdateDocumentTemplate(
                    tenantId = tenantId,
                    id = id,
                    name = updateRequest.name,
                    dataModel = updateRequest.dataModel,
                    dataExamples = updateRequest.dataExamples,
                ),
            ) ?: return ServerResponse.notFound().build()

            ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "id" to updated.id,
                        "name" to updated.name,
                        "dataModel" to updated.dataModel,
                        "dataExamples" to updated.dataExamples,
                        "createdAt" to updated.createdAt,
                        "lastModified" to updated.lastModified,
                    ),
                )
        } catch (e: DataModelValidationException) {
            ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("errors" to e.validationErrors))
        }
    }

    private fun resolveTenantId(request: ServerRequest): Long = request.pathVariable("tenantId").toLongOrNull()
        ?: throw IllegalArgumentException("Invalid tenant ID")
}
