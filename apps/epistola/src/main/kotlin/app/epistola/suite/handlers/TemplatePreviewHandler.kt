package app.epistola.suite.templates

import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.mediator.query
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Request body for PDF preview generation.
 *
 * @property data The data context for expression evaluation
 * @property templateModel Optional live template model from the editor (overrides stored version)
 * @property versionId Optional version to render; when absent, renders the current draft
 */
data class PreviewRequest(
    val data: ObjectNode? = null,
    val templateModel: Map<String, Any?>? = null,
    val versionId: Int? = null,
)

/**
 * Handles PDF preview generation for document templates.
 *
 * Single endpoint for all preview scenarios:
 * - `versionId` set → render that stored version (draft, published, or archived)
 * - `templateModel` set → render live editor content (ignores versionId)
 * - Neither → render the current draft
 */
@Component
class TemplatePreviewHandler(
    private val objectMapper: ObjectMapper,
    private val generationService: GenerationService,
) {

    fun preview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val previewRequest: PreviewRequest = try {
            val body = request.body(String::class.java)
            if (body.isBlank()) PreviewRequest() else objectMapper.readValue(body, PreviewRequest::class.java)
        } catch (_: Exception) {
            PreviewRequest()
        }

        val dataNode = previewRequest.data ?: objectMapper.createObjectNode()

        val pdfBytes = try {
            if (previewRequest.versionId != null && previewRequest.templateModel == null) {
                // Render a specific stored version
                PreviewDocument(
                    tenantId = tenantId.key,
                    catalogKey = catalogId,
                    templateId = templateId.key,
                    data = dataNode,
                    variantId = variantId.key,
                    versionId = VersionKey.of(previewRequest.versionId),
                ).query()
            } else {
                // Render draft, optionally with live template model from editor
                val liveTemplateModel = previewRequest.templateModel?.let {
                    generationService.convertTemplateModel(it)
                }
                PreviewVariant(
                    tenantId = tenantId.key,
                    catalogKey = catalogId,
                    templateId = templateId.key,
                    variantId = variantId.key,
                    data = dataNode,
                    templateModel = liveTemplateModel,
                ).query()
            }
        } catch (e: IllegalArgumentException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "errors" to listOf(
                            mapOf(
                                "path" to "",
                                "message" to (e.message ?: "Validation failed"),
                            ),
                        ),
                    ),
                )
        } catch (_: IllegalStateException) {
            return ServerResponse.notFound().build()
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .body(pdfBytes)
    }
}
