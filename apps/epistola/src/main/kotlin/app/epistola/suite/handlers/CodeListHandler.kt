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
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.codeListId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
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
        return ServerResponse.ok().page("code-lists/list") {
            "pageTitle" to "Code lists - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "catalogs" to catalogs
            "selectedCatalog" to (catalogFilter?.value ?: "")
            "codeLists" to codeLists
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        // Fragment models are lazy, so only the branch that renders evaluates —
        // one ListCatalogs per request, shared by the list filter and the
        // dialog's authored-only <select>.
        val allCatalogs by lazy { ListCatalogs(tenantId.key).query() }
        val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("code-lists/new", "dialog") {
                "tenantId" to tenantId.key
                "authoredCatalogs" to authoredCatalogs
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("code-lists/list") {
                    codeListPageModel(tenantId, allCatalogs)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)

        val form = request.form {
            field("catalog") { required() }
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(64)
                // Folds the old "invalid CodeListKey" branch into field validation
                // (same "Invalid code-list ID format" error) so all failure modes
                // share one error path. Layered ON TOP of the pattern/length rules
                // above — strictly additive, never loosening them.
                asCodeListId()
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

        if (form.hasErrors()) {
            return renderCreateError(request, form, tenantId)
        }

        val catalogKey = CatalogKey.validateOrNull(form["catalog"])
            ?: return renderCreateError(request, FormData(form.formData, mapOf("catalog" to "Invalid catalog")), tenantId)
        // Safe !!: asCodeListId already rejected an invalid non-blank slug, and
        // required() rejected a blank one, so a valid key is guaranteed here.
        val slug = CodeListKey.validateOrNull(form["slug"])!!
        val sourceType = runCatching { CodeListSource.valueOf(form["sourceType"]) }.getOrNull()
            ?: return renderCreateError(request, FormData(form.formData, mapOf("sourceType" to "Unknown source type")), tenantId)

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
            return renderCreateError(request, result, tenantId)
        }

        // Success: soft-navigate to the newly created code list's page via a
        // boosted body-swap (HX-Location) — no full-page reload. The dialog goes
        // with the swapped-out body; the list is not refreshed.
        val destination = "/tenants/${tenantId.key}/code-lists/${catalogKey.value}/${slug.value}"
        return request.htmx {
            dialogLocation(destination)
            onNonHtmx { redirect(destination) }
        }
    }

    /**
     * The single error-render path for [create]: re-render the form inside the
     * dialog (retargeted to the form, not the list) with inline errors + preserved
     * values for HTMX, or the host list page with the dialog embedded and open for
     * a non-HTMX submit. `tenantId` and `authoredCatalogs` are the prefill the form
     * fragment needs to rebuild its action URL and catalog <select>.
     */
    private fun renderCreateError(
        request: ServerRequest,
        formData: FormData,
        tenantId: TenantId,
    ): ServerResponse {
        // One ListCatalogs whichever branch renders (fragment models are lazy).
        val allCatalogs by lazy { ListCatalogs(tenantId.key).query() }
        val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
        return request.htmx {
            dialogFieldErrors(
                template = "code-lists/new",
                fragmentName = "code-list-form",
                formTarget = "#create-code-list-form",
                formData = formData,
            ) {
                "tenantId" to tenantId.key
                "authoredCatalogs" to authoredCatalogs
            }
            onNonHtmx {
                page(422, "code-lists/list") {
                    codeListPageModel(tenantId, allCatalogs)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs
                    "formData" to formData.formData
                    "errors" to formData.errors
                }
            }
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
     * The full-page list model, used by the newForm / create non-HTMX branches so
     * the list renders behind the embedded create dialog. `authoredCatalogs` (the
     * dialog's catalog `<select>` source) is threaded separately by the callers —
     * the list already puts *all* `catalogs` in the model for its filter, so the
     * dialog uses a distinct key to avoid rendering the wrong (non-authored) options.
     */
    private fun ModelBuilder.codeListPageModel(
        tenantId: TenantId,
        catalogs: List<Catalog>,
    ) {
        "pageTitle" to "Code lists - Epistola"
        "tenant" to GetTenant(tenantId.key).query()
        "tenantId" to tenantId.key
        "catalogs" to catalogs
        "selectedCatalog" to ""
        "codeLists" to ListCodeLists(tenantId = tenantId).query()
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
