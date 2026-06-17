package app.epistola.suite.themes

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.htmxCurrentUrl
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.themeId
import app.epistola.suite.htmx.urlWithCreateParam
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
        val createOpen = request.queryParam("create") != null
        return ServerResponse.ok().page("themes/list") {
            "pageTitle" to "Themes - Epistola"
            "tenantId" to tenantId.key
            "tenant" to tenant
            "catalogs" to catalogs
            "selectedCatalog" to (catalogFilter?.value ?: "")
            "themes" to themes
            "createOpen" to createOpen
            "authoredCatalogs" to catalogs.filter { it.type == CatalogType.AUTHORED }
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

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }
        // HTMX requests load the dialog into #dialog-host; a direct GET (no-JS,
        // deep link) still renders the full-page fallback.
        return request.htmx {
            fragment("themes/new", "createDialog") {
                "tenantId" to tenantId.key
                "catalogs" to catalogs
            }
            pushUrl(urlWithCreateParam(request.htmxCurrentUrl, "/tenants/${tenantId.key}/themes"))
            onNonHtmx { redirect("/tenants/${tenantId.key}/themes") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("catalog") {}
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(20)
            }
            field("name") {
                required()
                maxLength(100)
            }
            field("description") {}
        }

        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }

        // Re-render on validation error: for HTMX the lone `createForm` fragment
        // swaps itself in place (hx-target="this"), keeping the dialog open with
        // field errors; for non-HTMX the full page is redrawn.
        fun reRender(
            formData: Map<String, String>,
            errors: Map<String, String>,
        ): ServerResponse = request.htmx {
            fragment("themes/new", "createForm") {
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "formData" to formData
                "errors" to errors
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/themes") }
        }

        val catalogValue = form.formData["catalog"]?.ifBlank { null }
            ?: return reRender(form.formData, form.errors + ("catalog" to "Catalog is required"))
        val catalogKey = CatalogKey.of(catalogValue)

        if (form.hasErrors()) {
            return reRender(form.formData, form.errors)
        }

        // Validate slug format as ThemeKey
        val themeKey = ThemeKey.validateOrNull(form["slug"])
            ?: return reRender(form.formData, mapOf("slug" to "Invalid theme ID format"))

        val name = form["name"]
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }

        val result = form.executeOrFormError {
            CreateTheme(
                id = ThemeId(themeKey, CatalogId(catalogKey, tenantId)),
                name = name,
                description = description,
            ).execute()
        }

        if (result.hasErrors()) {
            return reRender(result.formData, result.errors)
        }

        val location = "/tenants/${tenantId.key}/themes/$catalogKey/$themeKey"
        return if (request.isHtmx) {
            // The dialog submits over HTMX; tell the client to navigate to the theme editor.
            ServerResponse.ok().header("HX-Redirect", location).build()
        } else {
            ServerResponse.status(303).header("Location", location).build()
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
