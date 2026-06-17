package app.epistola.suite.handlers

import app.epistola.suite.attributes.codelists.commands.CodeListInUseException
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.DeleteCodeList
import app.epistola.suite.attributes.codelists.commands.RefreshCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.codeListId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.htmxCurrentUrl
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.urlWithCreateParam
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
class CodeListHandler(
    private val objectMapper: ObjectMapper,
) {

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogFilter = request.queryParam("catalog")?.ifBlank { null }?.let { CatalogKey.of(it) }
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val catalogs = ListCatalogs(tenantId.key).query()
        val codeLists = ListCodeLists(tenantId = tenantId, catalogKey = catalogFilter).query()
        val createOpen = request.queryParam("create") != null
        return ServerResponse.ok().page("code-lists/list") {
            "pageTitle" to "Code lists - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "catalogs" to catalogs
            "selectedCatalog" to (catalogFilter?.value ?: "")
            "codeLists" to codeLists
            "createOpen" to createOpen
            "authoredCatalogs" to catalogs.filter { it.type == CatalogType.AUTHORED }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }
        // HTMX requests load the dialog into #dialog-host; a direct GET redirects
        // to the list (dialog-only — no full-page fallback).
        return request.htmx {
            fragment("code-lists/new", "createDialog") {
                "tenantId" to tenantId.key
                "catalogs" to catalogs
            }
            pushUrl(urlWithCreateParam(request.htmxCurrentUrl, "/tenants/${tenantId.key}/code-lists"))
            onNonHtmx { redirect("/tenants/${tenantId.key}/code-lists") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("catalog") { required() }
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(64)
            }
            field("displayName") {
                required()
                maxLength(100)
            }
            field("description") { maxLength(2000) }
            field("sourceType") { required() }
            field("sourceUrl") { maxLength(2000) }
            field("entriesJson") {}
        }

        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }

        if (form.hasErrors()) {
            return renderNew(request, form, catalogs, tenantId.key)
        }

        val catalogKey = CatalogKey.validateOrNull(form["catalog"])
            ?: return renderNewWithError(request, form, catalogs, tenantId.key, "catalog", "Invalid catalog")
        val slug = CodeListKey.validateOrNull(form["slug"])
            ?: return renderNewWithError(request, form, catalogs, tenantId.key, "slug", "Invalid slug format")
        val sourceType = runCatching { CodeListSource.valueOf(form["sourceType"]) }.getOrNull()
            ?: return renderNewWithError(request, form, catalogs, tenantId.key, "sourceType", "Unknown source type")

        val entries = if (sourceType == CodeListSource.INLINE) {
            parseInlineEntries(request.params().getFirst("entriesJson").orEmpty())
        } else {
            emptyList()
        }

        val result = form.executeOrFormError {
            CreateCodeList(
                id = CodeListId(slug, CatalogId(catalogKey, tenantId)),
                displayName = form["displayName"],
                description = form.formData["description"]?.ifBlank { null },
                sourceType = sourceType,
                sourceUrl = form.formData["sourceUrl"]?.ifBlank { null },
                authType = AuthType.NONE,
                entries = entries,
            ).execute()
        }

        if (result.hasErrors()) {
            return renderNew(request, result, catalogs, tenantId.key)
        }

        val location = "/tenants/${tenantId.key}/code-lists/${catalogKey.value}/${slug.value}"
        return if (request.isHtmx) {
            ServerResponse.ok().header("HX-Redirect", location).build()
        } else {
            ServerResponse.status(303).header("Location", location).build()
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val codeListId = request.codeListId(tenantId) ?: return ServerResponse.badRequest().build()
        val codeList = GetCodeList(codeListId).query() ?: return ServerResponse.notFound().build()
        val includeHidden = request.queryParam("show") == "all"
        val entries = ListCodeListEntries(codeListId, includeHidden = includeHidden).query()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val editable = codeList.catalogType == CatalogType.AUTHORED
        return ServerResponse.ok().page("code-lists/detail") {
            "pageTitle" to "${codeList.displayName} - Code list"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "codeList" to codeList
            "entries" to entries
            "editable" to editable
            "includeHidden" to includeHidden
        }
    }

    fun refresh(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val codeListId = request.codeListId(tenantId) ?: return ServerResponse.badRequest().build()
        RefreshCodeList(codeListId).execute()
        return ServerResponse.status(303)
            .header(
                "Location",
                "/tenants/${tenantId.key}/code-lists/${codeListId.catalogKey.value}/${codeListId.key.value}",
            )
            .build()
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val codeListId = request.codeListId(tenantId) ?: return ServerResponse.badRequest().build()
        return try {
            DeleteCodeList(codeListId).execute()
            val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
            val catalogs = ListCatalogs(tenantId.key).query()
            val codeLists = ListCodeLists(tenantId = tenantId).query()
            request.htmx {
                fragment("code-lists/list", "content") {
                    "pageTitle" to "Code lists - Epistola"
                    "tenant" to tenant
                    "tenantId" to tenantId.key
                    "catalogs" to catalogs
                    "selectedCatalog" to ""
                    "codeLists" to codeLists
                }
                pushUrl("/tenants/${tenantId.key}/code-lists")
                onNonHtmx { redirect("/tenants/${tenantId.key}/code-lists") }
            }
        } catch (e: CodeListInUseException) {
            if (request.isHtmx) {
                ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapOf("error" to (e.message ?: "Cannot delete")))
            } else {
                val codeList = GetCodeList(codeListId).query()
                    ?: return ServerResponse.notFound().build()
                val tenant = GetTenant(tenantId.key).query()
                    ?: return ServerResponse.notFound().build()
                ServerResponse.ok().page("code-lists/detail") {
                    "pageTitle" to "${codeList.displayName} - Code list"
                    "tenant" to tenant
                    "tenantId" to tenantId.key
                    "codeList" to codeList
                    "entries" to ListCodeListEntries(codeListId).query()
                    "editable" to (codeList.catalogType == CatalogType.AUTHORED)
                    "includeHidden" to false
                    "error" to (e.message ?: "Cannot delete")
                }
            }
        }
    }

    fun toggleEntryHidden(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val codeListId = request.codeListId(tenantId) ?: return ServerResponse.badRequest().build()
        val code = request.pathVariable("code")
        val newHidden = request.param("hidden").orElse("false").equals("true", ignoreCase = true)

        UpdateCodeListEntryHidden(codeListId, code, hidden = newHidden).execute()

        val codeList = GetCodeList(codeListId).query() ?: return ServerResponse.notFound().build()
        val entry = ListCodeListEntries(codeListId, includeHidden = true).query()
            .firstOrNull { it.code == code }
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("code-lists/detail", "entry-row") {
                "tenantId" to tenantId.key
                "codeList" to codeList
                "entry" to entry
                "editable" to true
            }
            reswap(HxSwap.OUTER_HTML)
        }
    }

    /**
     * Re-render the create form on validation error: the lone `createForm`
     * fragment swaps itself in place over HTMX (dialog stays open); a non-HTMX
     * request redirects to the list (dialog-only — no full-page fallback).
     */
    private fun renderNew(
        request: ServerRequest,
        form: FormData,
        catalogs: List<Catalog>,
        tenantKey: TenantKey,
    ): ServerResponse = request.htmx {
        fragment("code-lists/new", "createForm") {
            "tenantId" to tenantKey
            "catalogs" to catalogs
            "formData" to form.formData
            "errors" to form.errors
        }
        onNonHtmx { redirect("/tenants/$tenantKey/code-lists") }
    }

    private fun renderNewWithError(
        request: ServerRequest,
        form: FormData,
        catalogs: List<Catalog>,
        tenantKey: TenantKey,
        field: String,
        message: String,
    ): ServerResponse {
        val errors: Map<String, String> = mapOf(field to message)
        return renderNew(request, FormData(form.formData, errors), catalogs, tenantKey)
    }

    /**
     * Parses inline-entries JSON of the form `[{"code":"x","label":"X"}, ...]`
     * (the new-code-list form serializes its small entry editor into a hidden
     * input). Tolerates malformed input by returning an empty list — caller-side
     * validation then surfaces the "needs at least one entry" rule.
     */
    private fun parseInlineEntries(json: String): List<CodeListEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(json, ENTRIES_TYPE).filter { it.code.isNotBlank() && it.label.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    companion object {
        private val ENTRIES_TYPE = object : TypeReference<List<CodeListEntry>>() {}
    }
}
