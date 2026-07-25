// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.api.v1.ApiProblemType
import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.problemBody
import app.epistola.suite.api.v1.toValidationProblemBody
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.htmx.versionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DraftHasNoPublishedBaseException
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.DiscardDraft
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.commands.versions.VersionStillActiveException
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.activations.ListActivations
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.validation.ValidationException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

/**
 * Handles version lifecycle routes for document templates.
 * Manages draft creation, archiving of template versions, and version history dialog.
 * Environment-targeted publishing/unpublishing is handled by [DeploymentMatrixHandler].
 */
@Component
class VersionRouteHandler(
    private val objectMapper: ObjectMapper,
    private val variantRouteHandler: VariantRouteHandler,
) {

    fun listVersions(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        return returnVersionsFragment(request, tenantId.key, catalogId, templateId.key, variantId)
    }

    fun createDraft(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        CreateVersion(variantId = variantId).execute()

        return returnVersionsFragment(request, tenantId.key, catalogId, templateId.key, variantId)
    }

    fun publishDraft(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val draft = app.epistola.suite.templates.queries.versions.GetDraft(variantId = variantId).query()
            ?: return ServerResponse.badRequest().build()

        try {
            app.epistola.suite.templates.commands.versions.PublishVersion(
                versionId = app.epistola.suite.common.ids.VersionId(draft.id, variantId),
            ).execute()
        } catch (e: IllegalArgumentException) {
            return variantRouteHandler.renderVariantsSection(
                request = request,
                tenantId = tenantId,
                templateId = templateId,
                errorMessage = e.message,
            )
        }

        // Redirect to reload the page with updated state
        val redirectUrl = "/tenants/${tenantId.key.value}/templates/${catalogId.value}/${templateId.key.value}"
        return ServerResponse.ok()
            .header("HX-Redirect", redirectUrl)
            .build()
    }

    fun discardDraft(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        try {
            DiscardDraft(variantId = variantId).execute()
        } catch (e: DraftHasNoPublishedBaseException) {
            return variantRouteHandler.renderVariantsSection(
                request = request,
                tenantId = tenantId,
                templateId = templateId,
                errorMessage = e.message,
            )
        }

        // Redirect to reload the page with the draft gone and editor state reset
        val redirectUrl = "/tenants/${tenantId.key.value}/templates/${catalogId.value}/${templateId.key.value}"
        return ServerResponse.ok()
            .header("HX-Redirect", redirectUrl)
            .build()
    }

    fun updateDraft(request: ServerRequest): ServerResponse {
        val servletRequest = request.servletRequest()
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return uiProblem(servletRequest, ApiProblemTypes.BAD_REQUEST, "Unknown or invalid template in the request path.")
        val variantId = request.variantId(templateId)
            ?: return uiProblem(servletRequest, ApiProblemTypes.BAD_REQUEST, "Unknown or invalid variant in the request path.")

        // Parse JSON body to get templateModel. A missing/blank templateModel is a client
        // error, not a 500.
        val body = request.body(String::class.java)
        val templateModelJson = objectMapper.readTree(body).get("templateModel")
        if (templateModelJson == null || templateModelJson.isNull) {
            return uiProblem(servletRequest, ApiProblemTypes.BAD_REQUEST, "Request body must include a 'templateModel' object.")
        }
        val templateModel = objectMapper.treeToValue(
            templateModelJson,
            app.epistola.suite.templates.model.TemplateDocument::class.java,
        )

        try {
            UpdateDraft(
                variantId = variantId,
                templateModel = templateModel,
            ).execute()
        } catch (e: ValidationException) {
            // RFC 9457 problem+json (ValidationProblemDetail) — same shape as the REST
            // surface, so the editor switches on the problem `type` and renders the
            // field-level `errors` instead of regex-parsing the message.
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(e.toValidationProblemBody(servletRequest))
        }

        // Return minimal success response (UI doesn't need full DTO)
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("success" to true))
    }

    /** Builds an `application/problem+json` [ServerResponse] for a functional UI route. */
    private fun uiProblem(servletRequest: HttpServletRequest, type: ApiProblemType, detail: String): ServerResponse = ServerResponse.status(type.status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problemBody(servletRequest, type, detail))

    fun archiveVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.versionId(variantId)
            ?: return ServerResponse.badRequest().build()

        try {
            ArchiveVersion(versionId = versionId).execute()
        } catch (_: VersionStillActiveException) {
            return returnVersionsFragment(request, tenantId.key, catalogId, templateId.key, variantId, error = "Cannot archive: version is still active in one or more environments. Remove it from all environments first.")
        }

        return returnVersionsFragment(request, tenantId.key, catalogId, templateId.key, variantId)
    }

    private fun returnVersionsFragment(
        request: ServerRequest,
        tenantKey: TenantKey,
        catalogId: CatalogKey,
        templateKey: TemplateKey,
        variantId: VariantId,
        error: String? = null,
    ): ServerResponse {
        val templateId = TemplateId(templateKey, CatalogId(catalogId, TenantId(tenantKey)))

        GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()

        val contractVersion = GetLatestContractVersion(templateId = templateId).query()
        val dataExamples = contractVersion?.dataExamples ?: DataExamples.EMPTY

        val variant = GetVariant(variantId = variantId).query()
            ?: return ServerResponse.notFound().build()

        val versions = ListVersions(variantId = variantId).query()

        val activations = ListActivations(variantId = variantId).query()

        val activationsByVersion = activations.groupBy { it.versionKey.value }

        val variantSummary = VariantSummary(
            id = variant.id,
            title = variant.title,
            attributes = variant.attributes,
            isDefault = variant.isDefault,
            hasDraft = versions.any { it.status.name == "DRAFT" },
            publishedVersions = versions.filter { it.status.name == "PUBLISHED" }.map { it.id.value }.sorted(),
        )

        return request.htmx {
            fragment("templates/variant-versions", "content") {
                "tenantId" to tenantKey.value
                "catalogId" to catalogId.value
                "templateId" to templateKey
                "variant" to variantSummary
                "versions" to versions
                "dataExamples" to dataExamples
                "activationsByVersion" to activationsByVersion
                if (error != null) {
                    "error" to error
                }
            }
            onNonHtmx { redirect("/tenants/${tenantKey.value}/templates/$catalogId/$templateKey") }
        }
    }
}
