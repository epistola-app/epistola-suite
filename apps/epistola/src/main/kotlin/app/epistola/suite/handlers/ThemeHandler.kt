package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.pathId
import app.epistola.suite.htmx.queryParam
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
)

@Component
class ThemeHandler(
    private val objectMapper: ObjectMapper,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val tenant = GetTenant(id = tenantKey).query()
        val themes = ListThemes(tenantId = tenantId).query()
        return ServerResponse.ok().page("themes/list") {
            "pageTitle" to "Themes - Epistola"
            "tenantId" to tenantKey
            "tenant" to tenant
            "themes" to themes
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val searchTerm = request.queryParam("q")
        val tenant = GetTenant(id = tenantKey).query()
        val themes = ListThemes(tenantId = tenantId, searchTerm = searchTerm).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantKey
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/$tenantKey/themes") }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        return ServerResponse.ok().page("themes/new") {
            "pageTitle" to "New Theme - Epistola"
            "tenantId" to tenantKey
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)

        val form = request.form {
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

        if (form.hasErrors()) {
            return ServerResponse.ok().page("themes/new") {
                "pageTitle" to "New Theme - Epistola"
                "tenantId" to tenantKey
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
                "tenantId" to tenantKey
                "formData" to form.formData
                "errors" to errors
            }
        }

        val name = form["name"]
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }

        val result = form.executeOrFormError {
            CreateTheme(
                id = ThemeId(themeKey, tenantId),
                name = name,
                description = description,
            ).execute()
        }

        if (result.hasErrors()) {
            return ServerResponse.ok().page("themes/new") {
                "pageTitle" to "New Theme - Epistola"
                "tenantId" to tenantKey
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantKey/themes/$themeKey")
            .build()
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val themeKey = request.pathId("themeId") { ThemeKey.validateOrNull(it) }
            ?: return ServerResponse.badRequest().build()

        val theme = GetTheme(id = ThemeId(themeKey, tenantId)).query()
            ?: return ServerResponse.notFound().build()

        // Serialize theme data as JSON for the Lit component
        val themeJson = mapOf(
            "id" to theme.id.value,
            "name" to theme.name,
            "description" to theme.description,
            "documentStyles" to theme.documentStyles,
            "pageSettings" to theme.pageSettings,
            "blockStylePresets" to (theme.blockStylePresets ?: emptyMap()),
        )

        return ServerResponse.ok().page("themes/detail") {
            "pageTitle" to "${theme.name} - Epistola"
            "tenantId" to tenantKey
            "theme" to theme
            "themeJson" to themeJson
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val themeKey = ThemeKey.validateOrNull(request.pathVariable("themeId"))
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateThemeRequest::class.java)

        val theme = UpdateTheme(
            id = ThemeId(themeKey, tenantId),
            name = updateRequest.name,
            description = updateRequest.description,
            clearDescription = updateRequest.clearDescription,
            documentStyles = updateRequest.documentStyles,
            pageSettings = updateRequest.pageSettings,
            clearPageSettings = updateRequest.clearPageSettings,
            blockStylePresets = updateRequest.blockStylePresets,
            clearBlockStylePresets = updateRequest.clearBlockStylePresets,
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
                    "lastModified" to theme.lastModified,
                ),
            )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val themeKey = ThemeKey.validateOrNull(request.pathVariable("themeId"))
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteTheme(id = ThemeId(themeKey, tenantId)).execute()
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
        val tenant = GetTenant(id = tenantKey).query()
        val themes = ListThemes(tenantId = tenantId).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantKey
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/$tenantKey/themes") }
        }
    }

    /**
     * Sets the default theme for a tenant.
     */
    fun setDefault(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val themeIdStr = request.params().getFirst("themeId")
            ?: return ServerResponse.badRequest().build()
        val themeKey = ThemeKey.validateOrNull(themeIdStr)
            ?: return ServerResponse.badRequest().build()

        try {
            SetTenantDefaultTheme(
                tenantId = tenantKey,
                themeId = themeKey,
            ).execute()
        } catch (e: ThemeNotFoundException) {
            return ServerResponse.notFound().build()
        }

        // Return updated rows for HTMX
        val tenant = GetTenant(id = tenantKey).query()
        val themes = ListThemes(tenantId = tenantId).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantKey
                "tenant" to tenant
                "themes" to themes
            }
            onNonHtmx { redirect("/tenants/$tenantKey/themes") }
        }
    }
}
