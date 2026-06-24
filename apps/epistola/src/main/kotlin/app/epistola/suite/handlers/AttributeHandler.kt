package app.epistola.suite.attributes

import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.attributeId
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.table.Column
import app.epistola.suite.htmx.table.ListViewState
import app.epistola.suite.htmx.table.PAGE_SIZES
import app.epistola.suite.htmx.table.dataTableResponse
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class AttributeHandler {

    private val sortableColumns = setOf("id", "name", "created")
    private val pageSizeOptions = PAGE_SIZES
    private val defaultSort = SortSpec("name", SortDirection.ASC)

    // Display Name and Allowed Values flex (width = null); the rest are fixed. See ADR 0007.
    private val columns = listOf(
        Column("ID", "id", width = "12rem"),
        Column("Catalog", width = "9rem"),
        Column("Display Name", "name"),
        Column("Allowed Values"),
        Column("Created", "created", width = "10rem"),
        Column("", width = "6rem"),
    )

    /**
     * Unified list endpoint: full page on a normal request, the data-table fragment on
     * an HTMX request. Search/sort/filter/paging state is read from (and pushed back to)
     * the query string, so the view is bookmarkable and survives a refresh.
     */
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val (pushUrl, model) = loadTableModel(request, tenantId)
        return request.dataTableResponse("attributes/list", "Attributes - Epistola", pushUrl, model)
    }

    /** Parse the list state, run the paged query + filter-bar data; shared by list and row actions. */
    private fun loadTableModel(request: ServerRequest, tenantId: TenantId): Pair<String, ModelBuilder.() -> Unit> {
        val basePath = "/tenants/${tenantId.key}/attributes"
        val state = ListViewState.from(
            request = request,
            basePath = basePath,
            sortable = sortableColumns,
            defaultSort = defaultSort,
            pageSizes = pageSizeOptions,
            filterNames = listOf("q", "catalog"),
        )
        val catalogs = ListCatalogs(tenantId.key).query()
        val paged = ListAttributeDefinitions(
            tenantId = tenantId,
            searchTerm = state.filter("q"),
            catalogKey = state.filter("catalog")?.let { CatalogKey.of(it) },
            sort = state.sort,
            page = state.pageRequest,
        ).query()
        val query = state.toQuery(paged.page)

        val model: ModelBuilder.() -> Unit = {
            "tenantId" to tenantId.key
            "catalogs" to catalogs
            "selectedCatalog" to (state.filter("catalog") ?: "")
            "columns" to columns
            "query" to query
            "paged" to paged
            "pageSizeOptions" to pageSizeOptions
        }
        return query.canonicalUrl() to model
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }
        val codeLists = ListCodeLists(tenantId, page = PageRequest.ALL).query().items
        return ServerResponse.ok().page("attributes/new") {
            "pageTitle" to "New Attribute - Epistola"
            "tenantId" to tenantId.key
            "catalogs" to catalogs
            "codeLists" to codeLists
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("catalog") {}
            field("constraintKind") {}
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
            }
            field("displayName") {
                required()
                maxLength(100)
            }
            field("allowedValues") {}
            field("codeList") {}
        }

        val catalogKey = CatalogKey.of(form.formData["catalog"]?.ifBlank { null } ?: return ServerResponse.badRequest().build())
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }
        val codeLists = ListCodeLists(tenantId, page = PageRequest.ALL).query().items

        if (form.hasErrors()) {
            return ServerResponse.ok().page("attributes/new") {
                "pageTitle" to "New Attribute - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "codeLists" to codeLists
                "formData" to form.formData
                "errors" to form.errors
            }
        }

        val attributeKey = AttributeKey.validateOrNull(form["slug"])
        if (attributeKey == null) {
            val errors = mapOf("slug" to "Invalid attribute ID format")
            return ServerResponse.ok().page("attributes/new") {
                "pageTitle" to "New Attribute - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "codeLists" to codeLists
                "formData" to form.formData
                "errors" to errors
            }
        }

        val displayName = form["displayName"]
        val constraintKind = form.formData["constraintKind"]?.ifBlank { null } ?: "free"
        val allowedValuesInput = request.params().getFirst("allowedValues")?.trim().orEmpty()
        val codeListSelection = request.params().getFirst("codeList")?.ifBlank { null }

        val (allowedValues, codeListId) = parseConstraint(constraintKind, allowedValuesInput, codeListSelection, tenantId)

        val result = form.executeOrFormError {
            CreateAttributeDefinition(
                id = AttributeId(attributeKey, CatalogId(catalogKey, tenantId)),
                displayName = displayName,
                allowedValues = allowedValues,
                codeListId = codeListId,
            ).execute()
        }

        if (result.hasErrors()) {
            return ServerResponse.ok().page("attributes/new") {
                "pageTitle" to "New Attribute - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "codeLists" to codeLists
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/attributes")
            .build()
    }

    fun editForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val attribute = GetAttributeDefinition(
            id = attributeId,
        ).query() ?: return ServerResponse.notFound().build()
        val codeLists = ListCodeLists(tenantId, page = PageRequest.ALL).query().items

        return request.htmx {
            fragment("attributes/list", "edit-attribute-form") {
                "tenantId" to tenantId.key
                "attribute" to attribute
                "codeLists" to codeLists
            }
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val form = request.form {
            field("displayName") {
                required()
                maxLength(100)
            }
            field("constraintKind") {}
            field("allowedValues") {}
            field("codeList") {}
        }

        val codeLists = ListCodeLists(tenantId, page = PageRequest.ALL).query().items

        if (form.hasErrors()) {
            val attribute = GetAttributeDefinition(
                id = attributeId,
            ).query() ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("attributes/list", "edit-attribute-form") {
                    "tenantId" to tenantId.key
                    "attribute" to attribute
                    "codeLists" to codeLists
                    "error" to form.errors.values.firstOrNull()
                }
                retarget("#edit-attribute-dialog-body")
                reswap(HxSwap.INNER_HTML)
            }
        }

        val displayName = form["displayName"]
        val constraintKind = form.formData["constraintKind"]?.ifBlank { null } ?: "free"
        val allowedValuesInput = request.params().getFirst("allowedValues")?.trim().orEmpty()
        val codeListSelection = request.params().getFirst("codeList")?.ifBlank { null }
        val (allowedValues, codeListId) = parseConstraint(constraintKind, allowedValuesInput, codeListSelection, tenantId)

        try {
            UpdateAttributeDefinition(
                id = attributeId,
                displayName = displayName,
                allowedValues = allowedValues,
                codeListId = codeListId,
            ).execute() ?: return ServerResponse.notFound().build()
        } catch (e: Exception) {
            val attribute = GetAttributeDefinition(
                id = attributeId,
            ).query() ?: return ServerResponse.notFound().build()
            return request.htmx {
                fragment("attributes/list", "edit-attribute-form") {
                    "tenantId" to tenantId.key
                    "attribute" to attribute
                    "codeLists" to codeLists
                    "error" to (e.message ?: "Update failed")
                }
                retarget("#edit-attribute-dialog-body")
                reswap(HxSwap.INNER_HTML)
            }
        }

        // Re-render the whole table (the edit form targets #data-table-container) and close
        // the dialog. The PATCH carries no list state, so this resets to the default view.
        val (_, model) = loadTableModel(request, tenantId)
        return request.htmx {
            fragment("attributes/list", "data-table-fragment", model)
            trigger("closeDialog")
            onNonHtmx { redirect("/tenants/${tenantId.key}/attributes") }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val attributeId = request.attributeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        DeleteAttributeDefinition(
            id = attributeId,
        ).execute()

        // Re-render the whole table after delete; resets to the default view.
        val (_, model) = loadTableModel(request, tenantId)
        return request.htmx {
            fragment("attributes/list", "data-table-fragment", model)
            onNonHtmx { redirect("/tenants/${tenantId.key}/attributes") }
        }
    }

    private fun parseAllowedValues(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return input.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Translates the form's constraint-kind selection into the
     * `(allowedValues, codeListId)` pair expected by the CRUD commands.
     *
     * `codeListSelection` is the form's `codeList` field — encoded as
     * "<catalog>/<slug>" — so we can pack the user's choice across catalogs
     * into a single `<select>`. Returns `null` for `codeListId` on invalid
     * input; the command's own validation will then reject blank slugs etc.
     */
    private fun parseConstraint(
        constraintKind: String,
        allowedValuesInput: String,
        codeListSelection: String?,
        tenantId: TenantId,
    ): Pair<List<String>, CodeListId?> = when (constraintKind) {
        "inline" -> parseAllowedValues(allowedValuesInput) to null
        "code-list" -> emptyList<String>() to parseCodeListSelection(codeListSelection, tenantId)
        else -> emptyList<String>() to null // free format
    }

    private fun parseCodeListSelection(selection: String?, tenantId: TenantId): CodeListId? {
        val (catalogPart, slugPart) = selection?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return null
        val catalogKey = CatalogKey.validateOrNull(catalogPart) ?: return null
        val slug = CodeListKey.validateOrNull(slugPart) ?: return null
        return CodeListId(slug, CatalogId(catalogKey, tenantId))
    }
}
