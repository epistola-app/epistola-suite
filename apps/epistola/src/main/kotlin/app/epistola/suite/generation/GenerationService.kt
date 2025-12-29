package app.epistola.suite.generation

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.versions.queries.GetDraft
import org.springframework.stereotype.Service
import java.io.OutputStream

/**
 * Service for document generation operations.
 * Wraps the generation module's DirectPdfRenderer and provides template lookup.
 */
@Service
class GenerationService(
    private val mediator: Mediator,
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
     * @throws NotFoundException if no draft version exists for the variant
     */
    fun generatePreview(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        data: Map<String, Any?>,
        outputStream: OutputStream,
    ) {
        val draft = mediator.query(GetDraft(tenantId, templateId, variantId))
            ?: throw NoSuchElementException("No draft version found for variant $variantId")

        val templateModel = draft.templateModel
            ?: throw NoSuchElementException("Draft version has no template model")

        pdfRenderer.render(templateModel, data, outputStream)
    }
}
