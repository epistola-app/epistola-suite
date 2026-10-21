// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.generation.pdf.ResolvedTheme
import app.epistola.generation.pdf.SpacingScale
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogVersion
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
 * @property templateModel Optional live template model from the editor (overrides the working model)
 * @property releaseVersion Optional catalog release to render the variant as released; when absent,
 *   renders the variant's working model
 */
data class PreviewRequest(
    val data: ObjectNode? = null,
    val templateModel: Map<String, Any?>? = null,
    val releaseVersion: String? = null,
    val theme: EditorThemeConfig? = null,
)

/**
 * Handles PDF preview generation for document templates.
 *
 * Single endpoint for all preview scenarios:
 * - `releaseVersion` set → render the variant as that catalog release holds it
 * - `templateModel` set → render live editor content (ignores releaseVersion)
 * - Neither → render the variant's working model
 */
@Component
class TemplatePreviewHandler(
    private val objectMapper: ObjectMapper,
    private val generationService: GenerationService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

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
            if (previewRequest.releaseVersion != null && previewRequest.templateModel == null) {
                PreviewDocument(
                    tenantId = tenantId.key,
                    catalogKey = catalogId,
                    templateId = templateId.key,
                    variantId = variantId.key,
                    data = dataNode,
                    releaseVersion = previewRequest.releaseVersion,
                ).query()
            } else {
                // Render the working model, optionally with live template model from editor
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
                    catalogVersion = request.catalogVersion(),
                    resolvedTheme = previewRequest.theme?.let { theme ->
                        ResolvedTheme(
                            documentStyles = theme.documentStyles,
                            pageSettings = theme.pageSettings,
                            blockStylePresets = theme.blockStylePresets
                                ?.mapValues { (_, preset) -> preset.styles }
                                .orEmpty(),
                            spacingUnit = theme.spacingUnit ?: SpacingScale.DEFAULT_BASE_UNIT,
                        )
                    },
                ).query()
            }
        } catch (e: ValidationException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "errors" to listOf(
                            mapOf(
                                "path" to e.field,
                                "message" to e.message,
                                "code" to e.code.wire,
                            ),
                        ),
                    ),
                )
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
        } catch (e: IllegalStateException) {
            // The variant, its template or the tenant is gone (deleted meanwhile, or in another
            // version than the one open). Said in a sentence the editor shows, not a bare 404.
            logger.info("Preview of {}/{} refused: {}", templateId, variantId, e.message)
            return ServerResponse.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "errors" to listOf(
                            mapOf("path" to "", "message" to "This variant could not be found: it may have been deleted, or belong to another release or draft. Reload the page."),
                        ),
                    ),
                )
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .body(pdfBytes)
    }
}
