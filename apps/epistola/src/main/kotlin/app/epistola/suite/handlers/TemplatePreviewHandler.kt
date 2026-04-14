package app.epistola.suite.templates

import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.documents.queries.PreviewDraft
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.htmx.versionId
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
 * @property templateModel Optional template model for live preview (uses current editor state instead of saved draft)
 */
data class PreviewRequest(
    val data: Map<String, Any?>? = null,
    val templateModel: Map<String, Any?>? = null,
)

/**
 * Handles PDF preview generation for document templates.
 * Delegates to the unified [PreviewDocument] query.
 */
@Component
class TemplatePreviewHandler(
    private val objectMapper: ObjectMapper,
    private val generationService: GenerationService,
) {

    /**
     * Generates a PDF preview of a variant's draft version.
     * Streams the PDF directly to the response.
     *
     * If `templateModel` is provided in the request body, it will be used for rendering
     * instead of fetching from the database. This enables live preview of unsaved changes.
     */
    fun preview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        // Parse the request body
        val previewRequest: PreviewRequest = try {
            val body = request.body(String::class.java)
            if (body.isBlank()) {
                PreviewRequest()
            } else {
                objectMapper.readValue(body, PreviewRequest::class.java)
            }
        } catch (_: Exception) {
            PreviewRequest()
        }

        val data = previewRequest.data ?: emptyMap()

        // Convert live template model if provided
        val liveTemplateModel = previewRequest.templateModel?.let {
            generationService.convertTemplateModel(it)
        }

        // Build the data as ObjectNode for the query
        val dataNode = objectMapper.valueToTree<ObjectNode>(data)

        val query = PreviewDraft(
            tenantId = tenantId.key,
            catalogKey = catalogId,
            templateId = templateId.key,
            variantId = variantId.key,
            data = dataNode,
            templateModel = liveTemplateModel,
        )

        val pdfBytes = try {
            query.query()
        } catch (e: IllegalArgumentException) {
            // Data validation failure
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
        } catch (e: IllegalStateException) {
            // Template/variant/draft not found
            return ServerResponse.notFound().build()
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .body(pdfBytes)
    }

    /**
     * Generates a PDF preview of a specific version (draft or published).
     * Used by the version comparison dialog.
     */
    fun previewVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.versionId(variantId)
            ?: return ServerResponse.badRequest().build()

        val data: ObjectNode = try {
            val body = request.body(String::class.java)
            if (body.isBlank()) {
                objectMapper.createObjectNode()
            } else {
                val parsed = objectMapper.readTree(body) as ObjectNode
                // Unwrap { data: ... } wrapper if present (same format as draft preview)
                (parsed.get("data") as? ObjectNode) ?: parsed
            }
        } catch (_: Exception) {
            objectMapper.createObjectNode()
        }

        val pdfBytes = try {
            PreviewDocument(
                tenantId = tenantId.key,
                catalogKey = catalogId,
                templateId = templateId.key,
                data = data,
                variantId = variantId.key,
                versionId = versionId.key,
            ).query()
        } catch (e: IllegalArgumentException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to (e.message ?: "Validation failed")))
        } catch (_: IllegalStateException) {
            return ServerResponse.notFound().build()
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .body(pdfBytes)
    }
}
