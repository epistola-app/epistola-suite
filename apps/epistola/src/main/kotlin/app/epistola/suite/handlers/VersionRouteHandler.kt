package app.epistola.suite.templates

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
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.commands.versions.VersionStillActiveException
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.activations.ListActivations
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.versions.ListVersions
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

    fun updateDraft(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        // Parse JSON body to get templateModel
        val body = request.body(String::class.java)
        val jsonNode = objectMapper.readTree(body)
        val templateModelJson = jsonNode.get("templateModel")
        val templateModel = objectMapper.treeToValue(
            templateModelJson,
            app.epistola.suite.templates.model.TemplateDocument::class.java,
        )

        // Execute update command
        UpdateDraft(
            variantId = variantId,
            templateModel = templateModel,
        ).execute()

        // Return minimal success response (UI doesn't need full DTO)
        return ServerResponse.ok()
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(mapOf("success" to true))
    }

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
        val templateId = TemplateId(templateKey, CatalogId.default(TenantId(tenantKey)))

        val template = GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()

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
                "templateId" to templateKey
                "variant" to variantSummary
                "versions" to versions
                "dataExamples" to template.dataExamples
                "activationsByVersion" to activationsByVersion
                if (error != null) {
                    "error" to error
                }
            }
            onNonHtmx { redirect("/tenants/${tenantKey.value}/templates/$catalogId/$templateKey") }
        }
    }
}
