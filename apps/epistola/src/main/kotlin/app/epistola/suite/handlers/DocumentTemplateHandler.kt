package app.epistola.suite.templates

import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.handlers.buildAttributeDescriptors
import app.epistola.suite.handlers.buildAttributeOptions
import app.epistola.suite.handlers.decorateVariants
import app.epistola.suite.handlers.filterToUsedDescriptors
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.queryParamInt
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDataExample
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDataExample
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.templates.queries.ListTemplateSummaries
import app.epistola.suite.templates.queries.TemplateSort
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.MigrationSuggestion
import app.epistola.suite.templates.validation.SchemaCompatibilityResult
import app.epistola.suite.templates.validation.ValidationError
import app.epistola.suite.tenants.queries.GetTenant
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
 * Note: dataModel and dataExamples are now managed via contract versions.
 *
 * @property themeId The default theme ID for this template (optional)
 * @property clearThemeId When true, removes the default theme assignment
 */
data class UpdateTemplateRequest(
    val name: String? = null,
    val themeId: String? = null,
    val themeCatalogKey: String? = null,
    val clearThemeId: Boolean = false,
    val pdfaEnabled: Boolean? = null,
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
    private val detailHelper: TemplateDetailHelper,
    private val localeResolver: TenantLocaleResolver,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    private companion object {
        const val PAGE_SIZE = 10
    }

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query()
        val model = templateListModel(request, tenantId)
        return ServerResponse.ok().page("templates/list") {
            "pageTitle" to "Document Templates - Epistola"
            "catalogs" to catalogs
            model.forEach { (key, value) -> key to value }
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val model = templateListModel(request, tenantId)
        return request.htmx {
            fragment("templates/list", "table") {
                model.forEach { (key, value) -> key to value }
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/templates") }
        }
    }

    /**
     * Builds the shared model for the templates list table: the current page of
     * rows plus the sort and pagination state both `list` (full page) and
     * `search` (HTMX fragment swap) need to render identical controls.
     */
    private fun templateListModel(
        request: ServerRequest,
        tenantId: TenantId,
    ): Map<String, Any?> {
        val searchTerm = request.queryParam("q")?.ifBlank { null }
        val catalogFilter = request.queryParam("catalog")?.ifBlank { null }?.let { CatalogKey.of(it) }

        val sort = TemplateSort.fromParam(request.queryParam("sort"))
        val descending = when (request.queryParam("dir")) {
            "asc" -> false
            "desc" -> true
            else -> sort.defaultDescending
        }

        fun fetchPage(page: Int) = ListTemplateSummaries(
            tenantId = tenantId,
            searchTerm = searchTerm,
            catalogKey = catalogFilter,
            sort = sort,
            descending = descending,
            limit = PAGE_SIZE,
            offset = (page - 1) * PAGE_SIZE,
        ).query()

        // The page count and the rows come from one query (COUNT(*) OVER()), so
        // they can't disagree. An out-of-range page returns empty with no window
        // value, so when that happens we recover the real total from page 1 and
        // clamp the request down to the last page (preserving the old behavior).
        // Cap the page so (page - 1) * PAGE_SIZE can't overflow Int into a
        // negative SQL OFFSET (Postgres rejects it). A too-high page still
        // returns empty here and is clamped to the last page below.
        val requestedPage = request.queryParamInt("page", 1).coerceIn(1, Int.MAX_VALUE / PAGE_SIZE)
        var result = fetchPage(requestedPage)
        var page = requestedPage
        if (result.items.isEmpty() && requestedPage > 1) {
            val firstPage = fetchPage(1)
            page = requestedPage.coerceAtMost(pageCount(firstPage.total))
            result = if (page == 1) firstPage else fetchPage(page)
        }

        val total = result.total
        val totalPages = pageCount(total)

        return mapOf(
            "tenantId" to tenantId.key,
            "templates" to result.items,
            "selectedCatalog" to (catalogFilter?.value ?: ""),
            "searchTerm" to (searchTerm ?: ""),
            "sort" to sort.param,
            "sortDir" to if (descending) "desc" else "asc",
            // Per-column natural direction so a first click on an inactive column
            // honors its TemplateSort.defaultDescending instead of always going asc.
            "sortDefaultDirs" to TemplateSort.entries.associate { it.param to if (it.defaultDescending) "desc" else "asc" },
            "total" to total,
            "page" to page,
            "totalPages" to totalPages,
            "hasPrev" to (page > 1),
            "hasNext" to (page < totalPages),
            "prevPage" to (page - 1),
            "nextPage" to (page + 1),
        )
    }

    /** Number of pages for [total] rows at [PAGE_SIZE] per page (min 1). */
    private fun pageCount(total: Int): Int = if (total == 0) 1 else ((total + PAGE_SIZE - 1) / PAGE_SIZE)

    /** The catalogs a template can be created in — authored ones only. */
    private fun authoredCatalogs(tenantId: TenantId) = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }

    /**
     * The full-page list model, used by the newForm / create non-HTMX branches
     * so the list renders behind the embedded create dialog. `authoredCatalogs`
     * (the dialog's catalog `<select>` source) is threaded separately — the list
     * already puts *all* `catalogs` in the model for its filter, so the dialog
     * uses a distinct key to avoid rendering the wrong (non-authored) options.
     */
    private fun ModelBuilder.templatePageModel(request: ServerRequest, tenantId: TenantId) {
        "pageTitle" to "Document Templates - Epistola"
        "catalogs" to ListCatalogs(tenantId.key).query()
        templateListModel(request, tenantId).forEach { (key, value) -> key to value }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("templates/new", "dialog") {
                "tenantId" to tenantId.key
                "authoredCatalogs" to authoredCatalogs(tenantId)
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("templates/list") {
                    templatePageModel(request, tenantId)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs(tenantId)
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)

        val form = request.form {
            field("catalog") {}
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
                // Folds the old "invalid TemplateKey" branch into field validation
                // (same "Invalid template ID format" error) so all three failure
                // modes share one error path. Layered ON TOP of the pattern/length
                // rules above — strictly additive, never loosening them.
                asTemplateId()
            }
            field("name") {
                required()
            }
        }

        val catalogId = CatalogKey.of(form.formData["catalog"]?.ifBlank { null } ?: return ServerResponse.badRequest().build())

        // Field validation (incl. slug/TemplateKey) and the command-level failure
        // (duplicate slug, name length) both land as `errors` on the FormData, so
        // they share one error path — mirroring EnvironmentHandler.create.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateDocumentTemplate(
                    id = TemplateId(TemplateKey.validateOrNull(form["slug"])!!, CatalogId(catalogId, tenantId)),
                    name = form["name"],
                ).execute()
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form, not
                // the list) with inline errors + preserved values. `tenantId` and
                // `authoredCatalogs` are the prefill the form fragment needs to
                // rebuild its action URL and catalog <select>.
                dialogFieldErrors(
                    template = "templates/new",
                    fragmentName = "template-form",
                    formTarget = "#create-template-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                    "authoredCatalogs" to authoredCatalogs(tenantId)
                }
                onNonHtmx {
                    page(422, "templates/list") {
                        templatePageModel(request, tenantId)
                        "openDialog" to true
                        "authoredCatalogs" to authoredCatalogs(tenantId)
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: navigate to the newly created template's page. The dialog
        // disappears with the old page (HX-Redirect), so the list is not refreshed.
        val templateKey = TemplateKey.validateOrNull(form["slug"])!!
        val destination = "/tenants/${tenantId.key}/templates/$catalogId/$templateKey"
        return request.htmx {
            dialogRedirect(destination)
            onNonHtmx { redirect(destination) }
        }
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

        // Resolve the effective locale through the variant attribute → tenant
        // override → app default chain, so the editor's expression previews
        // format `$formatDate` the same way the PDF renderer will. The variant
        // attributes are already loaded above, so this is one extra GetTenant
        // query — cheap and worth it for WYSIWYG.
        val tenant = GetTenant(tenantId.key).query()
            ?: return ServerResponse.notFound().build()
        val resolvedLocale = localeResolver.resolve(tenant, context.variantAttributes)

        // Test-only seam (issue #418, Instance C): a `leaderTiming` query
        // param (JSON) lets UI tests shrink the editor's leader-hint TTLs so
        // their auto-hide behavior is deterministically observable instead of
        // raced against a wall clock. Absent in production → attribute omitted.
        val leaderTiming = request.queryParam("leaderTiming")?.ifBlank { null }

        return ServerResponse.ok().render(
            "templates/editor",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalogId.value,
                "templateId" to templateId.key,
                "variantId" to variantId.key,
                "templateName" to context.templateName,
                "variantAttributes" to context.variantAttributes,
                "templateModel" to context.templateModel,
                "dataExamples" to context.dataExamples,
                "dataModel" to context.dataModel,
                "leaderTiming" to leaderTiming,
                "resolvedLocale" to resolvedLocale,
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

        val contractVersion = GetLatestContractVersion(templateId = templateId).query()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to template.id,
                    "name" to template.name,
                    "dataModel" to contractVersion?.dataModel,
                    "dataExamples" to (contractVersion?.dataExamples ?: DataExamples.EMPTY),
                    "createdAt" to template.createdAt,
                    "updatedAt" to template.updatedAt,
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
            val updated = UpdateDocumentTemplate(
                id = templateId,
                name = updateRequest.name,
                themeId = updateRequest.themeId?.let { ThemeKey.of(it) },
                clearThemeId = updateRequest.clearThemeId,
                pdfaEnabled = updateRequest.pdfaEnabled,
            ).execute() ?: return ServerResponse.notFound().build()

            val responseBody = mutableMapOf<String, Any?>(
                "id" to updated.id,
                "name" to updated.name,
                "createdAt" to updated.createdAt,
                "updatedAt" to updated.updatedAt,
            )

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

        val updated = UpdateDocumentTemplate(
            id = templateId,
            themeId = updateRequest.themeId?.let { ThemeKey.of(it) },
            themeCatalogKey = updateRequest.themeCatalogKey?.let { app.epistola.suite.common.ids.CatalogKey.of(it) },
            clearThemeId = updateRequest.clearThemeId,
        ).execute() ?: return ServerResponse.notFound().build()

        // Load available themes for the fragment
        val themes = ListThemes(tenantId = tenantId).query()
        val themeCatalogs = themes.groupBy { it.catalogKey.value }

        return request.htmx {
            fragment("templates/detail/settings", "theme-section") {
                "tenantId" to tenantId.key
                "catalogId" to catalogId.value
                "template" to updated
                "themes" to themes
                "themeCatalogs" to themeCatalogs
                "editable" to (updated.catalogType == app.epistola.suite.catalog.CatalogType.AUTHORED)
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

        // Verify the template exists
        GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()

        val body = request.body(String::class.java)
        val validateRequest = objectMapper.readValue(body, ValidateSchemaRequest::class.java)

        // Get existing contract version to use its examples if not provided in request
        val contractVersion = GetLatestContractVersion(templateId = templateId).query()

        val examplesToValidate = validateRequest.examples ?: contractVersion?.dataExamples ?: DataExamples.EMPTY

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
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        val variants = GetVariantSummaries(templateId = ctx.templateId).query()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = ctx.templateId.tenantId).query()
        val attributeDescriptors = buildAttributeDescriptors(attributeDefinitions)
        val attributeOptions = buildAttributeOptions(attributeDefinitions)
        val decoratedVariants = decorateVariants(variants, attributeDescriptors)
        val usedDescriptors = filterToUsedDescriptors(attributeDescriptors, decoratedVariants)
        val contractVersion = GetLatestContractVersion(templateId = ctx.templateId).query()

        return detailHelper.renderDetailPage(
            ctx,
            "variants",
            mapOf(
                "variants" to decoratedVariants,
                "attributeDescriptors" to attributeDescriptors,
                "usedAttributeDescriptors" to usedDescriptors,
                "attributeDefinitions" to attributeDefinitions,
                "attributeOptions" to attributeOptions,
                "contractDataExamples" to contractVersion?.dataExamples,
            ),
        )
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
