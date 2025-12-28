package app.epistola.suite.templates

import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListTemplateSummaries
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.variants.VariantSummary
import app.epistola.suite.variants.commands.CreateVariant
import app.epistola.suite.variants.commands.DeleteVariant
import app.epistola.suite.variants.queries.GetVariant
import app.epistola.suite.variants.queries.GetVariantSummaries
import app.epistola.suite.versions.commands.ArchiveVersion
import app.epistola.suite.versions.commands.CreateVersion
import app.epistola.suite.versions.commands.PublishVersion
import app.epistola.suite.versions.queries.GetDraft
import app.epistola.suite.versions.queries.ListVersions
import org.springframework.boot.info.BuildProperties
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
    private val buildProperties: BuildProperties?,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templates = mediator.query(ListTemplateSummaries(tenantId = tenantId))
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
        val templates = mediator.query(ListTemplateSummaries(tenantId = tenantId, searchTerm = searchTerm))
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

        val templates = mediator.query(ListTemplateSummaries(tenantId = tenantId))
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
        val templateId = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val variantId = request.pathVariable("variantId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = templateId))
            ?: return ServerResponse.notFound().build()

        val variant = mediator.query(GetVariant(tenantId = tenantId, templateId = templateId, variantId = variantId))
            ?: return ServerResponse.notFound().build()

        // Get or create draft for this variant
        var draft = mediator.query(GetDraft(tenantId = tenantId, templateId = templateId, variantId = variantId))
        if (draft == null) {
            // Create a new draft if one doesn't exist
            draft = mediator.send(CreateVersion(tenantId = tenantId, templateId = templateId, variantId = variantId))
                ?: return ServerResponse.status(500).build()
        }

        // Provide a default empty template structure if none exists
        val templateModel = draft.templateModel ?: createDefaultTemplateModel(template.name, variantId)

        return ServerResponse.ok().render(
            "templates/editor",
            mapOf(
                "tenantId" to tenantId,
                "templateId" to templateId,
                "variantId" to variantId,
                "templateName" to template.name,
                "variantTags" to variant.tags,
                "templateModel" to templateModel,
                "appVersion" to (buildProperties?.version ?: "dev"),
                "appName" to (buildProperties?.name ?: "Epistola"),
            ),
        )
    }

    private fun createDefaultTemplateModel(templateName: String, variantId: Long): Map<String, Any> = mapOf(
        "id" to "template-$variantId",
        "name" to templateName,
        "version" to 1,
        "pageSettings" to mapOf(
            "format" to "A4",
            "orientation" to "portrait",
            "margins" to mapOf(
                "top" to 20,
                "right" to 20,
                "bottom" to 20,
                "left" to 20,
            ),
        ),
        "blocks" to emptyList<Any>(),
    )

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

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = id))
            ?: return ServerResponse.notFound().build()

        val variants = mediator.query(GetVariantSummaries(templateId = id))

        return ServerResponse.ok().render(
            "templates/detail",
            mapOf(
                "tenantId" to tenantId,
                "template" to template,
                "variants" to variants,
                "selectedVariant" to null,
                "versions" to emptyList<Any>(),
            ),
        )
    }

    fun variantDetail(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val variantId = request.pathVariable("variantId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = templateId))
            ?: return ServerResponse.notFound().build()

        val variants = mediator.query(GetVariantSummaries(templateId = templateId))

        val variant = mediator.query(GetVariant(tenantId = tenantId, templateId = templateId, variantId = variantId))
            ?: return ServerResponse.notFound().build()

        val versions = mediator.query(ListVersions(tenantId = tenantId, templateId = templateId, variantId = variantId))

        // Create a variant summary for the selected variant
        val selectedVariantSummary = variants.find { it.id == variantId }
            ?: VariantSummary(
                id = variant.id,
                title = variant.title,
                tags = variant.tags,
                hasDraft = versions.any { it.status.name == "DRAFT" },
                publishedVersions = versions.filter { it.status.name == "PUBLISHED" }.mapNotNull { it.versionNumber }.sorted(),
            )

        return ServerResponse.ok().render(
            "templates/detail",
            mapOf(
                "tenantId" to tenantId,
                "template" to template,
                "variants" to variants,
                "selectedVariant" to selectedVariantSummary,
                "versions" to versions,
            ),
        )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        mediator.send(DeleteDocumentTemplate(tenantId = tenantId, id = id))

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantId/templates")
            .build()
    }

    fun createVariant(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val tagsInput = request.params().getFirst("tags")?.trim().orEmpty()
        val tags = parseTags(tagsInput)

        mediator.send(
            CreateVariant(
                tenantId = tenantId,
                templateId = templateId,
                title = title,
                description = description,
                tags = tags,
            ),
        )

        val variants = mediator.query(GetVariantSummaries(templateId = templateId))
        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = templateId))
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId") }
        }
    }

    fun deleteVariant(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val variantId = request.pathVariable("variantId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        mediator.send(DeleteVariant(tenantId = tenantId, templateId = templateId, variantId = variantId))

        val variants = mediator.query(GetVariantSummaries(templateId = templateId))
        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = templateId))
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId") }
        }
    }

    fun createDraft(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val variantId = request.pathVariable("variantId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        mediator.send(CreateVersion(tenantId = tenantId, templateId = templateId, variantId = variantId))

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    fun publishVersion(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val variantId = request.pathVariable("variantId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val versionId = request.pathVariable("versionId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        mediator.send(PublishVersion(tenantId = tenantId, templateId = templateId, variantId = variantId, versionId = versionId))

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    fun archiveVersion(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val variantId = request.pathVariable("variantId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()
        val versionId = request.pathVariable("versionId").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        mediator.send(ArchiveVersion(tenantId = tenantId, templateId = templateId, variantId = variantId, versionId = versionId))

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    private fun returnVersionsFragment(
        request: ServerRequest,
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ServerResponse {
        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = templateId))
            ?: return ServerResponse.notFound().build()

        val variant = mediator.query(GetVariant(tenantId = tenantId, templateId = templateId, variantId = variantId))
            ?: return ServerResponse.notFound().build()

        val versions = mediator.query(ListVersions(tenantId = tenantId, templateId = templateId, variantId = variantId))

        val selectedVariantSummary = VariantSummary(
            id = variant.id,
            title = variant.title,
            tags = variant.tags,
            hasDraft = versions.any { it.status.name == "DRAFT" },
            publishedVersions = versions.filter { it.status.name == "PUBLISHED" }.mapNotNull { it.versionNumber }.sorted(),
        )

        return request.htmx {
            fragment("templates/detail", "versions-section") {
                "tenantId" to tenantId
                "template" to template
                "selectedVariant" to selectedVariantSummary
                "versions" to versions
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId/variants/$variantId") }
        }
    }

    private fun parseTags(input: String): Map<String, String> {
        if (input.isBlank()) return emptyMap()
        return input.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .associate { line ->
                val parts = line.split("=", limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
    }

    private fun resolveTenantId(request: ServerRequest): Long = request.pathVariable("tenantId").toLongOrNull()
        ?: throw IllegalArgumentException("Invalid tenant ID")
}
