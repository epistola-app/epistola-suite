package app.epistola.suite.generation

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.versions.queries.GetDraft
import app.epistola.template.model.TemplateModel
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.OutputStream

/**
 * Exception thrown when data validation fails against the schema.
 */
class DataValidationException(
    val errors: List<ValidationError>,
) : RuntimeException("Data validation failed: ${errors.size} error(s)")

/**
 * Service for document generation operations.
 * Wraps the generation module's DirectPdfRenderer and provides template lookup.
 */
@Service
class GenerationService(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
    private val schemaValidator: JsonSchemaValidator,
    private val pdfRenderer: DirectPdfRenderer = DirectPdfRenderer(),
) {
    /**
     * Generates a PDF preview of a variant's draft version.
     *
     * @param tenantId The tenant ID
     * @param templateId The template ID
     * @param variantId The variant ID
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     * @param validateSchema If true, validates data against the template's schema
     * @param liveTemplateModel Optional template model to use instead of fetching from DB (for live preview)
     * @throws NoSuchElementException if no draft version exists for the variant (when liveTemplateModel is null)
     * @throws DataValidationException if schema validation fails
     */
    fun generatePreview(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        validateSchema: Boolean = true,
        liveTemplateModel: Map<String, Any?>? = null,
    ) {
        // Use provided template model for live preview, otherwise fetch from DB
        val templateModel: TemplateModel = if (liveTemplateModel != null) {
            // Convert Map to TemplateModel
            objectMapper.convertValue(liveTemplateModel, TemplateModel::class.java)
        } else {
            val draft = mediator.query(GetDraft(tenantId, templateId, variantId))
                ?: throw NoSuchElementException("No draft version found for variant $variantId")
            draft.templateModel
                ?: throw NoSuchElementException("Draft version has no template model")
        }

        // Validate data against schema if enabled and schema exists
        if (validateSchema) {
            val template = mediator.query(GetDocumentTemplate(tenantId, templateId))
            if (template?.dataModel != null) {
                val dataNode = objectMapper.valueToTree<ObjectNode>(data)
                val errors = schemaValidator.validate(template.dataModel, dataNode)
                if (errors.isNotEmpty()) {
                    throw DataValidationException(errors)
                }
            }
        }

        pdfRenderer.render(templateModel, data, outputStream)
    }
}
