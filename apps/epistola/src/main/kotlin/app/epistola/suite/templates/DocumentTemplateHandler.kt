package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDataExample
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDataExample
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.templates.queries.ListTemplateSummaries
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.queries.versions.GetPreviewContext
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.MigrationSuggestion
import app.epistola.suite.templates.validation.SchemaCompatibilityResult
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.themes.queries.ListThemes
import app.epistola.suite.validation.ValidationException
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Request body for updating a document template's metadata.
 * Note: templateModel is now stored in TemplateVersion and updated separately.
 *
 * @property forceUpdate When true, validation warnings don't block the update
 * @property themeId The default theme ID for this template (optional)
 * @property clearThemeId When true, removes the default theme assignment
 */
data class UpdateTemplateRequest(
    val name: String? = null,
    val themeId: String? = null,
    val clearThemeId: Boolean = false,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExample>? = null,
    val forceUpdate: Boolean = false,
)

/**
 * Request body for validating schema compatibility with existing examples.
 */
data class ValidateSchemaRequest(
    val schema: ObjectNode,
    val examples: List<DataExample>? = null,
)

/**
 * Request body for updating a single data example.
 *
 * @property forceUpdate When true, validation warnings don't block the update
 */
data class UpdateDataExampleRequest(
    val name: String? = null,
    val data: ObjectNode? = null,
    val forceUpdate: Boolean = false,
)

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
 * Response for schema validation preview.
 */
data class ValidateSchemaResponse(
    val compatible: Boolean,
    val errors: List<ValidationError>,
    val migrations: List<MigrationSuggestion>,
) {
    companion object {
        fun from(result: SchemaCompatibilityResult) = ValidateSchemaResponse(
            compatible = result.compatible,
            errors = result.errors,
            migrations = result.migrations,
        )
    }
}

