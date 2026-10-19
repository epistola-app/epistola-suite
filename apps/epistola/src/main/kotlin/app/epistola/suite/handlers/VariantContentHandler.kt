// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.api.v1.ApiProblemType
import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.problemBody
import app.epistola.suite.api.v1.toValidationProblemBody
import app.epistola.suite.common.ResourceModifiedException
import app.epistola.suite.htmx.catalogVersion
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.validation.ValidationException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

/**
 * Saves a variant's working model from the template editor. A variant has one working model, which
 * the editor reads on open and writes on save; there are no versions to choose between.
 */
@Component
class VariantContentHandler(
    private val objectMapper: ObjectMapper,
) {

    /**
     * `PUT …/variants/{variantId}/content` with `{ "templateModel": …, "expectedRevision": … }`. Answers
     * `{ "success": true, "revision": … }`: the revision the editor names on its next save. A save
     * against a revision someone else has moved on from is refused with `resource-modified` (412).
     */
    fun updateContent(request: ServerRequest): ServerResponse {
        val servletRequest = request.servletRequest()
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return uiProblem(servletRequest, ApiProblemTypes.BAD_REQUEST, "Unknown or invalid template in the request path.")
        val variantId = request.variantId(templateId)
            ?: return uiProblem(servletRequest, ApiProblemTypes.BAD_REQUEST, "Unknown or invalid variant in the request path.")

        // A missing/blank templateModel is a client error, not a 500.
        val body = objectMapper.readTree(request.body(String::class.java))
        val templateModelJson = body.get("templateModel")
        val expectedRevision = body.get("expectedRevision")?.takeIf { it.isIntegralNumber }?.asLong()
        if (templateModelJson == null || templateModelJson.isNull) {
            return uiProblem(servletRequest, ApiProblemTypes.BAD_REQUEST, "Request body must include a 'templateModel' object.")
        }
        val templateModel = objectMapper.treeToValue(
            templateModelJson,
            app.epistola.suite.templates.model.TemplateDocument::class.java,
        )

        val saved = try {
            // On the draft the editor's route names (`letters@2.0.0`), or the current one.
            UpdateVariantContent(variantId = variantId, templateModel = templateModel, catalogVersion = request.catalogVersion(), expectedRevision = expectedRevision).execute()
        } catch (e: ResourceModifiedException) {
            return uiProblem(servletRequest, ApiProblemTypes.RESOURCE_MODIFIED, "Someone else saved this variant after you opened it.")
        } catch (e: ValidationException) {
            // RFC 9457 problem+json (ValidationProblemDetail) — same shape as the REST surface, so the
            // editor switches on the problem `type` and renders the field-level `errors`.
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(e.toValidationProblemBody(servletRequest))
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("success" to true, "revision" to saved.revision))
    }

    /** Builds an `application/problem+json` [ServerResponse] for a functional UI route. */
    private fun uiProblem(servletRequest: HttpServletRequest, type: ApiProblemType, detail: String): ServerResponse = ServerResponse.status(type.status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problemBody(servletRequest, type, detail))
}
