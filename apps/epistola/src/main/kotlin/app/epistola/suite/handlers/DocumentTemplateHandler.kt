package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDataExample
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDataExample
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.templates.queries.ListTemplateSummaries
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.MigrationSuggestion
import app.epistola.suite.templates.validation.SchemaCompatibilityResult
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.themes.queries.ListThemes
import app.epistola.suite.validation.ValidationException
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

/**
 * Handles core template CRUD operations and template detail views.
 * Variant operations are handled by [VariantRouteHandler].
 * Version lifecycle operations are handled by [VersionRouteHandler].
 * Preview generation is handled by [TemplatePreviewHandler].
 */
@Component
class DocumentTemplateHandler(
    private val objectMapper: ObjectMapper,
    private val buildProperties: BuildProperties?,
    private val jsonSchemaValidator: JsonSchemaValidator,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templates = ListTemplateSummaries(tenantId = TenantId.of(tenantId)).query()
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "templates/list",
                "pageTitle" to "Document Templates - Epistola",
                "tenantId" to tenantId,
                "templates" to templates,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
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

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "templates/new",
                "pageTitle" to "New Template - Epistola",
                "tenantId" to tenantId,
            ),
        )
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val slug = request.params().getFirst("slug")?.trim().orEmpty()
        val name = request.params().getFirst("name")?.trim().orEmpty()
        val schema = request.params().getFirst("schema")?.trim()?.takeIf { it.isNotEmpty() }

        fun renderFormWithErrors(errors: Map<String, String>): ServerResponse {
            val formData = mapOf("slug" to slug, "name" to name, "schema" to (schema ?: ""))
            return ServerResponse.ok().render(
                "layout/shell",
                mapOf(
                    "contentView" to "templates/new",
                    "pageTitle" to "New Template - Epistola",
                    "tenantId" to tenantId,
                    "formData" to formData,
                    "errors" to errors,
                ),
            )
        }

        // Validate slug format
        val templateId = TemplateId.validateOrNull(slug)
        if (templateId == null) {
            return renderFormWithErrors(
                mapOf("slug" to "Invalid template ID format. Must be 3-50 lowercase characters, starting with a letter."),
            )
        }

        try {
            CreateDocumentTemplate(
                id = templateId,
                tenantId = TenantId.of(tenantId),
                name = name,
                schema = schema,
            ).execute()
        } catch (e: ValidationException) {
            return renderFormWithErrors(mapOf(e.field to e.message))
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantId/templates/$slug")
            .build()
    }

    fun editor(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        // Get all editor context in a single query
        val context = GetEditorContext(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
        ).query() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok().render(
            "templates/editor",
            mapOf(
                "tenantId" to tenantId,
                "templateId" to templateId,
                "variantId" to variantId,
                "templateName" to context.templateName,
                "variantTags" to context.variantTags,
                "templateModel" to context.templateModel,
                "dataExamples" to context.dataExamples,
                "dataModel" to context.dataModel,
                "appVersion" to (buildProperties?.version ?: "dev"),
                "appName" to (buildProperties?.name ?: "Epistola"),
            ),
        )
    }

    fun get(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val idStr = request.pathVariable("id")
        val id = TemplateId.validateOrNull(idStr)
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = id).query()
            ?: return ServerResponse.notFound().build()

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
        val tenantId = request.pathVariable("tenantId")
        val idStr = request.pathVariable("id")
        val id = TemplateId.validateOrNull(idStr)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateTemplateRequest::class.java)

        return try {
            val result = UpdateDocumentTemplate(
                tenantId = TenantId.of(tenantId),
                id = id,
                name = updateRequest.name,
                themeId = updateRequest.themeId?.let { ThemeId.of(it) },
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
     * Updates the template's default theme via HTMX.
     * Returns the theme-section fragment for seamless UI updates.
     */
    fun updateTheme(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val idStr = request.pathVariable("id")
        val id = TemplateId.validateOrNull(idStr)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateTemplateRequest::class.java)

        val result = UpdateDocumentTemplate(
            tenantId = TenantId.of(tenantId),
            id = id,
            themeId = updateRequest.themeId?.let { ThemeId.of(it) },
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
        val tenantId = request.pathVariable("tenantId")
        val idStr = request.pathVariable("id")
        val id = TemplateId.validateOrNull(idStr)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val validateRequest = objectMapper.readValue(body, ValidateSchemaRequest::class.java)

        // Get existing template to use its examples if not provided in request
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = id).query()
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
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val exampleId = request.pathVariable("exampleId")

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateDataExampleRequest::class.java)

        return try {
            val result = UpdateDataExample(
                tenantId = TenantId.of(tenantId),
                templateId = templateId,
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
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val exampleId = request.pathVariable("exampleId")

        val result = DeleteDataExample(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            exampleId = exampleId,
        ).execute() ?: return ServerResponse.notFound().build()

        return if (result.deleted) {
            ServerResponse.noContent().build()
        } else {
            ServerResponse.notFound().build()
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val idStr = request.pathVariable("id")
        val id = TemplateId.validateOrNull(idStr)
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = id).query()
            ?: return ServerResponse.notFound().build()

        val variants = GetVariantSummaries(templateId = id).query()

        // Load available themes for theme selection
        val themes = ListThemes(tenantId = TenantId.of(tenantId)).query()

        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "templates/detail",
                "pageTitle" to "${template.name} - Epistola",
                "tenantId" to tenantId,
                "template" to template,
                "variants" to variants,
                "themes" to themes,
            ),
        )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val idStr = request.pathVariable("id")
        val id = TemplateId.validateOrNull(idStr)
            ?: return ServerResponse.badRequest().build()

        DeleteDocumentTemplate(tenantId = TenantId.of(tenantId), id = id).execute()

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantId/templates")
            .build()
    }
}
