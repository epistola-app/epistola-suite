package app.epistola.suite.templates

import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.versions.GetPreviewContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

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
 * Generates PDF previews for draft versions with optional live template model.
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
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
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

        // Validate data against schema BEFORE starting the streaming response
        val validationResult = generationService.validatePreviewData(
            TenantId.of(tenantId),
            templateId,
            data,
        )
        if (!validationResult.valid) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "errors" to validationResult.errors.map { error ->
                            mapOf(
                                "path" to error.path,
                                "message" to error.message,
                            )
                        },
                    ),
                )
        }

        // Get preview context: draft template model and template's default theme
        val previewContext = GetPreviewContext(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
        ).query() ?: return ServerResponse.notFound().build()

        // Resolve the template model - either from request (live preview) or from draft
        val templateModel = if (previewRequest.templateModel != null) {
            generationService.convertTemplateModel(previewRequest.templateModel)
        } else {
            previewContext.draftTemplateModel ?: return ServerResponse.notFound().build()
        }

        val resolvedTenantId = TenantId.of(tenantId)
        val assetResolver = AssetResolver { assetIdStr ->
            GetAssetContent(resolvedTenantId, AssetId.of(assetIdStr)).query()
                ?.let { AssetResolution(it.content, it.mediaType.mimeType) }
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .build { _, response ->
                generationService.renderPdf(
                    resolvedTenantId,
                    templateModel,
                    data,
                    response.outputStream,
                    previewContext.templateThemeId,
                    previewContext.tenantDefaultThemeId,
                    assetResolver = assetResolver,
                )
                response.outputStream.flush()
                null // Return null to indicate no view to render
            }
    }
}
