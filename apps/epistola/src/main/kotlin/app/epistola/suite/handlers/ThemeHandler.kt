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
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.themeId
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

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }
        return ServerResponse.ok().page("themes/new") {
            "pageTitle" to "New Theme - Epistola"
            "tenantId" to tenantId.key
            "catalogs" to catalogs
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

        val catalogKey = CatalogKey.of(form.formData["catalog"]?.ifBlank { null } ?: CatalogKey.DEFAULT.value)
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }

        if (form.hasErrors()) {
            return ServerResponse.ok().page("themes/new") {
                "pageTitle" to "New Theme - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "formData" to form.formData
                "errors" to form.errors
            }
        }

        // Validate slug format as ThemeKey
        val themeKey = ThemeKey.validateOrNull(form["slug"])
        if (themeKey == null) {
            val errors = mapOf("slug" to "Invalid theme ID format")
            return ServerResponse.ok().page("themes/new") {
                "pageTitle" to "New Theme - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "formData" to form.formData
                "errors" to errors
            }
        }

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
            return ServerResponse.ok().page("themes/new") {
                "pageTitle" to "New Theme - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/themes/$catalogKey/$themeKey")
            .build()
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
                    "lastModified" to theme.lastModified,
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
        } catch (e: LastThemeException) {
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
     * Sets the default theme for a tenant.
     */
    fun setDefault(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val themeIdStr = request.params().getFirst("themeId")
            ?: return ServerResponse.badRequest().build()
        val themeKey = ThemeKey.validateOrNull(themeIdStr)
            ?: return ServerResponse.badRequest().build()

        try {
            SetTenantDefaultTheme(
                tenantId = tenantId.key,
                themeId = themeKey,
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
