package app.epistola.suite.templates

import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
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
    val pdfaEnabled: Boolean? = null,
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
    private val jsonSchemaValidator: JsonSchemaValidator,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templates = ListTemplateSummaries(tenantId = tenantId).query()
        return ServerResponse.ok().page("templates/list") {
            "pageTitle" to "Document Templates - Epistola"
            "tenantId" to tenantId.key
            "templates" to templates
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val templates = ListTemplateSummaries(tenantId = tenantId, searchTerm = searchTerm).query()
        return request.htmx {
            fragment("templates/list", "rows") {
                "tenantId" to tenantId.key
                "templates" to templates
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/templates") }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        return ServerResponse.ok().page("templates/new") {
            "pageTitle" to "New Template - Epistola"
            "tenantId" to tenantId.key
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = CatalogKey.DEFAULT // TODO: catalog selection in create form

        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
            }
            field("name") {
                required()
            }
        }

        if (form.hasErrors()) {
            return ServerResponse.ok().page("templates/new") {
                "pageTitle" to "New Template - Epistola"
                "tenantId" to tenantId.key
                "catalogId" to catalogId
                "formData" to form.formData
                "errors" to form.errors
            }
        }

        val templateKey = TemplateKey.validateOrNull(form["slug"])
        if (templateKey == null) {
            val errors = mapOf("slug" to "Invalid template ID format")
            return ServerResponse.ok().page("templates/new") {
                "pageTitle" to "New Template - Epistola"
                "tenantId" to tenantId.key
                "catalogId" to catalogId
                "formData" to form.formData
                "errors" to errors
            }
        }

        val name = form["name"]

        val result = form.executeOrFormError {
            CreateDocumentTemplate(
                id = TemplateId(templateKey, CatalogId.default(tenantId)),
                name = name,
            ).execute()
        }

        if (result.hasErrors()) {
            return ServerResponse.ok().page("templates/new") {
                "pageTitle" to "New Template - Epistola"
                "tenantId" to tenantId.key
                "catalogId" to catalogId
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/templates/$catalogId/$templateKey")
            .build()
    }

    fun editor(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        // Get all editor context in a single query
        val context = GetEditorContext(variantId = variantId).query()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok().render(
            "templates/editor",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalogId,
                "templateId" to templateId.key,
                "variantId" to variantId.key,
                "templateName" to context.templateName,
                "variantAttributes" to context.variantAttributes,
                "templateModel" to context.templateModel,
                "dataExamples" to context.dataExamples,
                "dataModel" to context.dataModel,
            ),
        )
    }

    fun get(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(id = templateId).query()
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
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateTemplateRequest::class.java)

        return try {
            val result = UpdateDocumentTemplate(
                id = templateId,
                name = updateRequest.name,
                themeId = updateRequest.themeId?.let { ThemeKey.of(it) },
                clearThemeId = updateRequest.clearThemeId,
                dataModel = updateRequest.dataModel,
                dataExamples = updateRequest.dataExamples,
                pdfaEnabled = updateRequest.pdfaEnabled,
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
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateTemplateRequest::class.java)

        val result = UpdateDocumentTemplate(
            id = templateId,
            themeId = updateRequest.themeId?.let { ThemeKey.of(it) },
            clearThemeId = updateRequest.clearThemeId,
        ).execute() ?: return ServerResponse.notFound().build()

        // Load available themes for the fragment
        val themes = ListThemes(tenantId = tenantId).query()

        return request.htmx {
            fragment("templates/detail", "theme-section") {
                "tenantId" to tenantId.key
                "catalogId" to catalogId
                "template" to result.template
                "themes" to themes
            }
            onNonHtmx {
                redirect("/tenants/${tenantId.key}/templates/$catalogId/${templateId.key}")
            }
        }
    }

    /**
     * Validates a schema against existing data examples without persisting.
     * Returns migration suggestions for incompatible examples.
     */
    fun validateSchema(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val validateRequest = objectMapper.readValue(body, ValidateSchemaRequest::class.java)

        // Get existing template to use its examples if not provided in request
        val template = GetDocumentTemplate(id = templateId).query()
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
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val exampleId = request.pathVariable("exampleId")

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateDataExampleRequest::class.java)

        return try {
            val result = UpdateDataExample(
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
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val exampleId = request.pathVariable("exampleId")

        val result = DeleteDataExample(
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
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()

        val variants = GetVariantSummaries(templateId = templateId).query()

        // Load available themes for theme selection
        val themes = ListThemes(tenantId = tenantId).query()

        // Load attribute definitions for variant attribute selects
        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()

        return ServerResponse.ok().page("templates/detail") {
            "pageTitle" to "${template.name} - Epistola"
            "tenantId" to tenantId.key
            "catalogId" to catalogId
            "template" to template
            "variants" to variants
            "themes" to themes
            "attributeDefinitions" to attributeDefinitions
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        DeleteDocumentTemplate(id = templateId).execute()

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/templates")
            .build()
    }
}