@Component
class DocumentTemplateHandler(
    private val objectMapper: ObjectMapper,
    private val buildProperties: BuildProperties?,
    private val generationService: GenerationService,
    private val jsonSchemaValidator: JsonSchemaValidator,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templates = ListTemplateSummaries(tenantId = TenantId.of(tenantId)).query()
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
        val templates = ListTemplateSummaries(tenantId = TenantId.of(tenantId), searchTerm = searchTerm).query()
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
        val schema = request.params().getFirst("schema")?.trim()?.takeIf { it.isNotEmpty() }

        try {
            val command = CreateDocumentTemplate(id = TemplateId.generate(), tenantId = TenantId.of(tenantId), name = name, schema = schema)
            command.execute()
        } catch (e: ValidationException) {
            val formData = mapOf("name" to name, "schema" to (schema ?: ""))
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

        val templates = ListTemplateSummaries(tenantId = TenantId.of(tenantId)).query()
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
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val variantId = parseUUID(request.pathVariable("variantId"))
            ?: return ServerResponse.badRequest().build()

        // Get all editor context in a single query
        val context = GetEditorContext(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).query() ?: return ServerResponse.notFound().build()

        // Create draft if none exists (CreateVersion creates default template model)
        val templateModel = context.templateModel ?: run {
            val draft = CreateVersion(
                id = VersionId.generate(),
                tenantId = TenantId.of(tenantId),
                templateId = TemplateId.of(templateId),
                variantId = VariantId.of(variantId),
            ).execute() ?: return ServerResponse.status(500).build()
            draft.templateModel ?: return ServerResponse.status(500).build()
        }

        // Convert dataExamples to plain maps for proper Thymeleaf/JS serialization
        val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}
        val dataExamplesForJs = try {
            context.dataExamples.map { example ->
                mapOf(
                    "id" to example.id,
                    "name" to example.name,
                    "data" to objectMapper.convertValue(example.data, mapTypeRef),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Convert dataModel to plain map for proper Thymeleaf/JS serialization
        val dataModelForJs = try {
            context.dataModel?.let { objectMapper.convertValue(it, mapTypeRef) }
        } catch (e: Exception) {
            null
        }

        // Convert themes to plain maps for JS
        val themesForJs = context.themes.map { theme ->
            mapOf("id" to theme.id, "name" to theme.name, "description" to theme.description)
        }

        // Convert default theme to plain map for JS
        val defaultThemeForJs = context.defaultTheme?.let { theme ->
            mapOf("id" to theme.id, "name" to theme.name, "description" to theme.description)
        }

        return ServerResponse.ok().render(
            "templates/editor",
            mapOf(
                "tenantId" to tenantId,
                "templateId" to templateId,
                "variantId" to variantId,
                "templateName" to context.templateName,
                "variantTags" to context.variantTags,
                "templateModel" to templateModel,
                "dataExamples" to dataExamplesForJs,
                "dataModel" to dataModelForJs,
                "themes" to themesForJs,
                "defaultTheme" to defaultThemeForJs,
                "appVersion" to (buildProperties?.version ?: "dev"),
                "appName" to (buildProperties?.name ?: "Epistola"),
            ),
        )
    }

    fun get(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(id)).query()
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
        val id = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateTemplateRequest::class.java)

        // Note: templateModel updates should go through version commands now
        return try {
            val result = UpdateDocumentTemplate(
                tenantId = TenantId.of(tenantId),
                id = TemplateId.of(id),
                name = updateRequest.name,
                themeId = updateRequest.themeId?.let { ThemeId.of(UUID.fromString(it)) },
                clearThemeId = updateRequest.clearThemeId,
                dataModel = updateRequest.dataModel,
                dataExamples = updateRequest.dataExamples,
                forceUpdate = updateRequest.forceUpdate,
            ).execute() ?: return ServerResponse.notFound().build()

            val updated = result.template
            val responseBody = mutableMapOf<String, Any?>(
                "id" to updated.id,
                "name" to updated.name,
                "dataModel" to updated.dataModel,
                "dataExamples" to updated.dataExamples,
                "createdAt" to updated.createdAt,
                "lastModified" to updated.lastModified,
            )

            // Include warnings if present (when forceUpdate was used)
            if (result.warnings.isNotEmpty()) {
                responseBody["warnings"] = result.warnings
            }

            ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseBody)
        } catch (e: DataModelValidationException) {
            ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("errors" to e.validationErrors))
        }
    }

    /**
     * Validates a schema against existing data examples without persisting.
     * Returns migration suggestions for incompatible examples.
     */
    /**
     * Updates the template's default theme via HTMX.
     * Returns the theme-section fragment for seamless UI updates.
     */
    fun updateTheme(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateTemplateRequest::class.java)

        val result = UpdateDocumentTemplate(
            tenantId = TenantId.of(tenantId),
            id = TemplateId.of(id),
            themeId = updateRequest.themeId?.let { ThemeId.of(UUID.fromString(it)) },
            clearThemeId = updateRequest.clearThemeId,
        ).execute() ?: return ServerResponse.notFound().build()

        // Load available themes for the fragment
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()

        return request.htmx {
            fragment("templates/detail", "theme-section") {
                "tenantId" to tenantId
                "template" to result.template
                "themes" to themes
            }
            onNonHtmx {
                redirect("/tenants/$tenantId/templates/$id")
            }
        }
    }

    /**
     * Validates a schema against existing data examples without persisting.
     * Returns migration suggestions for incompatible examples.
     */
    fun validateSchema(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val validateRequest = objectMapper.readValue(body, ValidateSchemaRequest::class.java)

        // Get existing template to use its examples if not provided in request
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(id)).query()
            ?: return ServerResponse.notFound().build()

        val examplesToValidate = validateRequest.examples ?: template.dataExamples

        val result = jsonSchemaValidator.analyzeCompatibility(
            schema = validateRequest.schema,
            examples = examplesToValidate,
        )

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(ValidateSchemaResponse.from(result))
    }

    /**
     * Updates a single data example within a template.
     * Only validates the single example being updated against the schema.
     */
    fun updateDataExample(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val exampleId = request.pathVariable("exampleId")

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateDataExampleRequest::class.java)

        return try {
            val result = UpdateDataExample(
                tenantId = TenantId.of(tenantId),
                templateId = TemplateId.of(templateId),
                exampleId = exampleId,
                name = updateRequest.name,
                data = updateRequest.data,
                forceUpdate = updateRequest.forceUpdate,
            ).execute() ?: return ServerResponse.notFound().build()

            val responseBody = mutableMapOf<String, Any?>(
                "id" to result.example.id,
                "name" to result.example.name,
                "data" to result.example.data,
            )

            if (result.warnings.isNotEmpty()) {
                responseBody["warnings"] = result.warnings
            }

            ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseBody)
        } catch (e: DataModelValidationException) {
            ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("errors" to e.validationErrors))
        }
    }

    /**
     * Deletes a single data example from a template.
     */
    fun deleteDataExample(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val exampleId = request.pathVariable("exampleId")

        val result = DeleteDataExample(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            exampleId = exampleId,
        ).execute() ?: return ServerResponse.notFound().build()

        return if (result.deleted) {
            ServerResponse.noContent().build()
        } else {
            ServerResponse.notFound().build()
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(id)).query()
            ?: return ServerResponse.notFound().build()

        val variants = GetVariantSummaries(templateId = TemplateId.of(id)).query()

        // Load available themes for theme selection
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()

        return ServerResponse.ok().render(
            "templates/detail",
            mapOf(
                "tenantId" to tenantId,
                "template" to template,
                "variants" to variants,
                "themes" to themes,
                "selectedVariant" to null,
                "versions" to emptyList<Any>(),
            ),
        )
    }

    fun variantDetail(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val variantId = parseUUID(request.pathVariable("variantId"))
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).query()
            ?: return ServerResponse.notFound().build()

        val variants = GetVariantSummaries(templateId = TemplateId.of(templateId)).query()

        val variant = GetVariant(tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId)).query()
            ?: return ServerResponse.notFound().build()

        val versions = ListVersions(tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId)).query()

        // Load available themes for theme selection
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()

        // Create a variant summary for the selected variant
        val selectedVariantSummary = variants.find { it.id == VariantId.of(variantId) }
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
                "themes" to themes,
                "selectedVariant" to selectedVariantSummary,
                "versions" to versions,
            ),
        )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()

        DeleteDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(id)).execute()

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantId/templates")
            .build()
    }

    fun createVariant(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val tagsInput = request.params().getFirst("tags")?.trim().orEmpty()
        val tags = parseTags(tagsInput)

        CreateVariant(
            id = VariantId.generate(),
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            title = title,
            description = description,
            tags = tags,
        ).execute()

        val variants = GetVariantSummaries(templateId = TemplateId.of(templateId)).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).query()
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
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val variantId = parseUUID(request.pathVariable("variantId"))
            ?: return ServerResponse.badRequest().build()

        DeleteVariant(tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId)).execute()

        val variants = GetVariantSummaries(templateId = TemplateId.of(templateId)).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).query()
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
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val variantId = parseUUID(request.pathVariable("variantId"))
            ?: return ServerResponse.badRequest().build()

        CreateVersion(id = VersionId.generate(), tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId)).execute()

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    fun publishVersion(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val variantId = parseUUID(request.pathVariable("variantId"))
            ?: return ServerResponse.badRequest().build()
        val versionId = parseUUID(request.pathVariable("versionId"))
            ?: return ServerResponse.badRequest().build()

        PublishVersion(tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId), versionId = VersionId.of(versionId)).execute()

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    fun archiveVersion(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val variantId = parseUUID(request.pathVariable("variantId"))
            ?: return ServerResponse.badRequest().build()
        val versionId = parseUUID(request.pathVariable("versionId"))
            ?: return ServerResponse.badRequest().build()

        ArchiveVersion(tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId), versionId = VersionId.of(versionId)).execute()

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    /**
     * Generates a PDF preview of a variant's draft version.
     * Streams the PDF directly to the response.
     *
     * If `templateModel` is provided in the request body, it will be used for rendering
     * instead of fetching from the database. This enables live preview of unsaved changes.
     */
    fun preview(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templateId = parseUUID(request.pathVariable("id"))
            ?: return ServerResponse.badRequest().build()
        val variantId = parseUUID(request.pathVariable("variantId"))
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
        val validationResult = generationService.validatePreviewData(tenantId, templateId, data)
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
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).query() ?: return ServerResponse.notFound().build()

        // Resolve the template model - either from request (live preview) or from draft
        val templateModel = if (previewRequest.templateModel != null) {
            generationService.convertTemplateModel(previewRequest.templateModel)
        } else {
            previewContext.draftTemplateModel ?: return ServerResponse.notFound().build()
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .build { _, response ->
                generationService.renderPdf(TenantId.of(tenantId), templateModel, data, response.outputStream, previewContext.templateThemeId)
                response.outputStream.flush()
                null // Return null to indicate no view to render
            }
    }

    private fun returnVersionsFragment(
        request: ServerRequest,
        tenantId: UUID,
        templateId: UUID,
        variantId: UUID,
    ): ServerResponse {
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).query()
            ?: return ServerResponse.notFound().build()

        val variant = GetVariant(tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId)).query()
            ?: return ServerResponse.notFound().build()

        val versions = ListVersions(tenantId = TenantId.of(tenantId), templateId = TemplateId.of(templateId), variantId = VariantId.of(variantId)).query()

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

    private fun resolveTenantId(request: ServerRequest): UUID = parseUUID(request.pathVariable("tenantId"))
        ?: throw IllegalArgumentException("Invalid tenant ID")

    private fun parseUUID(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (_: Exception) {
        null
    }
}
