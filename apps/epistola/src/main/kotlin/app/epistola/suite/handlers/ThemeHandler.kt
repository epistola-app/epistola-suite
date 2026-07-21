package app.epistola.suite.themes

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.themeId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

/**
 * Request body for updating a theme via PATCH.
 */
data class UpdateThemeRequest(
    val name: String? = null,
    val description: String? = null,
    val clearDescription: Boolean = false,
    val documentStyles: DocumentStyles? = null,
    val pageSettings: PageSettings? = null,
    val clearPageSettings: Boolean = false,
    val blockStylePresets: BlockStylePresets? = null,
    val clearBlockStylePresets: Boolean = false,
    val spacingUnit: Float? = null,
    val clearSpacingUnit: Boolean = false,
)

@Component
class ThemeHandler(
    private val objectMapper: ObjectMapper,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }
        val tenant = GetTenant(id = tenantId.key).query()
        val catalogs = ListCatalogs(tenantId.key).query()
        val themes = ListThemes(tenantId = tenantId, catalogKey = catalogFilter).query()
        return ServerResponse.ok().page("themes/list") {
            "pageTitle" to "Themes - Epistola"
            "tenantId" to tenantId.key
            "tenant" to tenant
            "catalogs" to catalogs
            "selectedCatalog" to (catalogFilter?.value ?: "")
            "themes" to themes
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val catalogFilter = request.queryParam("catalog")?.ifBlank { null }?.let { CatalogKey.of(it) }
        val tenant = GetTenant(id = tenantId.key).query()
        val themes = ListThemes(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId.key
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/themes") }
        }
    }

    /**
     * The full-page list model, used by the newForm / create non-HTMX branches so
     * the list renders behind the embedded create dialog. `authoredCatalogs` (the
     * dialog's catalog `<select>` source) is threaded separately — the list puts
     * *all* `catalogs` in the model for its filter, so the dialog uses a distinct
     * key to avoid rendering the wrong (non-authored) options.
     */
    private fun ModelBuilder.themePageModel(tenantId: TenantId, catalogs: List<Catalog>) {
        "pageTitle" to "Themes - Epistola"
        "tenantId" to tenantId.key
        "tenant" to GetTenant(id = tenantId.key).query()
        "catalogs" to catalogs
        "selectedCatalog" to ""
        "themes" to ListThemes(tenantId = tenantId).query()
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.THEME_EDIT)
        // Fragment models are lazy, so only the branch that renders evaluates —
        // one ListCatalogs per request, shared by the list filter and the
        // dialog's authored-only <select>.
        val allCatalogs by lazy { ListCatalogs(tenantId.key).query() }
        val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("themes/new", "dialog") {
                "tenantId" to tenantId.key
                "authoredCatalogs" to authoredCatalogs
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("themes/list") {
                    themePageModel(tenantId, allCatalogs)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.THEME_EDIT)

        val form = request.form {
            // Validated like any other field so a missing or malformed catalog
            // lands in the dialog's error path rather than a bodyless 400 (which
            // HTMX does not swap) or a CatalogKey.of throw (a 500).
            field("catalog") {
                required()
                asCatalogId()
            }
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(20)
                // Folds the old "invalid ThemeKey" branch into field validation
                // (same "Invalid theme ID format" error) so all three failure
                // modes share one error path. Reported only when no rule above
                // failed first, so the specific message wins.
                asThemeId()
            }
            field("name") {
                required()
                maxLength(100)
            }
            field("description") {}
        }

        val description = form["description"].trim().takeIf { it.isNotEmpty() }

        // Field validation (incl. catalog/CatalogKey and slug/ThemeKey) and the
        // command-level failure (duplicate slug, name length) both land as `errors`
        // on the FormData, so they share one error path — mirroring
        // DocumentTemplateHandler.create.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateTheme(
                    id = ThemeId(
                        ThemeKey.validateOrNull(form["slug"])!!,
                        CatalogId(CatalogKey.of(form["catalog"]), tenantId),
                    ),
                    name = form["name"],
                    description = description,
                ).execute()
            }
        }

        if (result.hasErrors()) {
            // One ListCatalogs whichever branch renders (fragment models are lazy).
            val allCatalogs by lazy { ListCatalogs(tenantId.key).query() }
            val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form, not
                // the list) with inline errors + preserved values. `tenantId` and
                // `authoredCatalogs` are the prefill the form fragment needs to
                // rebuild its action URL and catalog <select>.
                dialogFieldErrors(
                    template = "themes/new",
                    fragmentName = "theme-form",
                    formTarget = "#create-theme-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                    "authoredCatalogs" to authoredCatalogs
                }
                onNonHtmx {
                    page(422, "themes/list") {
                        themePageModel(tenantId, allCatalogs)
                        "openDialog" to true
                        "authoredCatalogs" to authoredCatalogs
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: navigate to the newly created theme's page. Soft boosted
        // navigation (HX-Location) — the same body-swap a theme click from the list
        // already performs, so the theme editor's body-hosted boot script re-runs
        // as usual; the dialog goes with the swapped-out body. (Experiment: was
        // HX-Redirect; trying the soft path to drop the full-page reload.)
        // Safe !!/of(): both fields were validated above and the create succeeded.
        val themeKey = ThemeKey.validateOrNull(form["slug"])!!
        val catalogKey = CatalogKey.of(form["catalog"])
        val destination = "/tenants/${tenantId.key}/themes/$catalogKey/$themeKey"
        return request.htmx {
            dialogLocation(destination)
            onNonHtmx { redirect(destination) }
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val themeId = request.themeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val theme = GetTheme(id = themeId).query()
            ?: return ServerResponse.notFound().build()

        // Serialize theme data as JSON for the Lit component
        val themeJson = mapOf(
            "id" to theme.id.value,
            "name" to theme.name,
            "description" to theme.description,
            "documentStyles" to theme.documentStyles,
            "pageSettings" to theme.pageSettings,
            "blockStylePresets" to (theme.blockStylePresets ?: emptyMap()),
            "spacingUnit" to theme.spacingUnit,
        )

        val editable = theme.catalogType == app.epistola.suite.catalog.CatalogType.AUTHORED

        return ServerResponse.ok().page("themes/detail") {
            "pageTitle" to "${theme.name} - Epistola"
            "tenantId" to tenantId.key
            "catalogId" to catalogId.value
            "theme" to theme
            "themeJson" to themeJson
            "editable" to editable
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val themeId = request.themeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateThemeRequest::class.java)

        val theme = UpdateTheme(
            id = themeId,
            name = updateRequest.name,
            description = updateRequest.description,
            clearDescription = updateRequest.clearDescription,
            documentStyles = updateRequest.documentStyles,
            pageSettings = updateRequest.pageSettings,
            clearPageSettings = updateRequest.clearPageSettings,
            blockStylePresets = updateRequest.blockStylePresets,
            clearBlockStylePresets = updateRequest.clearBlockStylePresets,
            spacingUnit = updateRequest.spacingUnit,
            clearSpacingUnit = updateRequest.clearSpacingUnit,
        ).execute()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to theme.id.value,
                    "name" to theme.name,
                    "description" to theme.description,
                    "documentStyles" to theme.documentStyles,
                    "pageSettings" to theme.pageSettings,
                    "blockStylePresets" to theme.blockStylePresets,
                    "spacingUnit" to theme.spacingUnit,
                    "updatedAt" to theme.updatedAt,
                ),
            )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val themeId = request.themeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteTheme(id = themeId).execute()
        } catch (e: ThemeInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Return updated rows for HTMX
        val tenant = GetTenant(id = tenantId.key).query()
        val themes = ListThemes(tenantId = tenantId).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId.key
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/themes") }
        }
    }

    /**
     * Sets (or clears) the default theme for a tenant. An empty/missing
     * `themeId` form param clears the default; templates without their own
     * theme then render with engine defaults.
     */
    fun setDefault(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val themeIdStr = request.params().getFirst("themeId")?.takeIf { it.isNotBlank() }
        val catalogKeyStr = request.params().getFirst("catalogKey")?.takeIf { it.isNotBlank() }

        val themeKey = themeIdStr?.let { ThemeKey.validateOrNull(it) ?: return ServerResponse.badRequest().build() }
        val catalogKey = catalogKeyStr?.let { CatalogKey.of(it) }

        try {
            SetTenantDefaultTheme(
                tenantId = tenantId.key,
                themeId = themeKey,
                catalogKey = catalogKey,
            ).execute()
        } catch (e: ThemeNotFoundException) {
            return ServerResponse.notFound().build()
        }

        // Return updated rows for HTMX
        val tenant = GetTenant(id = tenantId.key).query()
        val themes = ListThemes(tenantId = tenantId).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId.key
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/themes") }
        }
    }
}
